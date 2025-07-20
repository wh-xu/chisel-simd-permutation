package simd_perm

import chisel3._
import chisel3.util._

import chisel3.stage.ChiselStage
import chisel3.stage.ChiselGeneratorAnnotation
import javax.xml.crypto.Data


class Crossbar2D(
  val NumNodes: Int, 
  val DataWidth: Int, 
) extends Module {
  val io = IO(new Bundle {
    val inVal     = Input(Vec(NumNodes, UInt(DataWidth.W)))
    val inIdx     = Input(Vec(NumNodes, UInt(log2Ceil(NumNodes).W)))
    val outValid  = Input(Vec(NumNodes, Bool()))
    val outVal    = Output(Vec(NumNodes, UInt(DataWidth.W)))
  })

  val reg_out = RegInit(VecInit.fill(NumNodes)(0.U(DataWidth.W)))
  val lut = Seq.tabulate(NumNodes)(idx =>(idx.U -> io.inVal(idx)))

  for(col <- 0 to NumNodes-1) {
    // Filter out the invalid index
    when(io.outValid(col)) {
      reg_out(col) := MuxLookup(io.inIdx(col), 0.U(log2Ceil(NumNodes).W))(lut)
    } otherwise {
      reg_out(col) := reg_out(col)
    }
  }

  io.outVal := reg_out
}


