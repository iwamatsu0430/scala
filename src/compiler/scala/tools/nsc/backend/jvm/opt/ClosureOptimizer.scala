/* NSC -- new Scala compiler
 * Copyright 2005-2015 LAMP/EPFL
 * @author  Martin Odersky
 */

package scala.tools.nsc
package backend.jvm
package opt

import scala.annotation.switch
import scala.collection.immutable
import scala.collection.immutable.IntMap
import scala.reflect.internal.util.NoPosition
import scala.tools.asm.{Type, Opcodes}
import scala.tools.asm.tree._
import scala.tools.nsc.backend.jvm.BTypes.InternalName
import BytecodeUtils._
import BackendReporting._
import Opcodes._
import scala.tools.nsc.backend.jvm.opt.ByteCodeRepository.CompilationUnit
import scala.collection.convert.decorateAsScala._

class ClosureOptimizer[BT <: BTypes](val btypes: BT) {
  import btypes._
  import callGraph._
  import analyzers._

  /**
   * If a closure is allocated and invoked within the same method, re-write the invocation to the
   * closure body method.
   *
   * Note that the closure body method (generated by delambdafy:method) takes additional parameters
   * for the values captured by the closure. The bytecode is transformed from
   *
   *   [generate captured values]
   *   [closure init, capturing values]
   *   [...]
   *   [load closure object]
   *   [generate closure invocation arguments]
   *   [invoke closure.apply]
   *
   * to
   *
   *   [generate captured values]
   *   [store captured values into new locals]
   *   [load the captured values from locals]    // a future optimization will eliminate the closure
   *   [closure init, capturing values]          // instantiation if the closure object becomes unused
   *   [...]
   *   [load closure object]
   *   [generate closure invocation arguments]
   *   [store argument values into new locals]
   *   [drop the closure object]
   *   [load captured values from locals]
   *   [load argument values from locals]
   *   [invoke the closure body method]
   */
  def rewriteClosureApplyInvocations(): Unit = {
    implicit object closureInitOrdering extends Ordering[ClosureInstantiation] {
      override def compare(x: ClosureInstantiation, y: ClosureInstantiation): Int = {
        val cls = x.ownerClass.internalName compareTo y.ownerClass.internalName
        if (cls != 0) return cls

        val mName = x.ownerMethod.name compareTo y.ownerMethod.name
        if (mName != 0) return mName

        val mDesc = x.ownerMethod.desc compareTo y.ownerMethod.desc
        if (mDesc != 0) return mDesc

        def pos(inst: ClosureInstantiation) = inst.ownerMethod.instructions.indexOf(inst.lambdaMetaFactoryCall.indy)
        pos(x) - pos(y)
      }
    }

    // For each closure instantiation, a list of callsites of the closure that can be re-written
    // If a callsite cannot be rewritten, for example because the lambda body method is not accessible,
    // a warning is returned instead.
    val callsitesToRewrite: List[(ClosureInstantiation, List[Either[RewriteClosureApplyToClosureBodyFailed, (MethodInsnNode, Int)]])] = {
      closureInstantiations.iterator.flatMap({
        case (methodNode, closureInits) =>
          // A lazy val to ensure the analysis only runs if necessary (the value is passed by name to `closureCallsites`)
          // We don't need to worry about the method being too large for running an analysis: large
          // methods are not added to the call graph / closureInstantiations map.
          lazy val prodCons = new ProdConsAnalyzer(methodNode, closureInits.valuesIterator.next().ownerClass.internalName)
          val sortedInits = immutable.TreeSet.empty ++ closureInits.values
          sortedInits.iterator.map(init => (init, closureCallsites(init, prodCons))).filter(_._2.nonEmpty)
      }).toList // mapping to a list (not a map) to keep the sorting
    }

    // Rewrite all closure callsites (or issue inliner warnings for those that cannot be rewritten)
    for ((closureInit, callsites) <- callsitesToRewrite) {
      // Local variables that hold the captured values and the closure invocation arguments.
      // They are lazy vals to ensure that locals for captured values are only allocated if there's
      // actually a callsite to rewrite (an not only warnings to be issued).
      lazy val (localsForCapturedValues, argumentLocalsList) = localsForClosureRewrite(closureInit)
      for (callsite <- callsites) callsite match {
        case Left(warning) =>
          backendReporting.inlinerWarning(warning.pos, warning.toString)

        case Right((invocation, stackHeight)) =>
          rewriteClosureApplyInvocation(closureInit, invocation, stackHeight, localsForCapturedValues, argumentLocalsList)
      }
    }
  }

