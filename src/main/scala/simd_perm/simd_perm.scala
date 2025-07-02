package simd_perm

import chisel3._
import chisel3.util._

import chisel3.stage.ChiselStage
import chisel3.stage.ChiselGeneratorAnnotation

class crossbar_2d(val NumNodes: Int, val DataWidth: Int, val Pipeline: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val in_val = Input(Vec(NumNodes, UInt(DataWidth.W)))
    val in_idx = Input(Vec(NumNodes, UInt(log2Ceil(NumNodes).W)))
    val out = Output(Vec(NumNodes, UInt(DataWidth.W)))
  })

  val lut = Seq.tabulate(NumNodes)(idx =>(idx.U -> io.in_val(idx)))

  for(col <- 0 to NumNodes-1) {
    if(Pipeline) {
      val reg_out = RegInit(0.U(DataWidth.W))
      reg_out := MuxLookup(io.in_idx(col), 0.U(DataWidth.W))(lut)
      io.out(col) := reg_out
    } else {
      io.out(col) := MuxLookup(io.in_idx(col), 0.U(DataWidth.W))(lut)
    }
  }
}


// Register for storing full RVV vectors
// Each mem bank has XLEN-bit width 
// VLEN = XLEN * NumBanks * NumLanes * NumSegments
// VLEN = DataWidthSegment * NumLanes * NumSegments
// 4096 = 64 * 8 * 4 * 2
class vector_reg(
  val XLEN: Int, 
  val NumLanes: Int, 
  val NumBanks: Int, 
  val NumSegments: Int, 
  val EnableRotation: Boolean = true
  ) extends Module {
  val io = IO(new Bundle {
    val valid = Input(Bool())
    val in_data = Input(Vec(NumLanes, Vec(NumBanks, UInt(XLEN.W))))
    val in_addr = Input(UInt(log2Ceil(NumSegments).W))
    val rotate = Input(Bool())
    val out_data = Output(Vec(NumSegments, Vec(NumLanes, Vec(NumBanks, UInt((XLEN).W)))))
  })
  val reg = RegInit(VecInit.fill(NumSegments)(VecInit.fill(NumLanes)(VecInit.fill(NumBanks)(0.U((XLEN.W))))))

  reg(io.in_addr) := Mux(io.valid, io.in_data, reg(io.in_addr))

  if(EnableRotation) {
    when(io.rotate){
      for(segment <- 0 to NumSegments-1) {
        if(segment == 0) {
          reg(0) := reg(NumSegments-1)
        } else {
          reg(segment) := reg(segment-1)
        }
      }
    }
  }

  for(segment <- 0 to NumSegments-1) {
    io.out_data(segment) := reg(segment)
  }
}


// class lane_io(val XLEN: Int, val NumBanks: Int) extends Bundle {
//   val valid = Bool()
//   val sel_idx_val = Bool()
//   val in_data = UInt((XLEN*NumBanks).W)
//   val in_addr = UInt(log2Ceil(NumSegments).W)
//   val rotate = Bool()
// }

class simd_permutation_network(
  val XLEN: Int=64, 
  val NumLanes: Int=4, 
  val NumBanks: Int=8, 
  val NumSegments: Int=2, 

  val DataWidth: Int=16,
  val IdxWidth: Int=5,

  val Use_pipeline: Boolean=true
  ) extends Module {
  val io = IO(new Bundle {
    val in_valid = Input(Bool())
    val sel_idx_val = Input(Bool())
    val in_data = Input(Vec(NumLanes, Vec(NumBanks, UInt(XLEN.W))))
    val addr = Input(UInt(log2Ceil(NumSegments).W))
    val rotate = Input(Bool())

    val out_valid = Input(Bool())
    val sel_out = Input(Bool())
    val out_data = Output(Vec(NumLanes, Vec(NumBanks, UInt(XLEN.W))))
  })

  val SizeXbar: Int = math.pow(2, IdxWidth).toInt
  val NumXbar: Int = (XLEN*NumBanks*NumLanes*NumSegments)/(DataWidth*SizeXbar)
  
  val reg_index = Module(new vector_reg(XLEN, NumLanes, NumBanks, NumSegments, EnableRotation=true))
  reg_index.io.valid := (io.in_valid && !io.sel_idx_val)
  reg_index.io.in_data := io.in_data
  reg_index.io.in_addr := io.addr
  reg_index.io.rotate := io.rotate

  val reg_value = Module(new vector_reg(XLEN, NumLanes, NumBanks, NumSegments, EnableRotation=false))
  reg_value.io.valid := (io.in_valid && io.sel_idx_val)
  reg_value.io.in_data := io.in_data
  reg_value.io.in_addr := io.addr
  reg_value.io.rotate := false.B

  val xbars = Seq.fill(NumXbar)(Module(new crossbar_2d(SizeXbar, DataWidth, Use_pipeline)))

  // Num_xbar must be larger than NumSegments
  val xbar_per_segment: Int = (NumXbar/NumSegments)
  for(segment <- 0 to NumSegments-1) {
    for(l <- 0 to NumLanes-1) {
      xbars(segment*xbar_per_segment+l).io.in_val := reg_value.io.out_data(segment)(l).asTypeOf(Vec(SizeXbar, UInt(DataWidth.W)))
      xbars(segment*xbar_per_segment+l).io.in_idx := reg_index.io.out_data(segment)(l).asTypeOf(Vec(SizeXbar, UInt(DataWidth.W)))
    }
  }

  // Reshape the xbars output
  val xbars_vec_out = VecInit(xbars.map(_.io.out)).asTypeOf(Vec(NumSegments, Vec(NumLanes, Vec(NumBanks, UInt(XLEN.W)))))
  println(s"xbars_vec_out: ${xbars_vec_out}")

  io.out_data := Mux(io.out_valid && io.sel_out, 
    MuxLookup(io.addr, 0.U.asTypeOf(Vec(NumLanes, Vec(NumBanks, UInt(XLEN.W)))))(
      Seq.tabulate(NumSegments)(idx =>(idx.U -> xbars_vec_out(idx)))
    ),
    0.U.asTypeOf(Vec(NumLanes, Vec(NumBanks, UInt(XLEN.W))))
  )
}


import scala.sys.process._
import java.io.{BufferedWriter, FileWriter}

object Main extends App {
  println("Generating the permutation network hardware")
  val permutation_parallelism: Int = 32
  val data_width: Int = 16
  val use_pipeline: Boolean = false
  val dir = "./generated"

  // new ChiselStage().emitSystemVerilog(new crossbar_2d(permutation_parallelism, data_width, use_pipeline), Array("--target-dir", "generated"))

  // new ChiselStage().emitSystemVerilog(new vector_reg(XLEN=64, NumLanes=4, NumBanks=8, NumSegments=2), Array("--target-dir", "generated"))

  new ChiselStage().emitSystemVerilog(new simd_permutation_network(XLEN=64, NumLanes=4, NumBanks=8, NumSegments=2), Array("--target-dir", "generated"))


  // emitVerilog(new crossbar_2d(32, 16, true), Array("--target-dir", "generated", "--addString", "pipelined"))

  // Seq("mkdir", dir).!
  // Seq("mkdir", "-p", dir + "/src").!

  // val writer = new BufferedWriter(new FileWriter(dir + "/crossbar_2d_pipelined.sv"))
  // try {
  //   val code = (new ChiselStage()).emitSystemVerilog(new crossbar_2d(permutation_parallelism, data_width, use_pipeline))
  // } finally {
  //   writer.close()
  // }
}
