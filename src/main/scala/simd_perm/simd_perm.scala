package simd_perm

import chisel3._
import chisel3.util._

import chisel3.stage.ChiselStage
import chisel3.stage.ChiselGeneratorAnnotation

object ModePerm extends ChiselEnum {
    val E16   = Value(0x0.U) // 16-ptr    -> 000
    val E32   = Value(0x1.U) // 32-ptr    -> 001
    val E64   = Value(0x2.U) // 64-ptr    -> 010
    val E128  = Value(0x3.U) // 128-ptr   -> 011
    val E256  = Value(0x4.U) // 256-ptr   -> 100
}

class Crossbar2D(
  val NumNodes: Int, 
  val DataWidth: Int, 
  val UsePipeline: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val inVal = Input(Vec(NumNodes, UInt(DataWidth.W)))
    val inIdx = Input(Vec(NumNodes, UInt(log2Ceil(NumNodes).W)))
    val outVal = Output(Vec(NumNodes, UInt(DataWidth.W)))
  })

  val lut = Seq.tabulate(NumNodes)(idx =>(idx.U -> io.inVal(idx)))

  for(col <- 0 to NumNodes-1) {
    if(UsePipeline) {
      val reg_out = RegInit(0.U(DataWidth.W))
      reg_out := MuxLookup(io.inIdx(col), 0.U(DataWidth.W))(lut)
      io.outVal(col) := reg_out
    } else {
      io.outVal(col) := MuxLookup(io.inIdx(col), 0.U(DataWidth.W))(lut)
    }
  }
}


// Register for storing full RVV vectors
// Each mem bank has XLEN-bit width 
// VLEN = XLEN * NumBanks * NumLanes * NumSegments
// VLEN = DataWidthSegment * NumLanes * NumSegments
// 4096 = 64 * 8 * 4 * 2
class VectorReg(
  val XLEN: Int, 
  val NumLanes: Int, 
  val NumBanks: Int=8, 
  val NumSegments: Int=8, // number of segments for minimum S*16b reg
  val EnableRotation: Boolean = true,
  val NumRotationRadix: Int = 4 // number of rotation radix
) extends Module {
  val io = IO(new Bundle {
    val inValid = Input(Bool())
    val inData = Input(Vec(NumLanes, Vec(NumBanks, UInt(XLEN.W))))
    val rotate = Input(Bool())
    val rotateLevel = Input(UInt(log2Ceil(NumRotationRadix).W)) // 0: rotate neighbors, 1: rotate by 2
    val outData = Output(Vec(NumSegments, Vec(NumBanks, UInt((XLEN).W))))
  })
  val reg = RegInit(VecInit.fill(NumLanes)(VecInit.fill(NumBanks)(0.U((XLEN.W)))))
  reg := Mux(io.inValid, io.inData, reg)

  if(EnableRotation) {
    when(io.rotate){
      for(radix <- 0 to NumRotationRadix-1) {
        when(io.rotateLevel === radix.U) {
          for(segment <- 0 to (NumSegments/(2<<radix)-1)) {
            for(i <- 0 to (2<<radix)-1) {
              reg((2<<radix)*segment + ((i+1)%(2<<radix))) := reg((2<<radix)*segment+i)
            }
          }
        }
      }
    }
  }

  io.outData := reg.asTypeOf(Vec(NumSegments, Vec(NumBanks, UInt((XLEN.W)))))
}


// class lane_io(val XLEN: Int, val NumBanks: Int) extends Bundle {
//   val valid = Bool()
//   val sel_idx_val = Bool()
//   val in_data = UInt((XLEN*NumBanks).W)
//   val in_addr = UInt(log2Ceil(NumSegments).W)
//   val rotate = Bool()
// }


