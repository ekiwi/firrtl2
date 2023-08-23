// SPDX-License-Identifier: Apache-2.0

package firrtl2

import java.io.Writer
import scala.collection.mutable
import scala.collection.immutable.VectorBuilder
import firrtl2.annotations._
import firrtl2.ir.Circuit
import firrtl2.Utils.throwInternalError
import firrtl2.annotations.transforms.{EliminateTargetPaths, ResolvePaths}
import firrtl2.logger.{LazyLogging, Logger}
import firrtl2.options.{Dependency, DependencyAPI, TransformLike}
import firrtl2.stage.Forms
import firrtl2.transforms.DedupAnnotationsTransform

/** Current State of the Circuit
  *
  * @constructor Creates a CircuitState object
  * @param circuit     The current state of the Firrtl AST
  * @param form        The current form of the circuit
  * @param annotations The current collection of [[firrtl2.annotations.Annotation Annotation]]
  * @param renames     A map of [[firrtl2.annotations.Named Named]] things that have been renamed.
  *                    Generally only a return value from [[Transform]]s
  */
case class CircuitState(
  circuit:     Circuit,
  form:        CircuitForm,
  annotations: AnnotationSeq,
  renames:     Option[RenameMap]) {

  /** Helper for getting just an emitted circuit */
  def emittedCircuitOption: Option[EmittedCircuit] =
    emittedComponents.collectFirst { case x: EmittedCircuit => x }

  /** Helper for getting an [[EmittedCircuit]] when it is known to exist */
  def getEmittedCircuit: EmittedCircuit = emittedCircuitOption match {
    case Some(emittedCircuit) => emittedCircuit
    case None =>
      throw new FirrtlInternalException(
        s"No EmittedCircuit found! Did you delete any annotations?\n$deletedAnnotations"
      )
  }

  /** Helper function for extracting emitted components from annotations */
  def emittedComponents: Seq[EmittedComponent] =
    annotations.collect { case emitted: EmittedAnnotation[_] => emitted.value }
  def deletedAnnotations: Seq[Annotation] =
    annotations.collect { case anno: DeletedAnnotation => anno }

  /** Returns a new CircuitState with all targets being resolved.
    * Paths through instances are replaced with a uniquified final target
    * Includes modifying the circuit and annotations
    * @param targets
    * @return
    */
  def resolvePaths(targets: Seq[CompleteTarget]): CircuitState = targets match {
    case Nil => this
    case _ =>
      val newCS = new EliminateTargetPaths().runTransform(this.copy(annotations = ResolvePaths(targets) +: annotations))
      newCS.copy(form = form)
  }

  /** Returns a new CircuitState with the targets of every annotation of a type in annoClasses
    * @param annoClasses
    * @return
    */
  def resolvePathsOf(annoClasses: Class[_]*): CircuitState = {
    val targets = getAnnotationsOf(annoClasses: _*).flatMap(_.getTargets)
    if (targets.nonEmpty) resolvePaths(targets.flatMap { _.getComplete }) else this
  }

  /** Returns all annotations which are of a class in annoClasses
    * @param annoClasses
    * @return
    */
  def getAnnotationsOf(annoClasses: Class[_]*): AnnotationSeq = {
    annotations.collect { case a if annoClasses.contains(a.getClass) => a }
  }
}

object CircuitState {
  def apply(circuit: Circuit, form: CircuitForm): CircuitState = apply(circuit, form, Seq())
  def apply(circuit: Circuit, form: CircuitForm, annotations: AnnotationSeq): CircuitState =
    new CircuitState(circuit, form, annotations, None)
  def apply(circuit: Circuit, annotations: AnnotationSeq): CircuitState =
    new CircuitState(circuit, UnknownForm, annotations, None)
}

/** Current form of the Firrtl Circuit
  *
  * Form is a measure of addition restrictions on the legality of a Firrtl
  * circuit.  There is a notion of "highness" and "lowness" implemented in the
  * compiler by extending scala.math.Ordered. "Lower" forms add additional
  * restrictions compared to "higher" forms. This means that "higher" forms are
  * strictly supersets of the "lower" forms. Thus, that any transform that
  * operates on [[HighForm]] can also operate on [[MidForm]] or [[LowForm]]
  */
@deprecated(
  "Mix-in the DependencyAPIMigration trait into your Transform and specify its Dependency API dependencies. See: https://bit.ly/2Voppre",
  "FIRRTL 1.3"
)
sealed abstract class CircuitForm(private val value: Int) extends Ordered[CircuitForm] {
  // Note that value is used only to allow comparisons
  def compare(that: CircuitForm): Int = this.value - that.value

  /** Defines a suffix to use if this form is written to a file */
  def outputSuffix: String
}
private[firrtl2] object CircuitForm {
  // Private internal utils to reduce number of deprecation warnings
  val ChirrtlForm = firrtl2.ChirrtlForm
  val HighForm = firrtl2.HighForm
  val MidForm = firrtl2.MidForm
  val LowForm = firrtl2.LowForm
  val UnknownForm = firrtl2.UnknownForm
}

