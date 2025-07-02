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

  "vector_reg" should "work" in {
    val XLEN = 64
    val NumBanks = 8
    val NumLanes = 4
    val NumSegments = 2

    test(new vector_reg(XLEN, NumLanes, NumBanks, NumSegments)) { dut =>
      for(i <- 0 until 1) {
        val in_data_seq = t_.gen_random_seq(123, NumLanes*NumBanks, 16)
        val in_addr_seq = t_.gen_random_seq(NumSegments, NumSegments, log2Ceil(NumSegments))

        dut.io.valid.poke(true.B)
        dut.io.in_addr.poke(in_addr_seq(1))
        for(lane <- 0 until NumLanes) {
          for(bank <- 0 until NumBanks) {
            dut.io.in_data(lane)(bank).poke(in_data_seq(lane*NumBanks + bank))
          }
        }

        dut.clock.step(1)

        dut.io.valid.poke(false.B)

        println(s"in_data_seq: ${in_data_seq}")
        println(s"out: ${dut.io.out_data.peek()}")

        dut.clock.step(1)

        dut.io.valid.poke(true.B)
        dut.io.rotate.poke(true.B)

        dut.clock.step(1)

        println(s"rotated out: ${dut.io.out_data.peek()}")

        dut.io.valid.poke(true.B)
        dut.io.rotate.poke(true.B)

        dut.clock.step(1)

        println(s"rotated out 2: ${dut.io.out_data.peek()}")
      }
    }
  }


  "crossbar_2d" should "work" in {
    val num_nodes = 8
    val data_width = 16
    test(new crossbar_2d(num_nodes, data_width)) { dut =>
      
      for(i <- 0 until 5) {
        // Generate random permutation indices (0 to num_nodes-1)
        val in_val_seq = t_.gen_random_seq(1234, num_nodes, data_width)
        val in_idx_seq = t_.gen_random_seq(num_nodes, num_nodes, log2Ceil(num_nodes))

        // println(s"in_val: ${in_val_seq}")
        // println(s"in_idx: ${in_idx_seq}")

        // Apply inputs to DUT
        for (row <- 0 until num_nodes) {
          dut.io.in_val(row).poke(in_val_seq(row))
          dut.io.in_idx(row).poke(in_idx_seq(row))
        }
      
        dut.clock.step(1)
      
        // Verify outputs based on permutation
        for (col <- 0 until num_nodes) {
          var expected_val = in_val_seq(in_idx_seq(col).litValue.toInt)
          dut.io.out(col).expect(expected_val)
          // println(s"out @ $col: ${dut.io.out(col).peek().litValue.toInt}")
          // println(s"gt @ $col: ${in_val_seq(in_idx_seq(col).litValue.toInt).litValue.toString}")
        }
      }
    }
  }


  "simd_permutation_network" should "work" in {
    val XLEN = 64
    val NumLanes = 4
    val NumBanks = 8
    val NumSegments = 2

    test(new simd_permutation_network(XLEN, NumLanes, NumBanks, NumSegments)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      for(i <- 0 until 1) {
        // val in_addr_seq = t_.gen_random_seq(NumSegments, NumSegments, log2Ceil(NumSegments))

        // Step 1: load val and idx data
        for(s <- 0 until NumSegments) {
          // Load index data - generate random data for each lane
          // val in_idx_data = t_.gen_random_seq(32, NumLanes, XLEN*NumBanks)//.grouped(NumBanks).toSeq
          
          dut.io.in_valid.poke(true.B)
          dut.io.sel_idx_val.poke(0.B)
          dut.io.addr.poke(s.U)
          dut.io.rotate.poke(false.B)

          for(l <- 0 until NumLanes) {
            for(b <- 0 until NumBanks) {
              dut.io.in_data(l)(b).poke(l*NumBanks + b)
            }
          }

          dut.clock.step(1)

          // Step 2: Load value data
          // val in_val_data = t_.gen_random_seq(99, NumLanes*NumBanks, XLEN).grouped(NumBanks).toSeq
          
          dut.io.in_valid.poke(true.B)
          dut.io.sel_idx_val.poke(1.B)
          dut.io.addr.poke(s.U)
          dut.io.rotate.poke(false.B)

          for(l <- 0 until NumLanes) {
            for(b <- 0 until NumBanks) {
              dut.io.in_data(l)(b).poke(l*NumBanks + b)
            }
          }

          dut.clock.step(1)
        }

        // Step 2: test output
        for(s <- 0 until NumSegments) {
          dut.io.out_valid.poke(true.B)
          dut.io.sel_out.poke(true.B)
          dut.io.addr.poke(s.U)

          dut.clock.step(1)
          println(s"Output data: ${dut.io.out_data.peek()}")
        }

      }
    }
  }
}