class SimdPermutation(
  val XLEN: Int=64,  // XLEN of RVV
  val DataWidth: Int=16, // bitwidth of permutation element
  val NumLanes: Int=8, // number of lanes
  val NumBanks: Int=8, // number of banks per lane
  val NumSegments: Int=8, // number of segments
  val NumRotationRadix: Int=4, // number of rotation radix
  val SizeXbar: Int=32, // number of crossbars per segment
  val UsePipeline: Boolean=true
) extends Module {
  val io = IO(new Bundle {
    val inValid = Input(Bool())
    val inReady = Output(Bool())

    val selIdxVal = Input(Bool())
    val inData = Input(Vec(NumLanes, Vec(NumBanks, UInt(XLEN.W))))

    val permute = Input(Bool())
    val mode = Input(ModePerm())

    val outValid = Output(Bool())
    val outReady = Input(Bool())

    val outData = Output(Vec(NumLanes, Vec(NumBanks, UInt(XLEN.W))))
  })

  val IdxWidth: Int=log2Ceil(SizeXbar)
  
  val idxReg = Module(new VectorReg(
    XLEN, NumLanes, NumBanks, NumSegments, EnableRotation=true, NumRotationRadix))
  idxReg.io.inValid := (io.inValid && !io.selIdxVal)
  idxReg.io.inData := io.inData
  idxReg.io.rotate := false.B
  idxReg.io.rotateLevel := 0.U

  val valRegRotate = WireDefault(false.B)
  val valRegRotateLevel = WireDefault(0.U(log2Ceil(NumRotationRadix).W))
  val valReg = Module(new VectorReg(
    XLEN, NumLanes, NumBanks, NumSegments, EnableRotation=false, NumRotationRadix))
  valReg.io.inValid := (io.inValid && io.selIdxVal)
  valReg.io.inData := io.inData
  valReg.io.rotate := valRegRotate
  valReg.io.rotateLevel := valRegRotateLevel

  val xbars = Seq.fill(NumSegments)(Module(new Crossbar2D(SizeXbar, DataWidth, UsePipeline)))

  for(segment <- 0 to NumSegments-1) {
      xbars(segment).io.inVal := valReg.io.outData(segment).asTypeOf(Vec(SizeXbar, UInt(DataWidth.W)))
      xbars(segment).io.inIdx := idxReg.io.outData(segment).asTypeOf(Vec(SizeXbar, UInt(DataWidth.W)))
      // TODO: add mode to xbars
      // TODO: offset idx based on mode or do this in software
  }

  // Reshape the xbars output
  val xbarsVecOut = VecInit(xbars.map(_.io.outVal)).asTypeOf(Vec(NumLanes, Vec(NumBanks, UInt(XLEN.W))))
  println(s"xbars_vec_out: ${xbarsVecOut}")

  io.outData := Mux(
    io.outValid && io.outReady, 
    xbarsVecOut,
    0.U.asTypeOf(Vec(NumLanes, Vec(NumBanks, UInt(XLEN.W))))
  )

  // Control logic
  object State extends ChiselEnum {
    val IDLE_LOAD, PERMUTE, DONE = Value
  }
  import State._

  val stateReg = RegInit(IDLE_LOAD)
  val rotateCnt = RegInit(0.U(NumRotationRadix.W))

  io.inReady := false.B
  io.outValid := false.B

  switch(stateReg) {
    is(IDLE_LOAD) {
      io.inReady := true.B
      io.outValid := false.B
      rotateCnt := 0.U
      when(io.permute) {
        val lt_xBar_PermSize: Bool = SizeXbar.asUInt < (16.U << io.mode.asUInt)
        val eq_xBar_PermSize: Bool = SizeXbar.asUInt === (16.U << io.mode.asUInt)
        val gt_xBar_PermSize: Bool = SizeXbar.asUInt > (16.U << io.mode.asUInt)
        switch(Cat(lt_xBar_PermSize, eq_xBar_PermSize, gt_xBar_PermSize)) {
          // Rotation for SizeXbar < E
          is(0b100.U) {
            stateReg := PERMUTE
          }
          // Do nothing for SizeXbar == E
          is(0b010.U) {
            stateReg := DONE
          }
          // Offset for SizeXbar > E
          is(0b001.U) {
            stateReg := DONE
            // TODO: Add offset logic
          }
        }
      }
    }
    is(PERMUTE) {
      io.inReady := false.B
      rotateCnt := rotateCnt + 1.U

      when(rotateCnt < ((2.U << (4.U+io.mode.asUInt-log2Ceil(SizeXbar).U))-1.U)) {
        stateReg := PERMUTE
        valRegRotate := true.B
        valRegRotateLevel := io.mode.asUInt + 3.U - log2Ceil(SizeXbar).U
      } otherwise {
        stateReg := DONE
        rotateCnt := 0.U
        valRegRotate := false.B
        valRegRotateLevel := 0.U
      }
    }
    is(DONE) {
      io.outValid := true.B
      when(io.outReady) {
        stateReg := IDLE_LOAD
      }
    } 
  }
}


import scala.sys.process._
import java.io.{BufferedWriter, FileWriter}

object Main extends App {
  val XLEN: Int = 64
  val DataWidth: Int = 16

  val VLEN: Int = 4096
  val NumLanes: Int = 8
  val NumBanks: Int = 8
  val NumSegments: Int = 8
  val NumRotationRadix: Int = 4
  val SizeXbar: Int = 32
  val UsePipeline: Boolean = true

  val dir = "./generated"
  println("Generating the permutation network hardware")
  // new ChiselStage().emitSystemVerilog(new crossbar_2d(permutation_parallelism, data_width, use_pipeline), Array("--target-dir", "generated"))

  // new ChiselStage().emitSystemVerilog(new vector_reg(XLEN=64, NumLanes=4, NumBanks=8, NumSegments=2), Array("--target-dir", "generated"))

  new ChiselStage().emitSystemVerilog(new SimdPermutation(
    XLEN=XLEN, 
    DataWidth=DataWidth, 
    NumLanes=NumLanes, 
    NumBanks=NumBanks, 
    NumSegments=NumSegments,
    NumRotationRadix=NumRotationRadix,
    SizeXbar=SizeXbar,
    UsePipeline=UsePipeline
  ), Array("--target-dir", "generated"))


  // Seq("mkdir", dir).!
  // Seq("mkdir", "-p", dir + "/src").!

  // val writer = new BufferedWriter(new FileWriter(dir + "/crossbar_2d_pipelined.sv"))
  // try {
  //   val code = (new ChiselStage()).emitSystemVerilog(new crossbar_2d(permutation_parallelism, data_width, use_pipeline))
  // } finally {
  //   writer.close()
  // }
}
