// DESCRIPTION: Verilator: Verilog example module
//
// This file ONLY is placed under the Creative Commons Public Domain, for
// any use, without warranty, 2003 by Wilson Snyder.
// SPDX-License-Identifier: CC0-1.0
// ======================================================================

// This is intended to be a complex example of several features, please also
// see the simpler examples/make_hello_c.

module top
#(
    parameter int XLEN = 64,
    parameter int NumLanes = 8,
    parameter int NumBanks = 8,
    parameter int NumSegments = 8,
    parameter int NumRotationRadix = 4,
    parameter int SizeXbar = 32,
    parameter int UsePipeline = 1,
    parameter int NumInOuts = NumLanes * NumBanks
)
(
   // Declare some signals so we can see how I/O works
   input              clock,
   input              reset,

   input              io_inValid,
   output             io_inReady,
   input              io_selIdxVal,
   input              [XLEN-1:0] io_inData [NumInOuts-1:0],

   input              io_permute,
   input              [2:0] io_mode,

   input              io_outReady,
   output             io_outValid,
   output             [XLEN-1:0] io_outData [NumInOuts-1:0]
   );

   // dut
   SimdPermutation dut(
    .clock(clock),
    .reset(reset),

    .io_inValid(io_inValid),
    .io_inReady(io_inReady),
    .io_selIdxVal(io_selIdxVal),

    .io_inData_0_0(io_inData[0]),
    .io_inData_0_1(io_inData[1]),
    .io_inData_0_2(io_inData[2]),
    .io_inData_0_3(io_inData[3]),
    .io_inData_0_4(io_inData[4]),
    .io_inData_0_5(io_inData[5]),
    .io_inData_0_6(io_inData[6]),
    .io_inData_0_7(io_inData[7]),
    .io_inData_1_0(io_inData[8]),
    .io_inData_1_1(io_inData[9]),
    .io_inData_1_2(io_inData[10]),
    .io_inData_1_3(io_inData[11]),
    .io_inData_1_4(io_inData[12]),
    .io_inData_1_5(io_inData[13]),
    .io_inData_1_6(io_inData[14]),
    .io_inData_1_7(io_inData[15]),
    .io_inData_2_0(io_inData[16]),
    .io_inData_2_1(io_inData[17]),
    .io_inData_2_2(io_inData[18]),
    .io_inData_2_3(io_inData[19]),
    .io_inData_2_4(io_inData[20]),
    .io_inData_2_5(io_inData[21]),
    .io_inData_2_6(io_inData[22]),
    .io_inData_2_7(io_inData[23]),
    .io_inData_3_0(io_inData[24]),
    .io_inData_3_1(io_inData[25]),
    .io_inData_3_2(io_inData[26]),
    .io_inData_3_3(io_inData[27]),
    .io_inData_3_4(io_inData[28]),
    .io_inData_3_5(io_inData[29]),
    .io_inData_3_6(io_inData[30]),
    .io_inData_3_7(io_inData[31]),
    .io_inData_4_0(io_inData[32]),
    .io_inData_4_1(io_inData[33]),
    .io_inData_4_2(io_inData[34]),
    .io_inData_4_3(io_inData[35]),
    .io_inData_4_4(io_inData[36]),
    .io_inData_4_5(io_inData[37]),
    .io_inData_4_6(io_inData[38]),
    .io_inData_4_7(io_inData[39]),
    .io_inData_5_0(io_inData[40]),
    .io_inData_5_1(io_inData[41]),
    .io_inData_5_2(io_inData[42]),
    .io_inData_5_3(io_inData[43]),
    .io_inData_5_4(io_inData[44]),
    .io_inData_5_5(io_inData[45]),
    .io_inData_5_6(io_inData[46]),
    .io_inData_5_7(io_inData[47]),
    .io_inData_6_0(io_inData[48]),
    .io_inData_6_1(io_inData[49]),
    .io_inData_6_2(io_inData[50]),
    .io_inData_6_3(io_inData[51]),
    .io_inData_6_4(io_inData[52]),
    .io_inData_6_5(io_inData[53]),
    .io_inData_6_6(io_inData[54]),
    .io_inData_6_7(io_inData[55]),
    .io_inData_7_0(io_inData[56]),
    .io_inData_7_1(io_inData[57]),
    .io_inData_7_2(io_inData[58]),
    .io_inData_7_3(io_inData[59]),
    .io_inData_7_4(io_inData[60]),
    .io_inData_7_5(io_inData[61]),
    .io_inData_7_6(io_inData[62]),
    .io_inData_7_7(io_inData[63]),

    .io_permute(io_permute),
    .io_mode(io_mode), 
    .io_outValid(io_outValid), 
    .io_outReady(io_outReady), 

    .io_outData_0_0(io_outData[0]),
    .io_outData_0_1(io_outData[1]),
    .io_outData_0_2(io_outData[2]),
    .io_outData_0_3(io_outData[3]),
    .io_outData_0_4(io_outData[4]),
    .io_outData_0_5(io_outData[5]),
    .io_outData_0_6(io_outData[6]),
    .io_outData_0_7(io_outData[7]),
    .io_outData_1_0(io_outData[8]),
    .io_outData_1_1(io_outData[9]),
    .io_outData_1_2(io_outData[10]),
    .io_outData_1_3(io_outData[11]),
    .io_outData_1_4(io_outData[12]),
    .io_outData_1_5(io_outData[13]),
    .io_outData_1_6(io_outData[14]),
    .io_outData_1_7(io_outData[15]),
    .io_outData_2_0(io_outData[16]),
    .io_outData_2_1(io_outData[17]),
    .io_outData_2_2(io_outData[18]),
    .io_outData_2_3(io_outData[19]),
    .io_outData_2_4(io_outData[20]),
    .io_outData_2_5(io_outData[21]),
    .io_outData_2_6(io_outData[22]),
    .io_outData_2_7(io_outData[23]),
    .io_outData_3_0(io_outData[24]),
    .io_outData_3_1(io_outData[25]),
    .io_outData_3_2(io_outData[26]),
    .io_outData_3_3(io_outData[27]),
    .io_outData_3_4(io_outData[28]),
    .io_outData_3_5(io_outData[29]),
    .io_outData_3_6(io_outData[30]),
    .io_outData_3_7(io_outData[31]),
    .io_outData_4_0(io_outData[32]),
    .io_outData_4_1(io_outData[33]),
    .io_outData_4_2(io_outData[34]),
    .io_outData_4_3(io_outData[35]),
    .io_outData_4_4(io_outData[36]),
    .io_outData_4_5(io_outData[37]),
    .io_outData_4_6(io_outData[38]),
    .io_outData_4_7(io_outData[39]),
    .io_outData_5_0(io_outData[40]),
    .io_outData_5_1(io_outData[41]),
    .io_outData_5_2(io_outData[42]),
    .io_outData_5_3(io_outData[43]),
    .io_outData_5_4(io_outData[44]),
    .io_outData_5_5(io_outData[45]),
    .io_outData_5_6(io_outData[46]),
    .io_outData_5_7(io_outData[47]),
    .io_outData_6_0(io_outData[48]),
    .io_outData_6_1(io_outData[49]),
    .io_outData_6_2(io_outData[50]),
    .io_outData_6_3(io_outData[51]),
    .io_outData_6_4(io_outData[52]),
    .io_outData_6_5(io_outData[53]),
    .io_outData_6_6(io_outData[54]),
    .io_outData_6_7(io_outData[55]),
    .io_outData_7_0(io_outData[56]),
    .io_outData_7_1(io_outData[57]),
    .io_outData_7_2(io_outData[58]),
    .io_outData_7_3(io_outData[59]),
    .io_outData_7_4(io_outData[60]),
    .io_outData_7_5(io_outData[61]),
    .io_outData_7_6(io_outData[62]),
    .io_outData_7_7(io_outData[63])
   );


   initial begin
      if ($test$plusargs("trace") != 0) begin
         $display("[%0t] Tracing to logs/vlt_dump.vcd...\n", $time);
         $dumpfile("logs/vlt_dump.vcd");
         $dumpvars(0, dut);
      end
      $display("[%0t] Model running...\n", $time);
   end

endmodule
