package simd_perm

import chisel3._
import chisel3.util._

import chisel3.stage.ChiselStage
import chisel3.stage.ChiselGeneratorAnnotation
import javax.xml.crypto.Data


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


// Supported LUT mode definition
object ModePerm extends ChiselEnum {
    val SEQ   = Value(0x0.U(3.W)) // sequential
    val E4    = Value(0x1.U(3.W)) // 4-ptr     
    val E8    = Value(0x2.U(3.W)) // 8-ptr     
    val E16   = Value(0x3.U(3.W)) // 16-ptr    
    val E32   = Value(0x4.U(3.W)) // 32-ptr    
    val E64   = Value(0x5.U(3.W)) // 64-ptr    
    val E128  = Value(0x6.U(3.W)) // 128-ptr   
    val E256  = Value(0x7.U(3.W)) // 256-ptr   
    // val SEQ, E16, E32, E64, E128, E256 = Value
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
    val mask_idx_bit = Input(UInt(DataWidth.W))
    val rshift_idx_bit = Input(UInt(log2Ceil(DataWidth).W))

    val outValid = Output(Bool())
    val outReady = Input(Bool())

    val outData = Output(Vec(NumLanes, Vec(NumBanks, UInt(XLEN.W))))
  })

  // Offset vec generation for xbar input index when SizeXbar > E
  def get_offset_vec(SizeXbar: Int, mode: ModePerm.Type, DataWidth: Int = 16): Vec[UInt] = {
    // Currently only support 16-bit data width and 32/64-ptr xbar
    val IdxWidth: Int = log2Ceil(SizeXbar)
    val chunk_size: Int = 2 << ModePerm.E4.litValue.toInt
    val num_chunks: Int = SizeXbar / chunk_size
    // Offset vec size: [SizeXbar, IdxWidth]
    val mask: Vec[UInt] = VecInit.fill(SizeXbar)(0.U(IdxWidth.W))

    // Generate bit offset mask
    for(c <- 0 to num_chunks-1) {
      for(i <- 0 to chunk_size-1) {
        // 0 1 2 3 4 5 6 7 8 -> 0 4 8 12 16 20 24 28 32
        // 0-1 2-3 4-5 6-7 -> 0 8 16 24
        // 0-1-2-3 4-5-6-7 -> 0 16 
        mask(c*chunk_size + i) := (c.U(IdxWidth.W) >> (mode.asUInt - 1.U)) * (2.U<<mode.asUInt)
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
  val offsetVec = WireDefault(VecInit.fill(SizeXbar)(0.U(DataWidth.W)))

  val xbars = Seq.fill(NumSegments)(Module(new Crossbar2D(SizeXbar, DataWidth, UsePipeline)))
  // val idx_bit_mask = Seq.fill(SizeXbar)(io.bit_mask_idx.U(DataWidth.W))
  for(segment <- 0 to NumSegments-1) {
      xbars(segment).io.inVal := valReg.io.outData(segment).asTypeOf(Vec(SizeXbar, UInt(DataWidth.W)))
      xbars(segment).io.inIdx := idxReg.io.outData(segment).asTypeOf(Vec(SizeXbar, UInt(DataWidth.W))).zip(offsetVec).map { case (a, b) => (((a & io.mask_idx_bit) >> io.rshift_idx_bit) + b) }
  }

  // Reshape the xbars output
  val xbarsVecOut = VecInit(xbars.map(_.io.outVal)).asTypeOf(Vec(NumLanes, Vec(NumBanks, UInt(XLEN.W))))

  // TODO: mask the val out to support case SizeXbar < E
  // val mask = VecInit.fill(NumLanes)(VecInit.fill(NumBanks)(0.U(DataWidth.W)))
  // val masked_xbarsVecOut = xbarsVecOut.zip(mask).map { case (a, b) => a & b }
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
  val modeReg = RegInit(ModePerm.SEQ)
  val lut_mode = Mux(stateReg === IDLE_LOAD, io.mode, modeReg)

  io.inReady := false.B
  io.outValid := false.B
  idxRegRotateLevel := rotateCnt
  offsetVec := get_offset_vec(SizeXbar, lut_mode, DataWidth)

  // State machine
  switch(stateReg) {
    is(IDLE_LOAD) {
      io.inReady := true.B
      io.outValid := false.B
      rotateCnt := 0.U

      when(io.permute) {
        io.inReady := false.B
        modeReg := io.mode

        val lt_xBar_PermSize: Bool = SizeXbar.asUInt < (8.U << io.mode.asUInt)
        val eq_xBar_PermSize: Bool = SizeXbar.asUInt === (8.U << io.mode.asUInt)
        val gt_xBar_PermSize: Bool = SizeXbar.asUInt > (8.U << io.mode.asUInt)
        switch(Cat(lt_xBar_PermSize, eq_xBar_PermSize, gt_xBar_PermSize)) {
          is(0b100.U) {
            // Rotation for SizeXbar < E
            stateReg := PERMUTE
            rotateCnt := rotateCnt + 1.U
          }
          is(0b010.U) {
            // Do nothing for SizeXbar == E
            stateReg := DONE
          }
          is(0b001.U) {
            // Offset for SizeXbar > E
            stateReg := DONE
            
          }
        }
      }
    }
    is(PERMUTE) {
      io.inReady := false.B
      rotateCnt := rotateCnt + 1.U
      // idxRegRotateLevel := rotateCnt
      modeReg := modeReg

      val _rotate_lv = 3.U+modeReg.asUInt-log2Ceil(SizeXbar).U
      when(rotateCnt < (1.U((NumRotationRadix+1).W) << _rotate_lv)-1.U) {
        stateReg := PERMUTE
        idxRegRotate := true.B
      } otherwise {
        stateReg := DONE
        rotateCnt := 0.U
        idxRegRotate := false.B
        // idxRegRotateLevel := 0.U
      }
    }
    is(DONE) {
      io.outValid := true.B
      modeReg := ModePerm.SEQ

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