  /**
   * Insert instructions to store the values captured by a closure instantiation into local variables,
   * and load the values back to the stack.
   *
   * Returns the list of locals holding those captured values, and a list of locals that should be
   * used at the closure invocation callsite to store the arguments passed to the closure invocation.
   */
  private def localsForClosureRewrite(closureInit: ClosureInstantiation): (LocalsList, LocalsList) = {
    val ownerMethod = closureInit.ownerMethod
    val captureLocals = storeCaptures(closureInit)

    // allocate locals for storing the arguments of the closure apply callsites.
    // if there are multiple callsites, the same locals are re-used.
    val argTypes = closureInit.lambdaMetaFactoryCall.samMethodType.getArgumentTypes
    val firstArgLocal = ownerMethod.maxLocals

    // The comment in the unapply method of `LambdaMetaFactoryCall` explains why we have to introduce
    // casts for arguments that have different types in samMethodType and instantiatedMethodType.
    val castLoadTypes = {
      val instantiatedMethodType = closureInit.lambdaMetaFactoryCall.instantiatedMethodType
      (argTypes, instantiatedMethodType.getArgumentTypes).zipped map {
        case (samArgType, instantiatedArgType) if samArgType != instantiatedArgType =>
          // the LambdaMetaFactoryCall extractor ensures that the two types are reference types,
          // so we don't end up casting primitive values.
          Some(instantiatedArgType)
        case _ =>
          None
      }
    }
    val argLocals = LocalsList.fromTypes(firstArgLocal, argTypes, castLoadTypes)
    ownerMethod.maxLocals = firstArgLocal + argLocals.size

    (captureLocals, argLocals)
  }

  /**
   * Find all callsites of a closure within the method where the closure is allocated.
   */
  private def closureCallsites(closureInit: ClosureInstantiation, prodCons: => ProdConsAnalyzer): List[Either[RewriteClosureApplyToClosureBodyFailed, (MethodInsnNode, Int)]] = {
    val ownerMethod = closureInit.ownerMethod
    val ownerClass = closureInit.ownerClass
    val lambdaBodyHandle = closureInit.lambdaMetaFactoryCall.implMethod

    ownerMethod.instructions.iterator.asScala.collect({
      case invocation: MethodInsnNode if isSamInvocation(invocation, closureInit, prodCons) =>
        // TODO: This is maybe over-cautious.
        // We are checking if the closure body method is accessible at the closure callsite.
        // If the closure allocation has access to the body method, then the callsite (in the same
        // method as the alloction) should have access too.
        val bodyAccessible: Either[OptimizerWarning, Boolean] = for {
          (bodyMethodNode, declClass) <- byteCodeRepository.methodNode(lambdaBodyHandle.getOwner, lambdaBodyHandle.getName, lambdaBodyHandle.getDesc): Either[OptimizerWarning, (MethodNode, InternalName)]
          isAccessible                <- inliner.memberIsAccessible(bodyMethodNode.access, classBTypeFromParsedClassfile(declClass), classBTypeFromParsedClassfile(lambdaBodyHandle.getOwner), ownerClass)
        } yield {
          isAccessible
        }

        def pos = callGraph.callsites(ownerMethod).get(invocation).map(_.callsitePosition).getOrElse(NoPosition)
        val stackSize: Either[RewriteClosureApplyToClosureBodyFailed, Int] = bodyAccessible match {
          case Left(w)      => Left(RewriteClosureAccessCheckFailed(pos, w))
          case Right(false) => Left(RewriteClosureIllegalAccess(pos, ownerClass.internalName))
          case _            => Right(prodCons.frameAt(invocation).getStackSize)
        }

        stackSize.right.map((invocation, _))
    }).toList
  }

  private def isSamInvocation(invocation: MethodInsnNode, closureInit: ClosureInstantiation, prodCons: => ProdConsAnalyzer): Boolean = {
    val indy = closureInit.lambdaMetaFactoryCall.indy
    if (invocation.getOpcode == INVOKESTATIC) false
    else {
      def closureIsReceiver = {
        val invocationFrame = prodCons.frameAt(invocation)
        val receiverSlot = {
          val numArgs = Type.getArgumentTypes(invocation.desc).length
          invocationFrame.stackTop - numArgs
        }
        val receiverProducers = prodCons.initialProducersForValueAt(invocation, receiverSlot)
        receiverProducers.size == 1 && receiverProducers.head == indy
      }

      invocation.name == indy.name && {
        val indySamMethodDesc = closureInit.lambdaMetaFactoryCall.samMethodType.getDescriptor
        indySamMethodDesc == invocation.desc
      } &&
        closureIsReceiver // most expensive check last
    }
  }