// These magic numbers give an ordering to CircuitForm
/** Chirrtl Form
  *
  * The form of the circuit emitted by Chisel. Not a true Firrtl form.
  * Includes cmem, smem, and mport IR nodes which enable declaring memories
  * separately form their ports. A "Higher" form than [[HighForm]]
  *
  * See [[CDefMemory]] and [[CDefMPort]]
  */
@deprecated(
  "Mix-in the DependencyAPIMigration trait into your Transform and specify its Dependency API dependencies. See: https://bit.ly/2Voppre",
  "FIRRTL 1.3"
)
final case object ChirrtlForm extends CircuitForm(value = 3) {
  val outputSuffix: String = ".fir"
}

/** High Form
  *
  * As detailed in the Firrtl specification
  * [[https://github.com/ucb-bar/firrtl/blob/master/spec/spec.pdf]]
  *
  * Also see [[firrtl2.ir]]
  */
@deprecated(
  "Mix-in the DependencyAPIMigration trait into your Transform and specify its Dependency API dependencies. See: https://bit.ly/2Voppre",
  "FIRRTL 1.3"
)
final case object HighForm extends CircuitForm(2) {
  val outputSuffix: String = ".hi.fir"
}

/** Middle Form
  *
  * A "lower" form than [[HighForm]] with the following restrictions:
  *  - All widths must be explicit
  *  - All whens must be removed
  *  - There can only be a single connection to any element
  */
@deprecated(
  "Mix-in the DependencyAPIMigration trait into your Transform and specify its Dependency API dependencies. See: https://bit.ly/2Voppre",
  "FIRRTL 1.3"
)
final case object MidForm extends CircuitForm(1) {
  val outputSuffix: String = ".mid.fir"
}

/** Low Form
  *
  * The "lowest" form. In addition to the restrictions in [[MidForm]]:
  *  - All aggregate types (vector/bundle) must have been removed
  *  - All implicit truncations must be made explicit
  */
@deprecated(
  "Mix-in the DependencyAPIMigration trait into your Transform and specify its Dependency API dependencies. See: https://bit.ly/2Voppre",
  "FIRRTL 1.3"
)
final case object LowForm extends CircuitForm(0) {
  val outputSuffix: String = ".lo.fir"
}

/** Unknown Form
  *
  * Often passes may modify a circuit (e.g. InferTypes), but return
  * a circuit in the same form it was given.
  *
  * For this use case, use UnknownForm. It cannot be compared against other
  * forms.
  *
  * TODO(azidar): Replace with PreviousForm, which more explicitly encodes
  * this requirement.
  */
@deprecated(
  "Mix-in the DependencyAPIMigration trait into your Transform and specify its Dependency API dependencies. See: https://bit.ly/2Voppre",
  "FIRRTL 1.3"
)
final case object UnknownForm extends CircuitForm(-1) {
  override def compare(that: CircuitForm): Int = { sys.error("Illegal to compare UnknownForm"); 0 }

  val outputSuffix: String = ".unknown.fir"
}

// Internal utilities to keep code DRY, not a clean interface
private[firrtl2] object Transform {

  def remapAnnotations(after: CircuitState, logger: Logger): CircuitState = {
    val remappedAnnotations = propagateAnnotations(after.annotations, after.renames)

    logger.trace(s"Annotations:")
    logger.trace(JsonProtocol.serializeRecover(remappedAnnotations))

    logger.trace(s"Circuit:\n${after.circuit.serialize}")

    CircuitState(after.circuit, after.form, remappedAnnotations, None)
  }

  // This function is *very* mutable but it is fairly performance critical
  def propagateAnnotations(
    resAnno:   AnnotationSeq,
    renameOpt: Option[RenameMap]
  ): AnnotationSeq = {
    // We dedup/distinct the resulting annotations when renaming occurs
    val seen = new mutable.HashSet[Annotation]
    val result = new VectorBuilder[Annotation]

    val hasRenames = renameOpt.isDefined
    val renames = renameOpt.getOrElse(null) // Null is bad but saving the allocation is worth it

    val it = resAnno.toSeq.iterator
    while (it.hasNext) {
      val anno = it.next()
      if (hasRenames) {
        val renamed = anno.update(renames)
        for (annox <- renamed) {
          if (!seen(annox)) {
            seen += annox
            result += annox
          }
        }
      } else {
        result += anno
      }
    }
    result.result()
  }
}

/** The basic unit of operating on a Firrtl AST */
trait Transform extends TransformLike[CircuitState] with DependencyAPI[Transform] {

  /** A convenience function useful for debugging and error messages */
  def name: String = this.getClass.getName

  /** The [[firrtl2.CircuitForm]] that this transform requires to operate on */
  @deprecated("Use Dependency API methods for equivalent functionality. See: https://bit.ly/2Voppre", "FIRRTL 1.3")
  def inputForm: CircuitForm

  /** The [[firrtl2.CircuitForm]] that this transform outputs */
  @deprecated("Use Dependency API methods for equivalent functionality. See: https://bit.ly/2Voppre", "FIRRTL 1.3")
  def outputForm: CircuitForm

