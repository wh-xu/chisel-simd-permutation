package simd_perm

import chisel3._
import chisel3.util._

import chisel3.stage.ChiselStage
import chisel3.stage.ChiselGeneratorAnnotation


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
    for(radix <- 0 to NumRotationRadix-1) {
      when(io.rotate && io.rotateLevel === radix.U) {
        for(segment <- 0 to (NumSegments/(2<<radix)-1)) {
          for(i <- 0 to (2<<radix)-1) {
            reg((2<<radix)*segment + ((i+1)%(2<<radix))) := reg((2<<radix)*segment+i)
          }
        }
      }
    }
  }

  io.outData := reg.asTypeOf(Vec(NumSegments, Vec(NumBanks, UInt((XLEN.W)))))
}


object ModePerm extends ChiselEnum {
    // val E16   = Value(0x0.U) // 16-ptr    -> 000
    // val E32   = Value(0x1.U) // 32-ptr    -> 001
    // val E64   = Value(0x2.U) // 64-ptr    -> 010
    // val E128  = Value(0x3.U) // 128-ptr   -> 011
    // val E256  = Value(0x4.U) // 256-ptr   -> 100
    val E16, E32, E64, E128, E256 = Value
}


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

  // Offset logic for xbar input index when SizeXbar > E
  def get_offset_vec(SizeXbar: Int, mode: ModePerm.Type, DataWidth: Int = 16): Vec[UInt] = {
    // Currently only support 16-bit data width and 32/64-ptr xbar
    val num_segs: Int = SizeXbar / 16 // 2 or 4
    val seg_size: Int = SizeXbar / num_segs
    val mask: Vec[UInt] = VecInit.fill(SizeXbar)(0.U(DataWidth.W))

    // Generate bit offset mask
    for(s <- 0 to num_segs-1) {
      for(i <- 0 to seg_size-1) {
        when(s.asUInt < mode.asUInt+1.U) { 
          mask(s*seg_size + i) := 0.U(DataWidth.W)
        } otherwise {
          mask(s*seg_size + i) := (1.U(DataWidth.W) << (3.U(3.W)+s.asUInt+mode.asUInt))
        }
      }
    }
    mask
  }

  val idxRegRotate = WireDefault(false.B)
  val idxRegRotateLevel = WireDefault(0.U(log2Ceil(NumRotationRadix).W))
  val idxReg = Module(new VectorReg(
    XLEN=XLEN, 
    NumLanes=NumLanes, 
    NumBanks=NumBanks, 
    NumSegments=NumSegments,
    EnableRotation=true,
    NumRotationRadix=NumRotationRadix
  ))
  idxReg.io.inValid := (io.inValid && !io.selIdxVal)
  idxReg.io.inData := io.inData
  idxReg.io.rotate := idxRegRotate
  idxReg.io.rotateLevel := idxRegRotateLevel

  val valReg = Module(new VectorReg(
    XLEN=XLEN, 
    NumLanes=NumLanes, 
    NumBanks=NumBanks, 
    NumSegments=NumSegments,
    EnableRotation=false,
  ))
  valReg.io.inValid := (io.inValid && io.selIdxVal)
  valReg.io.inData := io.inData
  valReg.io.rotate := false.B
  valReg.io.rotateLevel := 0.U

  // Offset vector to support case SizeXbar > E
  // 32-ptr xBar needs 
  val offsetVec = VecInit.fill(SizeXbar)(0.U(DataWidth.W))

  val xbars = Seq.fill(NumSegments)(Module(new Crossbar2D(SizeXbar, DataWidth, UsePipeline)))
  for(segment <- 0 to NumSegments-1) {
      xbars(segment).io.inVal := valReg.io.outData(segment).asTypeOf(Vec(SizeXbar, UInt(DataWidth.W)))
      xbars(segment).io.inIdx := idxReg.io.outData(segment).asTypeOf(Vec(SizeXbar, UInt(DataWidth.W))).zip(offsetVec).map { case (a, b) => a | b }
  }

  // Reshape the xbars output
  val xbarsVecOut = VecInit(xbars.map(_.io.outVal)).asTypeOf(Vec(NumLanes, Vec(NumBanks, UInt(XLEN.W))))

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
  val modeReg = Mux(io.permute, io.mode, ModePerm.E16)

  io.inReady := false.B
  io.outValid := false.B

  switch(stateReg) {
    is(IDLE_LOAD) {
      io.inReady := true.B
      io.outValid := false.B
      rotateCnt := 0.U
      // offsetVec := VecInit.fill(SizeXbar)(0.U(DataWidth.W))
      when(io.permute) {
        val lt_xBar_PermSize: Bool = SizeXbar.asUInt < (16.U << io.mode.asUInt)
        val eq_xBar_PermSize: Bool = SizeXbar.asUInt === (16.U << io.mode.asUInt)
        val gt_xBar_PermSize: Bool = SizeXbar.asUInt > (16.U << io.mode.asUInt)
        switch(Cat(lt_xBar_PermSize, eq_xBar_PermSize, gt_xBar_PermSize)) {
          is(0b100.U) {
            // Rotation for SizeXbar < E
            stateReg := PERMUTE
          }
          is(0b010.U) {
            // Do nothing for SizeXbar == E
            stateReg := DONE
          }
          is(0b001.U) {
            // Offset for SizeXbar > E
            stateReg := DONE
            offsetVec := get_offset_vec(SizeXbar, modeReg, DataWidth)
          }
        }
      }
    }
    is(PERMUTE) {
      io.inReady := false.B
      rotateCnt := rotateCnt + 1.U

      when(rotateCnt < (1.U << (3.U+modeReg.asUInt-log2Ceil(SizeXbar).U))) {
        stateReg := PERMUTE
        idxRegRotate := true.B
        idxRegRotateLevel := modeReg.asUInt + 3.U - log2Ceil(SizeXbar).U
      } otherwise {
        stateReg := DONE
        rotateCnt := 0.U
        idxRegRotate := false.B
        idxRegRotateLevel := 0.U
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


  // new ChiselStage().emitSystemVerilog(new VectorReg(
  //   XLEN=XLEN, 
  //   NumLanes=NumLanes, 
  //   NumBanks=NumBanks, 
  //   NumSegments=NumSegments,
  //   EnableRotation=true,
  //   NumRotationRadix=NumRotationRadix
  // ), Array("--target-dir", "generated"))


  // Seq("mkdir", dir).!
  // Seq("mkdir", "-p", dir + "/src").!

  // val writer = new BufferedWriter(new FileWriter(dir + "/crossbar_2d_pipelined.sv"))
  // try {
  //   val code = (new ChiselStage()).emitSystemVerilog(new crossbar_2d(permutation_parallelism, data_width, use_pipeline))
  // } finally {
  //   writer.close()
  // }
}