  private def rewriteClosureApplyInvocation(closureInit: ClosureInstantiation, invocation: MethodInsnNode, stackHeight: Int, localsForCapturedValues: LocalsList, argumentLocalsList: LocalsList): Unit = {
    val ownerMethod = closureInit.ownerMethod
    val lambdaBodyHandle = closureInit.lambdaMetaFactoryCall.implMethod

    // store arguments
    insertStoreOps(invocation, ownerMethod, argumentLocalsList)

    // drop the closure from the stack
    ownerMethod.instructions.insertBefore(invocation, new InsnNode(POP))

    // load captured values and arguments
    insertLoadOps(invocation, ownerMethod, localsForCapturedValues)
    insertLoadOps(invocation, ownerMethod, argumentLocalsList)

    // update maxStack
    // One slot per value is correct for long / double, see comment in the `analysis` package object.
    val numCapturedValues = localsForCapturedValues.locals.length
    val invocationStackHeight = stackHeight + numCapturedValues - 1 // -1 because the closure is gone
    if (invocationStackHeight > ownerMethod.maxStack)
      ownerMethod.maxStack = invocationStackHeight

    // replace the callsite with a new call to the body method
    val bodyOpcode = (lambdaBodyHandle.getTag: @switch) match {
      case H_INVOKEVIRTUAL    => INVOKEVIRTUAL
      case H_INVOKESTATIC     => INVOKESTATIC
      case H_INVOKESPECIAL    => INVOKESPECIAL
      case H_INVOKEINTERFACE  => INVOKEINTERFACE
      case H_NEWINVOKESPECIAL =>
        val insns = ownerMethod.instructions
        insns.insertBefore(invocation, new TypeInsnNode(NEW, lambdaBodyHandle.getOwner))
        insns.insertBefore(invocation, new InsnNode(DUP))
        INVOKESPECIAL
    }
    val isInterface = bodyOpcode == INVOKEINTERFACE
    val bodyInvocation = new MethodInsnNode(bodyOpcode, lambdaBodyHandle.getOwner, lambdaBodyHandle.getName, lambdaBodyHandle.getDesc, isInterface)
    ownerMethod.instructions.insertBefore(invocation, bodyInvocation)

    val returnType = Type.getReturnType(lambdaBodyHandle.getDesc)
    fixLoadedNothingOrNullValue(returnType, bodyInvocation, ownerMethod, btypes) // see comment of that method

    ownerMethod.instructions.remove(invocation)

    // update the call graph
    val originalCallsite = callGraph.removeCallsite(invocation, ownerMethod)

    // the method node is needed for building the call graph entry
    val bodyMethod = byteCodeRepository.methodNode(lambdaBodyHandle.getOwner, lambdaBodyHandle.getName, lambdaBodyHandle.getDesc)
    def bodyMethodIsBeingCompiled = byteCodeRepository.classNodeAndSource(lambdaBodyHandle.getOwner).map(_._2 == CompilationUnit).getOrElse(false)
    val callee = bodyMethod.map({
      case (bodyMethodNode, bodyMethodDeclClass) =>
        val bodyDeclClassType = classBTypeFromParsedClassfile(bodyMethodDeclClass)
        Callee(
          callee = bodyMethodNode,
          calleeDeclarationClass = bodyDeclClassType,
          safeToInline = compilerSettings.YoptInlineGlobal || bodyMethodIsBeingCompiled,
          safeToRewrite = false, // the lambda body method is not a trait interface method
          annotatedInline = false,
          annotatedNoInline = false,
          samParamTypes = callGraph.samParamTypes(bodyMethodNode, bodyDeclClassType),
          calleeInfoWarning = None)
    })
    val argInfos = closureInit.capturedArgInfos ++ originalCallsite.map(cs => cs.argInfos map {
      case (index, info) => (index + numCapturedValues, info)
    }).getOrElse(IntMap.empty)
    val bodyMethodCallsite = Callsite(
      callsiteInstruction = bodyInvocation,
      callsiteMethod = ownerMethod,
      callsiteClass = closureInit.ownerClass,
      callee = callee,
      argInfos = argInfos,
      callsiteStackHeight = invocationStackHeight,
      receiverKnownNotNull = true, // see below (*)
      callsitePosition = originalCallsite.map(_.callsitePosition).getOrElse(NoPosition)
    )
    // (*) The documentation in class LambdaMetafactory says:
    //     "if implMethod corresponds to an instance method, the first capture argument
    //     (corresponding to the receiver) must be non-null"
    // Explanation: If the lambda body method is non-static, the receiver is a captured
    // value. It can only be captured within some instance method, so we know it's non-null.
    callGraph.addCallsite(bodyMethodCallsite)

    // Rewriting a closure invocation may render code unreachable. For example, the body method of
    // (x: T) => ??? has return type Nothing$, and an ATHROW is added (see fixLoadedNothingOrNullValue).
    unreachableCodeEliminated -= ownerMethod
  }

