// SPDX-License-Identifier: Apache-2.0

package firrtlTests

import firrtl2._
import firrtl2.passes._
import firrtl2.transforms._
import firrtl2.annotations._
import firrtl2.stage.{FirrtlStage, Forms}
import firrtl2.testutils._
import firrtl2.util.BackendCompilationUtilities.createTestDirectory

import java.io.File
import java.nio.file.Paths

case class AnnotationWithDontTouches(target: ReferenceTarget)
    extends SingleTargetAnnotation[ReferenceTarget]
    with HasDontTouches {
  def targets = Seq(target)
  def duplicate(n: ReferenceTarget) = this.copy(n)
  def dontTouches: Seq[ReferenceTarget] = targets
}

class DCETests extends LowFirrtlTransformSpec(Forms.LowFormOptimized) {

  "Unread wire" should "be deleted" in {
    val input =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    wire a : UInt<1>
        |    z <= x
        |    a <= x""".stripMargin
    val check =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    z <= x""".stripMargin
    execute(input, check)
  }
  "Unread wire marked dont touch" should "NOT be deleted" in {
    val input =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    wire a : UInt<1>
        |    z <= x
        |    a <= x""".stripMargin
    val check =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    node a = x
        |    z <= x""".stripMargin
    execute(input, check, Seq(dontTouch("Top.a")))
  }
  "Unread wire marked dont touch by another annotation" should "NOT be deleted" in {
    val input =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    wire a : UInt<1>
        |    z <= x
        |    a <= x""".stripMargin
    val check =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    node a = x
        |    z <= x""".stripMargin
    execute(input, check, Seq(AnnotationWithDontTouches(ModuleTarget("Top", "Top").ref("a"))))
  }
  "Unread register" should "be deleted" in {
    val input =
      """circuit Top :
        |  module Top :
        |    input clk : Clock
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    reg a : UInt<1>, clk
        |    a <= x
        |    node y = asUInt(clk)
        |    z <= or(x, y)""".stripMargin
    val check =
      """circuit Top :
        |  module Top :
        |    input clk : Clock
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    node y = asUInt(clk)
        |    z <= or(x, y)""".stripMargin
    execute(input, check)
  }
  "Unread node" should "be deleted" in {
    val input =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    node a = not(x)
        |    z <= x""".stripMargin
    val check =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    z <= x""".stripMargin
    execute(input, check)
  }
  "Unused ports" should "be deleted" in {
    val input =
      """circuit Top :
        |  module Sub :
        |    input x : UInt<1>
        |    input y : UInt<1>
        |    output z : UInt<1>
        |    x is invalid
        |    y is invalid
        |    z <= x
        |  module Top :
        |    input x : UInt<1>
        |    input y : UInt<1>
        |    output z : UInt<1>
        |    inst sub of Sub
        |    sub.x <= x
        |    sub.y is invalid
        |    z <= sub.z""".stripMargin
    val check =
      """circuit Top :
        |  module Sub :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    z <= x
        |  module Top :
        |    input x : UInt<1>
        |    input y : UInt<1>
        |    output z : UInt<1>
        |    inst sub of Sub
        |    sub.x <= x
        |    z <= sub.z""".stripMargin
    execute(input, check, unordered = true)
  }
  "Chain of unread nodes" should "be deleted" in {
    val input =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    node a = not(x)
        |    node b = or(a, a)
        |    node c = add(b, x)
        |    z <= x""".stripMargin
    val check =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    z <= x""".stripMargin
    execute(input, check)
  }
  "Chain of unread wires and their connections" should "be deleted" in {
    val input =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    wire a : UInt<1>
        |    a <= x
        |    wire b : UInt<1>
        |    b <= a
        |    z <= x""".stripMargin
    val check =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    z <= x""".stripMargin
    execute(input, check)
  }
  "Read register" should "not be deleted" in {
    val input =
      """circuit Top :
        |  module Top :
        |    input clk : Clock
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    reg r : UInt<1>, clk
        |    r <= x
        |    z <= r""".stripMargin
    val check =
      """circuit Top :
        |  module Top :
        |    input clk : Clock
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    reg r : UInt<1>, clk with : (reset => (UInt<1>("h0"), r))
        |    r <= x
        |    z <= r""".stripMargin
    execute(input, check, unordered = true)
  }
  "Logic that feeds into simulation constructs" should "not be deleted" in {
    val input =
      """circuit Top :
        |  module Top :
        |    input clk : Clock
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    node a = not(x)
        |    stop(clk, a, 0)
        |    z <= x""".stripMargin
    val check =
      """circuit Top :
        |  module Top :
        |    input clk : Clock
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    node a = not(x)
        |    z <= x
        |    stop(clk, a, 0)""".stripMargin
    execute(input, check)
  }
  "Globally dead module" should "should be deleted" in {
    val input =
      """circuit Top :
        |  module Dead :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    z <= x
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    inst dead of Dead
        |    dead.x <= x
        |    z <= x""".stripMargin
    val check =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    z <= x""".stripMargin
    execute(input, check)
  }
  "Globally dead extmodule" should "NOT be deleted by default" in {
    val input =
      """circuit Top :
        |  extmodule Dead :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    inst dead of Dead
        |    dead.x <= x
        |    z <= x""".stripMargin
    val check =
      """circuit Top :
        |  extmodule Dead :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    inst dead of Dead
        |    dead.x <= x
        |    z <= x""".stripMargin
    execute(input, check, unordered = true)
  }
  "Extmodule with only inputs" should "NOT be deleted by default" in {
    val input =
      """circuit Top :
        |  extmodule InputsOnly :
        |    input x : UInt<1>
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    inst ext of InputsOnly
        |    ext.x <= x
        |    z <= x""".stripMargin
    val check =
      """circuit Top :
        |  extmodule InputsOnly :
        |    input x : UInt<1>
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    inst ext of InputsOnly
        |    ext.x <= x
        |    z <= x""".stripMargin
    execute(input, check, unordered = true)
  }
  "Globally dead extmodule marked optimizable" should "be deleted" in {
    val input =
      """circuit Top :
        |  extmodule Dead :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    inst dead of Dead
        |    dead.x <= x
        |    z <= x""".stripMargin
    val check =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    z <= x""".stripMargin
    val doTouchAnno = OptimizableExtModuleAnnotation(ModuleName("Dead", CircuitName("Top")))
    execute(input, check, Seq(doTouchAnno))
  }
  "Analog ports of extmodules" should "count as both inputs and outputs" in {
    val input =
      """circuit Top :
        |  extmodule BB1 :
        |    output bus : Analog<1>
        |  extmodule BB2 :
        |    output bus : Analog<1>
        |    output out : UInt<1>
        |  module Top :
        |    output out : UInt<1>
        |    inst bb1 of BB1
        |    inst bb2 of BB2
        |    attach (bb1.bus, bb2.bus)
        |    out <= bb2.out
        """.stripMargin
    execute(input, input, unordered = true)
  }
  "extmodules with no ports" should "NOT be deleted by default" in {
    val input =
      """circuit Top :
        |  extmodule BlackBox :
        |    defname = BlackBox
        |  module Top :
        |    input x : UInt<1>
        |    output y : UInt<1>
        |    inst blackBox of BlackBox
        |    y <= x
        |""".stripMargin
    execute(input, input)
  }
  "extmodules with no ports marked optimizable" should "be deleted" in {
    val input =
      """circuit Top :
        |  extmodule BlackBox :
        |    defname = BlackBox
        |  module Top :
        |    input x : UInt<1>
        |    output y : UInt<1>
        |    inst blackBox of BlackBox
        |    y <= x
        |""".stripMargin
    val check =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output y : UInt<1>
        |    y <= x
        |""".stripMargin
    val doTouchAnno = OptimizableExtModuleAnnotation(ModuleName("BlackBox", CircuitName("Top")))
    execute(input, check, Seq(doTouchAnno))
  }
  // bar.z is not used and thus is dead code, but foo.z is used so this code isn't eliminated
  "Module deduplication" should "should be preserved despite unused output of ONE instance" in {
    val input =
      """circuit Top :
        |  module Child :
        |    input x : UInt<1>
        |    output y : UInt<1>
        |    output z : UInt<1>
        |    y <= not(x)
        |    z <= x
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    inst foo of Child
        |    inst bar of Child
        |    foo.x <= x
        |    bar.x <= x
        |    node t0 = or(foo.y, foo.z)
        |    z <= or(t0, bar.y)""".stripMargin
    val check =
      """circuit Top :
        |  module Child :
        |    input x : UInt<1>
        |    output y : UInt<1>
        |    output z : UInt<1>
        |    y <= not(x)
        |    z <= x
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    inst foo of Child
        |    inst bar of Child
        |    foo.x <= x
        |    bar.x <= x
        |    node t0 = or(foo.y, foo.z)
        |    z <= or(t0, bar.y)""".stripMargin
    execute(input, check, unordered = true)
  }
  // This currently does NOT work
  behavior.of("Single dead instances")
  ignore should "should be deleted" in {
    val input =
      """circuit Top :
        |  module Child :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    z <= x
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    inst foo of Child
        |    inst bar of Child
        |    foo.x <= x
        |    bar.x <= x
        |    z <= foo.z""".stripMargin
    val check =
      """circuit Top :
        |  module Child :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    z <= x
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    inst foo of Child
        |    skip
        |    foo.x <= x
        |    skip
        |    z <= foo.z""".stripMargin
    execute(input, check)
  }

  "Emitted Verilog" should "not contain dead \"register update\" code" in {
    val input = parse(
      """circuit test :
        |  module test :
        |    input clock : Clock
        |    input a : UInt<1>
        |    input x : UInt<8>
        |    output z : UInt<8>
        |    reg r : UInt, clock
        |    when a :
        |      r <= x
        |    z <= r""".stripMargin
    )

    val state = CircuitState(input, Seq(MakeCompiler.makeEmitVerilogCircuitAnno))
    val result = MakeCompiler.makeVerilogCompiler().transform(state)
    val verilog = result.getEmittedCircuit.value
    // Check that mux is removed!
    (verilog shouldNot include).regex("""a \? x : r;""")
    // Check for register update
    (verilog should include).regex("""(?m)if \(a\) begin\n\s*r <= x;\s*end""")
  }

  "Emitted Verilog" should "not contain dead print or stop statements" in {
    val input = parse(
      """circuit test :
        |  module test :
        |    input clock : Clock
        |    when UInt<1>(0) :
        |      printf(clock, UInt<1>(1), "o hai")
        |      stop(clock, UInt<1>(1), 1)""".stripMargin
    )

    val state = CircuitState(input, Seq(MakeCompiler.makeEmitVerilogCircuitAnno))
    val result = MakeCompiler.makeVerilogCompiler().transform(state)
    val verilog = result.getEmittedCircuit.value
    (verilog shouldNot include).regex("""fwrite""")
    (verilog shouldNot include).regex("""fatal""")
  }

  "DCE" should "not duplicate unnecessarily" in {
    val input =
      """circuit Top :
        |  module child :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    z <= not(x)
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    inst c of child
        |    inst c_1 of child
        |    c.x <= x
        |    c_1.x <= x
        |    z <= and(c.z, c_1.z)""".stripMargin
    val check =
      """circuit Top :
        |  module child :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    z <= not(x)
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    inst c of child
        |    inst c_1 of child
        |    z <= and(c.z, c_1.z)
        |    c.x <= x
        |    c_1.x <= x""".stripMargin
    val top = CircuitTarget("Top").module("Top")
    val annos =
      Seq(top.instOf("c", "child").ref("z"), top.instOf("c_1", "child").ref("z"))
        .map(DontTouchAnnotation(_))
    execute(input, check, annos)
  }
}

class DCECommandLineSpec extends FirrtlFlatSpec {

  val testDir = createTestDirectory("dce")
  val inputFile = Paths.get(getClass.getResource("/features/HasDeadCode.fir").toURI()).toFile()
  val outFile = new File(testDir, "HasDeadCode.v")
  val args = Array("-i", inputFile.getAbsolutePath, "-o", outFile.getAbsolutePath, "-X", "verilog")

  "Dead Code Elimination" should "run by default" in {
    val verilog =
      try {
        (new FirrtlStage)
          .execute(args, Seq())
          .collectFirst { case EmittedVerilogCircuitAnnotation(value) => value }
          .get
          .value
      } catch { case _: Throwable => fail("Unexpected compilation failure") }
    (verilog should not).include(regex("wire +a"))
  }

  it should "not run when given --no-dce option" in {
    val verilog =
      try {
        (new FirrtlStage)
          .execute(args :+ "--no-dce", Seq())
          .collectFirst { case EmittedVerilogCircuitAnnotation(value) => value }
          .get
          .value
      } catch { case _: Throwable => fail("Unexpected compilation failure") }
    (verilog should include).regex("wire +a")
  }
}
