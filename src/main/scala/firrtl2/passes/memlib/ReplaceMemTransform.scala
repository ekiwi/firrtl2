// SPDX-License-Identifier: Apache-2.0

package firrtl2.passes
package memlib

import firrtl2.Utils.error
import firrtl2._
import firrtl2.annotations._
import firrtl2.options.{CustomFileEmission, Dependency, HasShellOptions, ShellOption}
import firrtl2.passes.wiring._
import firrtl2.stage.{Forms, RunFirrtlTransformAnnotation}

import java.io.{CharArrayWriter, PrintWriter}

sealed trait PassOption
case object OutputConfigFileName extends PassOption
case object PassCircuitName extends PassOption
case object PassModuleName extends PassOption

object PassConfigUtil {
  type PassOptionMap = Map[PassOption, String]

  def getPassOptions(t: String, usage: String = "") = {
    // can't use space to delimit sub arguments (otherwise, Driver.scala will throw error)
    val passArgList = t.split(":").toList

    def nextPassOption(map: PassOptionMap, list: List[String]): PassOptionMap = {
      list match {
        case Nil => map
        case "-o" :: value :: tail =>
          nextPassOption(map + (OutputConfigFileName -> value), tail)
        case "-c" :: value :: tail =>
          nextPassOption(map + (PassCircuitName -> value), tail)
        case "-m" :: value :: tail =>
          nextPassOption(map + (PassModuleName -> value), tail)
        case option :: tail =>
          error("Unknown option " + option + usage)
      }
    }
    nextPassOption(Map[PassOption, String](), passArgList)
  }
}

case class ReplSeqMemAnnotation(outputConfig: String) extends NoTargetAnnotation

case class GenVerilogMemBehaviorModelAnno(genBlackBox: Boolean) extends NoTargetAnnotation

/** Generate conf file for a sequence of [[DefAnnotatedMemory]]
  * @note file already has its suffix adding by `--replSeqMem`
  */
case class MemLibOutConfigFileAnnotation(file: String, annotatedMemories: Seq[DefAnnotatedMemory])
    extends NoTargetAnnotation
    with CustomFileEmission {
  def baseFileName(annotations: AnnotationSeq) = file
  def suffix = None
  def getBytes = annotatedMemories.map { m =>
    require(bitWidth(m.dataType) <= Int.MaxValue)
    m.maskGran.foreach(x => require(x <= Int.MaxValue))
    MemConf(
      m.name,
      m.depth,
      bitWidth(m.dataType).toInt,
      m.readers.length,
      m.writers.length,
      m.readwriters.length,
      m.maskGran.map(_.toInt)
    ).toString
  }.mkString.getBytes
}

private[memlib] case class AnnotatedMemoriesAnnotation(annotatedMemories: List[DefAnnotatedMemory])
    extends NoTargetAnnotation

object ReplSeqMemAnnotation {
  def parse(t: String): ReplSeqMemAnnotation = {
    val usage = """
[Optional] ReplSeqMem
  Pass to replace sequential memories with blackboxes + configuration file

Usage:
  --replSeqMem -c:<circuit>:-o:<filename>
  *** Note: sub-arguments to --replSeqMem should be delimited by : and not white space!

Required Arguments:
  -o<filename>         Specify the output configuration file
  -c<circuit>          Specify the target circuit
"""

    val passOptions = PassConfigUtil.getPassOptions(t, usage)
    val outputConfig = passOptions.getOrElse(
      OutputConfigFileName,
      error("No output config file provided for ReplSeqMem!" + usage)
    )
    ReplSeqMemAnnotation(outputConfig)
  }
}

private class SimpleTransform(p: Pass) extends Transform {
  def execute(state: CircuitState): CircuitState = CircuitState(p.run(state.circuit), state.annotations)
}

// SimpleRun instead of PassBased because of the arguments to passSeq
class ReplSeqMem extends SeqTransform with HasShellOptions {

  override def prerequisites = Forms.MidForm
  override def optionalPrerequisites = Seq.empty
  override def optionalPrerequisiteOf = Forms.MidEmitters
  override def invalidates(a: Transform) = a match {
    case InferTypes | ResolveKinds | ResolveFlows | LowerTypes => true
    case _                                                     => false
  }

  val options = Seq(
    new ShellOption[String](
      longOption = "repl-seq-mem",
      toAnnotationSeq =
        (a: String) => Seq(passes.memlib.ReplSeqMemAnnotation.parse(a), RunFirrtlTransformAnnotation(new ReplSeqMem)),
      helpText = "Blackbox and emit a configuration file for each sequential memory",
      shortOption = Some("frsq"),
      helpValueName = Some("-c:<circuit>:-o:<file>")
    ),
    new ShellOption[String](
      longOption = "gen-mem-verilog",
      toAnnotationSeq = (a: String) =>
        Seq(
          a match {
            case "blackbox" => GenVerilogMemBehaviorModelAnno(genBlackBox = true)
            case _          => GenVerilogMemBehaviorModelAnno(genBlackBox = false)
          },
          RunFirrtlTransformAnnotation(new ReplSeqMem)
        ),
      helpText = "Blackbox and emit a Verilog behavior model for each sequential memory",
      shortOption = Some("gmv"),
      helpValueName = Some("<blackbox|full>")
    )
  )

  val transforms: Seq[Transform] =
    Seq(
      new SimpleTransform(LegalizeConnectsOnly),
      new SimpleTransform(ToMemIR),
      new SimpleTransform(ResolveMaskGranularity),
      new SimpleTransform(RenameAnnotatedMemoryPorts),
      new CreateMemoryAnnotations,
      new ResolveMemoryReference,
      new ReplaceMemMacros,
      new WiringTransform,
      new DumpMemoryAnnotations
    )
}
