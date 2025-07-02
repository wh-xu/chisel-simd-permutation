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
    parameter int DATA_WIDTH = 64,
    parameter int NUM_INOUTS = 32
)
(
   // Declare some signals so we can see how I/O works
   input              clock,
   input              reset,

   input              io_in_valid,
   input              io_sel_idx_val,
   input              io_addr,
   input              [DATA_WIDTH-1:0] io_in_data [NUM_INOUTS-1:0],

   input              io_rotate,
   input              io_out_valid,
   input              io_sel_out,
   output             [DATA_WIDTH-1:0] io_out_data [NUM_INOUTS-1:0]
   );

   // dut
   simd_permutation_network dut(
    .clock(clock),
    .reset(reset),
    .io_in_valid(io_in_valid),
    .io_sel_idx_val(io_sel_idx_val),
    .io_addr(io_addr),
    .io_in_data_0_0(io_in_data[0]),
    .io_in_data_0_1(io_in_data[1]),
    .io_in_data_0_2(io_in_data[2]),
    .io_in_data_0_3(io_in_data[3]),
    .io_in_data_0_4(io_in_data[4]),
    .io_in_data_0_5(io_in_data[5]),
    .io_in_data_0_6(io_in_data[6]),
    .io_in_data_0_7(io_in_data[7]),
    .io_in_data_1_0(io_in_data[8]),
    .io_in_data_1_1(io_in_data[9]),
    .io_in_data_1_2(io_in_data[10]),
    .io_in_data_1_3(io_in_data[11]),
    .io_in_data_1_4(io_in_data[12]),
    .io_in_data_1_5(io_in_data[13]),
    .io_in_data_1_6(io_in_data[14]),
    .io_in_data_1_7(io_in_data[15]),
    .io_in_data_2_0(io_in_data[16]),
    .io_in_data_2_1(io_in_data[17]),
    .io_in_data_2_2(io_in_data[18]),
    .io_in_data_2_3(io_in_data[19]),
    .io_in_data_2_4(io_in_data[20]),
    .io_in_data_2_5(io_in_data[21]),
    .io_in_data_2_6(io_in_data[22]),
    .io_in_data_2_7(io_in_data[23]),
    .io_in_data_3_0(io_in_data[24]),
    .io_in_data_3_1(io_in_data[25]),
    .io_in_data_3_2(io_in_data[26]),
    .io_in_data_3_3(io_in_data[27]),
    .io_in_data_3_4(io_in_data[28]),
    .io_in_data_3_5(io_in_data[29]),
    .io_in_data_3_6(io_in_data[30]),
    .io_in_data_3_7(io_in_data[31]),

    .io_rotate(io_rotate),
    .io_out_valid(io_out_valid),
    .io_sel_out(io_sel_out),
    .io_out_data_0_0(io_out_data[0]),
    .io_out_data_0_1(io_out_data[1]),
    .io_out_data_0_2(io_out_data[2]),
    .io_out_data_0_3(io_out_data[3]),
    .io_out_data_0_4(io_out_data[4]),
    .io_out_data_0_5(io_out_data[5]),
    .io_out_data_0_6(io_out_data[6]),
    .io_out_data_0_7(io_out_data[7]),
    .io_out_data_1_0(io_out_data[8]),
    .io_out_data_1_1(io_out_data[9]),
    .io_out_data_1_2(io_out_data[10]),
    .io_out_data_1_3(io_out_data[11]),
    .io_out_data_1_4(io_out_data[12]),
    .io_out_data_1_5(io_out_data[13]),
    .io_out_data_1_6(io_out_data[14]),
    .io_out_data_1_7(io_out_data[15]),
    .io_out_data_2_0(io_out_data[16]),
    .io_out_data_2_1(io_out_data[17]),
    .io_out_data_2_2(io_out_data[18]),
    .io_out_data_2_3(io_out_data[19]),
    .io_out_data_2_4(io_out_data[20]),
    .io_out_data_2_5(io_out_data[21]),
    .io_out_data_2_6(io_out_data[22]),
    .io_out_data_2_7(io_out_data[23]),
    .io_out_data_3_0(io_out_data[24]),
    .io_out_data_3_1(io_out_data[25]),
    .io_out_data_3_2(io_out_data[26]),
    .io_out_data_3_3(io_out_data[27]),
    .io_out_data_3_4(io_out_data[28]),
    .io_out_data_3_5(io_out_data[29]),
    .io_out_data_3_6(io_out_data[30]),
    .io_out_data_3_7(io_out_data[31])
   );


   // Print some stuff as an example
   initial begin
      if ($test$plusargs("trace") != 0) begin
         $display("[%0t] Tracing to logs/vlt_dump.vcd...\n", $time);
         $dumpfile("logs/vlt_dump.vcd");
         $dumpvars(0, dut);
      end
      $display("[%0t] Model running...\n", $time);
   end

endmodule