// Register for storing full RVV vectors
// Each mem bank has XLEN-bit width 
// VLEN = XLEN * NumBanks * NumLanes
class VectorReg(
  val XLEN: Int, 
  val NumLanes: Int, 
  val NumBanks: Int=8, 
  val NumXbar: Int=8, 
  val SizeXbar: Int=32, 
  val DataWidth: Int=16, 
  val EnableRotation: Boolean = true,
  val NumRotationPattern: Int = 4 // number of rotation patterns, 0 for the smallest range
) extends Module {
  val io = IO(new Bundle {
    val inValid     = Input(Bool())
    val inData      = Input(Vec(NumLanes, Vec(NumBanks, UInt(XLEN.W))))
    val rotate      = Input(Bool())
    val rotateLevel = Input(UInt(log2Ceil(NumRotationPattern).W)) // 0: rotate neighbors, 1: rotate by 2
    val outData     = Output(Vec(NumXbar, Vec(SizeXbar, UInt(DataWidth.W))))
  })

  val reg = RegInit(VecInit.fill(NumXbar)(VecInit.fill(SizeXbar)(0.U(DataWidth.W))))
  reg := Mux(io.inValid, io.inData.asTypeOf(Vec(NumXbar, Vec(SizeXbar, UInt(DataWidth.W)))), reg)

  if(EnableRotation) {
    for(radix <- 0 to NumRotationPattern-1) {
      when(io.rotate && io.rotateLevel === radix.U) {
        for(segment <- 0 to (NumXbar/(2<<radix)-1)) {
          for(i <- 0 to (2<<radix)-1) {
            reg((2<<radix)*segment + ((i+1)%(2<<radix))) := reg((2<<radix)*segment+i)
          }
        }
      }
    }
  }

  io.outData := reg
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


// Mask unit for large permutation op
class MaskUnit(
  val NumXbar: Int, 
  val SizeXbar: Int, 
  val IdxWidth: Int,
  val DataWidth: Int = 16,
) extends Module {
  val io = IO(new Bundle {
    val inIdx           = Input(Vec(NumXbar, Vec(SizeXbar, UInt(DataWidth.W))))
    val rotateCnt       = Input(UInt(6.W))
    val mode            = Input(ModePerm())
    val mask_idx_bit    = Input(UInt(DataWidth.W))
    val rshift_idx_bit  = Input(UInt(log2Ceil(DataWidth).W))
    val outValid        = Output(Vec(NumXbar, Vec(SizeXbar, Bool())))
    val outIdx          = Output(Vec(NumXbar, Vec(SizeXbar, UInt(8.W))))
  })

  // Shifted index - properly handle nested Vec structure
  val shifted_idx = VecInit(io.inIdx.map { row =>
    VecInit(row.map { elem =>
      ((elem & io.mask_idx_bit) >> io.rshift_idx_bit)(7, 0)
    })
  })

  // Generate offset mask
  // Currently only support 16-bit data 
  // val chunk_size: Int = 2 << ModePerm.E4.litValue.toInt
  val chunk_size: Int = 4 // chunk_size *2 -> minimum XbarSize
  val num_chunks: Int = SizeXbar / chunk_size
  val mask = VecInit.fill(NumXbar)(VecInit.fill(SizeXbar)(0.U(8.W)))
  //  Flags
  val lt_xBar_PermSize: Bool = SizeXbar.asUInt < (2.U << io.mode.asUInt)
  val eq_xBar_PermSize: Bool = SizeXbar.asUInt === (2.U << io.mode.asUInt)
  val gt_xBar_PermSize: Bool = SizeXbar.asUInt > (2.U << io.mode.asUInt)
  val factor = 2.U << (io.mode.asUInt - log2Ceil(SizeXbar).asUInt)
  for(segment <- 0 to NumXbar-1) {
    for(c <- 0 to num_chunks-1) {
      for(i <- 0 to chunk_size-1) {
        switch(Cat(lt_xBar_PermSize, eq_xBar_PermSize, gt_xBar_PermSize)) {
          is(0b100.U) {
            // Offset mask for SizeXbar < Xbar size
            mask(segment)(c*chunk_size + i) := ((segment.asUInt - io.rotateCnt) % factor) * SizeXbar.U(8.W)
          }
          is(0b010.U) {
            // No need to mask
            mask(segment)(c*chunk_size + i) := 0.U(IdxWidth.W)
          }
          is(0b001.U) {
            // Offset mask for SizeXbar > Xbar size
            mask(segment)(c*chunk_size + i) := (c.U(IdxWidth.W) >> (io.mode.asUInt - 1.U)) * (2.U<<io.mode.asUInt)
          }
        }
      }
    }
  }

  io.outIdx := VecInit(shifted_idx.zip(mask).map { 
    case (idx, msk) => VecInit(idx.zip(msk).map { 
      case (idx_elem, msk_elem) => Mux(lt_xBar_PermSize, idx_elem - msk_elem, idx_elem + msk_elem) 
    })
  })
  io.outValid := VecInit(io.outIdx.map { row =>
    VecInit(row.map { elem => elem < SizeXbar.U(8.W)})
  })
  // io.outIdx := mask
  // io.outValid := VecInit.fill(NumXbar)(VecInit.fill(SizeXbar)(true.B))
}

class SimdPermutation(
  val XLEN: Int=64,  // XLEN of RVV
  val DataWidth: Int=16, // bitwidth of permutation element
  val NumLanes: Int=8, // number of lanes
  val NumBanks: Int=8, // number of banks per lane
  val NumXbar: Int=8, // number of segments
  val SizeXbar: Int=32, // number of crossbars per segment
  val NumRotationPattern: Int=4, // number of rotation patterns, 0 for the smallest range
) extends Module {
  val io = IO(new Bundle {
    val inValid         = Input(Bool())
    val inReady         = Output(Bool())

    val selIdxVal       = Input(Bool())
    val inData          = Input(Vec(NumLanes, Vec(NumBanks, UInt(XLEN.W))))

    val permute         = Input(Bool())
    val mode            = Input(ModePerm())
    val mask_idx_bit    = Input(UInt(DataWidth.W))
    val rshift_idx_bit  = Input(UInt(log2Ceil(DataWidth).W))

    val outValid        = Output(Bool())
    val outReady        = Input(Bool())

    val outData         = Output(Vec(NumLanes, Vec(NumBanks, UInt(XLEN.W))))
  })

  val idxReg = Module(new VectorReg(
    XLEN=XLEN, 
    NumLanes=NumLanes, 
    NumBanks=NumBanks, 
    NumXbar=NumXbar,
    SizeXbar=SizeXbar,
    DataWidth=DataWidth,
    EnableRotation=false,
  ))
  idxReg.io.inValid := (io.inValid && !io.selIdxVal)
  idxReg.io.inData := io.inData
  idxReg.io.rotate := false.B
  idxReg.io.rotateLevel := 0.U

  val rotateCnt = RegInit(0.U(6.W))
  val valRegRotate = WireDefault(false.B)
  val valRegRotateLevel = WireDefault(0.U(log2Ceil(NumRotationPattern).W))
  val valReg = Module(new VectorReg(
    XLEN=XLEN, 
    NumLanes=NumLanes, 
    NumBanks=NumBanks, 
    NumXbar=NumXbar,
    SizeXbar=SizeXbar,
    DataWidth=DataWidth,
    EnableRotation=true,
    NumRotationPattern=NumRotationPattern
  ))
  valReg.io.inValid := (io.inValid && io.selIdxVal)
  valReg.io.inData := io.inData
  valReg.io.rotate := valRegRotate
  valReg.io.rotateLevel := valRegRotateLevel

  // Offset vector to support all cases
  val maskUnit = Module(new MaskUnit(
    NumXbar=NumXbar,
    SizeXbar=SizeXbar,
    IdxWidth=log2Ceil(SizeXbar),
    DataWidth=DataWidth
  ))

  maskUnit.io.inIdx  := idxReg.io.outData
  maskUnit.io.mode  := io.mode
  maskUnit.io.rotateCnt := rotateCnt
  maskUnit.io.mask_idx_bit  := io.mask_idx_bit
  maskUnit.io.rshift_idx_bit  := io.rshift_idx_bit

  val xbars = Seq.fill(NumXbar)(Module(new Crossbar2D(SizeXbar, DataWidth)))
  for(segment <- 0 to NumXbar-1) {
      xbars(segment).io.inVal := valReg.io.outData(segment)
      xbars(segment).io.inIdx := maskUnit.io.outIdx(segment)
      xbars(segment).io.outValid := maskUnit.io.outValid(segment)
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
  val modeReg = RegInit(ModePerm.SEQ)
  val lut_mode = Mux(stateReg === IDLE_LOAD, io.mode, modeReg)

  io.inReady := false.B
  io.outValid := false.B
  valRegRotate := false.B
  valRegRotateLevel := 0.U

  // State machine
  switch(stateReg) {
    is(IDLE_LOAD) {
      io.inReady := true.B
      io.outValid := false.B
      rotateCnt := 0.U
      valRegRotate := false.B
      valRegRotateLevel := 0.U

      when(io.permute) {
        io.inReady := false.B
        modeReg := io.mode

        val lt_xBar_PermSize: Bool = SizeXbar.asUInt < (2.U << io.mode.asUInt)
        val eq_xBar_PermSize: Bool = SizeXbar.asUInt === (2.U << io.mode.asUInt)
        val gt_xBar_PermSize: Bool = SizeXbar.asUInt > (2.U << io.mode.asUInt)
        switch(Cat(lt_xBar_PermSize, eq_xBar_PermSize, gt_xBar_PermSize)) {
          is(0b100.U) {
            // Rotation for SizeXbar < E
            stateReg := PERMUTE
            rotateCnt := rotateCnt + 1.U
            valRegRotate := true.B
            valRegRotateLevel := io.mode.asUInt - log2Ceil(SizeXbar).U
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
      modeReg := modeReg
      valRegRotate := true.B
      valRegRotateLevel := modeReg.asUInt - log2Ceil(SizeXbar).U

      when(rotateCnt < (2.U << (modeReg.asUInt - log2Ceil(SizeXbar).U))-1.U) {
        stateReg := PERMUTE
        // valRegRotate := true.B
      } otherwise {
        stateReg := DONE
        rotateCnt := 0.U
        valRegRotate := false.B
        // idxRegRotateLevel := 0.U
      }
    }
    is(DONE) {
      io.outValid := true.B
      modeReg := ModePerm.SEQ
      valRegRotateLevel := 0.U

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
  val SizeXbar: Int = 16
  val NumRotationPattern: Int = log2Ceil(256/SizeXbar)
  val VL: Int = VLEN/DataWidth
  val NumXbar: Int = VL/SizeXbar

  val dir = "./generated"
  println("Generating the permutation network hardware")
  // new ChiselStage().emitSystemVerilog(new crossbar_2d(permutation_parallelism, data_width, use_pipeline), Array("--target-dir", "generated"))

  // new ChiselStage().emitSystemVerilog(new vector_reg(XLEN=64, NumLanes=4, NumBanks=8, NumSegments=2), Array("--target-dir", "generated"))

  new ChiselStage().emitSystemVerilog(new SimdPermutation(
    XLEN=XLEN, 
    DataWidth=DataWidth, 
    NumLanes=NumLanes, 
    NumBanks=NumBanks, 
    SizeXbar=SizeXbar,
    NumXbar=NumXbar,
    NumRotationPattern=NumRotationPattern,
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