  /**
   * Stores the values captured by a closure creation into fresh local variables, and loads the
   * values back onto the stack. Returns the list of locals holding the captured values.
   */
  private def storeCaptures(closureInit: ClosureInstantiation): LocalsList = {
    val indy = closureInit.lambdaMetaFactoryCall.indy
    val capturedTypes = Type.getArgumentTypes(indy.desc)
    val firstCaptureLocal = closureInit.ownerMethod.maxLocals

    // This could be optimized: in many cases the captured values are produced by LOAD instructions.
    // If the variable is not modified within the method, we could avoid introducing yet another
    // local. On the other hand, further optimizations (copy propagation, remove unused locals) will
    // clean it up.

    // Captured variables don't need to be cast when loaded at the callsite (castLoadTypes are None).
    // This is checked in `isClosureInstantiation`: the types of the captured variables in the indy
    // instruction match exactly the corresponding parameter types in the body method.
    val localsForCaptures = LocalsList.fromTypes(firstCaptureLocal, capturedTypes, castLoadTypes = _ => None)
    closureInit.ownerMethod.maxLocals = firstCaptureLocal + localsForCaptures.size

    insertStoreOps(indy, closureInit.ownerMethod, localsForCaptures)
    insertLoadOps(indy, closureInit.ownerMethod, localsForCaptures)

    localsForCaptures
  }

  /**
   * Insert store operations in front of the `before` instruction to copy stack values into the
   * locals denoted by `localsList`.
   *
   * The lowest stack value is stored in the head of the locals list, so the last local is stored first.
   */
  private def insertStoreOps(before: AbstractInsnNode, methodNode: MethodNode, localsList: LocalsList) =
    insertLocalValueOps(before, methodNode, localsList, store = true)

  /**
   * Insert load operations in front of the `before` instruction to copy the local values denoted
   * by `localsList` onto the stack.
   *
   * The head of the locals list will be the lowest value on the stack, so the first local is loaded first.
   */
  private def insertLoadOps(before: AbstractInsnNode, methodNode: MethodNode, localsList: LocalsList) =
    insertLocalValueOps(before, methodNode, localsList, store = false)

  private def insertLocalValueOps(before: AbstractInsnNode, methodNode: MethodNode, localsList: LocalsList, store: Boolean): Unit = {
    // If `store` is true, the first instruction needs to store into the last local of the `localsList`.
    // Load instructions on the other hand are emitted in the order of the list.
    // To avoid reversing the list, we use `insert(previousInstr)` for stores and `insertBefore(before)` for loads.
    lazy val previous = before.getPrevious
    for (l <- localsList.locals) {
      val varOp = new VarInsnNode(if (store) l.storeOpcode else l.loadOpcode, l.local)
      if (store) methodNode.instructions.insert(previous, varOp)
      else methodNode.instructions.insertBefore(before, varOp)
      if (!store) for (castType <- l.castLoadedValue)
        methodNode.instructions.insert(varOp, new TypeInsnNode(CHECKCAST, castType.getInternalName))
    }
  }

  /**
   * A list of local variables. Each local stores information about its type, see class [[Local]].
   */
  case class LocalsList(locals: List[Local]) {
    val size = locals.iterator.map(_.size).sum
  }

  object LocalsList {
    /**
     * A list of local variables starting at `firstLocal` that can hold values of the types in the
     * `types` parameter.
     *
     * For example, `fromTypes(3, Array(Int, Long, String))` returns
     *   Local(3, intOpOffset)  ::
     *   Local(4, longOpOffset) ::  // note that this local occupies two slots, the next is at 6
     *   Local(6, refOpOffset)  ::
     *   Nil
     */
    def fromTypes(firstLocal: Int, types: Array[Type], castLoadTypes: Int => Option[Type]): LocalsList = {
      var sizeTwoOffset = 0
      val locals: List[Local] = types.indices.map(i => {
        // The ASM method `type.getOpcode` returns the opcode for operating on a value of `type`.
        val offset = types(i).getOpcode(ILOAD) - ILOAD
        val local = Local(firstLocal + i + sizeTwoOffset, offset, castLoadTypes(i))
        if (local.size == 2) sizeTwoOffset += 1
        local
      })(collection.breakOut)
      LocalsList(locals)
    }
  }

  /**
   * Stores a local varaible index the opcode offset required for operating on that variable.
   *
   * The xLOAD / xSTORE opcodes are in the following sequence: I, L, F, D, A, so the offset for
   * a local variable holding a reference (`A`) is 4. See also method `getOpcode` in [[scala.tools.asm.Type]].
   */
  case class Local(local: Int, opcodeOffset: Int, castLoadedValue: Option[Type]) {
    def size = if (loadOpcode == LLOAD || loadOpcode == DLOAD) 2  else 1

    def loadOpcode = ILOAD + opcodeOffset
    def storeOpcode = ISTORE + opcodeOffset
  }
}