  /** Perform the transform, encode renaming with RenameMap, and can
    *   delete annotations
    * Called by [[runTransform]].
    *
    * @param state Input Firrtl AST
    * @return A transformed Firrtl AST
    */
  protected def execute(state: CircuitState): CircuitState

  def transform(state: CircuitState): CircuitState = execute(state)

  import firrtl2.CircuitForm.{ChirrtlForm => C, HighForm => H, MidForm => M, LowForm => L, UnknownForm => U}

  override def prerequisites: Seq[Dependency[Transform]] = inputForm match {
    case C => Nil
    case H => Forms.Deduped
    case M => Forms.MidForm
    case L => Forms.LowForm
    case U => Nil
  }

  override def optionalPrerequisites: Seq[Dependency[Transform]] = inputForm match {
    case L => Forms.LowFormOptimized ++ Forms.AssertsRemoved
    case _ => Seq.empty
  }

  private lazy val fullCompilerSet = new mutable.LinkedHashSet[Dependency[Transform]] ++ Forms.VerilogOptimized

  override def optionalPrerequisiteOf: Seq[Dependency[Transform]] = {
    val lowEmitters = Dependency[LowFirrtlEmitter] :: Dependency[VerilogEmitter] :: Dependency[MinimumVerilogEmitter] ::
      Dependency[SystemVerilogEmitter] :: Nil

    val emitters = inputForm match {
      case C =>
        Dependency[ChirrtlEmitter] :: Dependency[HighFirrtlEmitter] :: Dependency[MiddleFirrtlEmitter] :: lowEmitters
      case H => Dependency[HighFirrtlEmitter] :: Dependency[MiddleFirrtlEmitter] :: lowEmitters
      case M => Dependency[MiddleFirrtlEmitter] :: lowEmitters
      case L => lowEmitters
      case U => Nil
    }

    val selfDep = Dependency.fromTransform(this)

    inputForm match {
      case C => (fullCompilerSet ++ emitters - selfDep).toSeq
      case H => (fullCompilerSet -- Forms.Deduped ++ emitters - selfDep).toSeq
      case M => (fullCompilerSet -- Forms.MidForm ++ emitters - selfDep).toSeq
      case L => (fullCompilerSet -- Forms.LowFormOptimized ++ emitters - selfDep).toSeq
      case U => Nil
    }
  }

  private lazy val highOutputInvalidates = fullCompilerSet -- Forms.MinimalHighForm
  private lazy val midOutputInvalidates = fullCompilerSet -- Forms.MidForm

  override def invalidates(a: Transform): Boolean = {
    (inputForm, outputForm) match {
      case (U, _) | (_, U)  => true // invalidate everything
      case (i, o) if i >= o => false // invalidate nothing
      case (_, C)           => true // invalidate everything
      case (_, H)           => highOutputInvalidates(Dependency.fromTransform(a))
      case (_, M)           => midOutputInvalidates(Dependency.fromTransform(a))
      case (_, L)           => false // invalidate nothing
    }
  }

  /** Executes before any transform's execute method
    * @param state
    * @return
    */
  private[firrtl2] def prepare(state: CircuitState): CircuitState = state

  /** Perform the transform and update annotations.
    *
    * @param state Input Firrtl AST
    * @return A transformed Firrtl AST
    */
  final def runTransform(state: CircuitState): CircuitState = {
    val result = execute(prepare(state))
    Transform.remapAnnotations(result, logger)
  }

}

trait SeqTransformBased {
  def transforms: Seq[Transform]
  protected def runTransforms(state: CircuitState): CircuitState =
    transforms.foldLeft(state) { (in, xform) => xform.runTransform(in) }
}

/** For transformations that are simply a sequence of transforms */
abstract class SeqTransform extends Transform with SeqTransformBased {
  def execute(state: CircuitState): CircuitState = {
    /*
    require(state.form <= inputForm,
      s"[$name]: Input form must be lower or equal to $inputForm. Got ${state.form}")
     */
    val ret = runTransforms(state)
    CircuitState(ret.circuit, outputForm, ret.annotations, ret.renames)
  }
}

/** Extend for transforms that require resolved targets in their annotations
  * Ensures all targets in annotations of a class in annotationClasses are resolved before the execute method
  */
trait ResolvedAnnotationPaths {
  this: Transform =>

  val annotationClasses: Traversable[Class[_]]

  override def prepare(state: CircuitState): CircuitState = {
    state.resolvePathsOf(annotationClasses.toSeq: _*)
  }

  // Any transform with this trait invalidates DedupAnnotationsTransform
  override def invalidates(a: Transform) = a.isInstanceOf[DedupAnnotationsTransform]
}

/** Defines old API for Emission. Deprecated */
trait Emitter extends Transform {

  override def invalidates(a: Transform) = false

  @deprecated("Use emission annotations instead", "FIRRTL 1.0")
  def emit(state: CircuitState, writer: Writer): Unit

  /** An output suffix to use if the output of this [[Emitter]] was written to a file */
  def outputSuffix: String
}
