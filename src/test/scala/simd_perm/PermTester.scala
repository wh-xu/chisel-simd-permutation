/*
 * Dummy tester to start a Chisel project.
 *
 * Author: Martin Schoeberl (martin@jopdesign.com)
 * 
 */

package simd_perm

import chisel3._
import chisel3.util._

import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class PermTester() {
  val random = new scala.util.Random(42)

  def gen_random_seq(range: Int, len: Int, width: Int): Seq[UInt] = {
    Seq.fill(len)(random.nextInt(range).U(width.W))
  }
}

class TestPermutationNetwork extends AnyFlatSpec with ChiselScalatestTester {

  val t_ = new PermTester()

  "VectorReg" should "work" in {
    val XLEN = 64
    val NumBanks = 8
    val NumLanes = 8
    val NumSegments = 8
    val EnableRotation = true
    val NumRotationRadix = 4

    test(new VectorReg(XLEN, NumLanes, NumBanks, NumSegments, EnableRotation, NumRotationRadix)) { dut =>
      for(i <- 0 until 1) {
        val in_data_seq = t_.gen_random_seq(123, NumLanes*NumBanks, 16)
        val in_addr_seq = t_.gen_random_seq(NumSegments, NumSegments, log2Ceil(NumSegments))

        dut.io.inValid.poke(true.B)
        for(lane <- 0 until NumLanes) {
          for(bank <- 0 until NumBanks) {
            dut.io.inData(lane)(bank).poke(in_data_seq(lane*NumBanks + bank))
          }
        }

        dut.clock.step(1)

        println(s"in_data_seq:\n${in_data_seq}")
        println(s"out:\n${dut.io.outData.peek()}")

        dut.io.inValid.poke(false.B)
        dut.io.rotate.poke(true.B)
        dut.io.rotateLevel.poke(0.U)

        dut.clock.step(1)

        val rout_0 = dut.io.outData.peek()
        println(s"rotated out-0:\n${rout_0}")

        dut.clock.step(1)

        val rout_0_original = dut.io.outData.peek()
        println(s"original out:\n${rout_0_original}")

        dut.io.rotate.poke(true.B)
        dut.io.rotateLevel.poke(1.U)

        dut.clock.step(1)

        val rout_1_1 = dut.io.outData.peek()
        println(s"rotated out-1-1:\n${rout_1_1}")

        dut.clock.step(1)

        val rout_1_2 = dut.io.outData.peek()
        println(s"rotated out-1-2:\n${rout_1_2}")
      }
    }
  }


  "Crossbar2D" should "work" in {
    val num_nodes = 8
    val data_width = 16
    test(new Crossbar2D(num_nodes, data_width)) { dut =>
      
      for(i <- 0 until 5) {
        // Generate random permutation indices (0 to num_nodes-1)
        val in_val_seq = t_.gen_random_seq(1234, num_nodes, data_width)
        val in_idx_seq = t_.gen_random_seq(num_nodes, num_nodes, log2Ceil(num_nodes))

        // println(s"in_val: ${in_val_seq}")
        // println(s"in_idx: ${in_idx_seq}")

        // Apply inputs to DUT
        for (row <- 0 until num_nodes) {
          dut.io.inVal(row).poke(in_val_seq(row))
          dut.io.inIdx(row).poke(in_idx_seq(row))
        }
      
        dut.clock.step(1)
      
        // Verify outputs based on permutation
        for (col <- 0 until num_nodes) {
          var expected_val = in_val_seq(in_idx_seq(col).litValue.toInt)
          dut.io.outVal(col).expect(expected_val)
          // println(s"out @ $col: ${dut.io.out(col).peek().litValue.toInt}")
          // println(s"gt @ $col: ${in_val_seq(in_idx_seq(col).litValue.toInt).litValue.toString}")
        }
      }
    }
  }
}

