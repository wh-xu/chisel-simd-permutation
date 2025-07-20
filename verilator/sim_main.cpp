/*
 *                             The MIT License
 *
 * Chisel SIMD Permutation Network for Long-vector Architecture
 * Copyright (c) 2025 by Weihong Xu  <weihong.xu@epfl.ch>
 *
 * This file is part of Chisel SIMD Permutation Network for Long-vector Architecture.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 * PROJECT: Chisel SIMD Permutation Network for Long-vector Architecture
 * AUTHOR(S): Weihong Xu <weihong.xu@epfl.ch>
 * DESCRIPTION: main functions for Verilator simulation
 */
 
 // For std::unique_ptr
#include <memory>

// Include common routines
#include <verilated.h>

// Include model header, generated from Verilating "top.v"
#include "Vtop.h"

// Include utility functions for simulation
#include "utils.h"

static Vtop *top;
static VerilatedContext* contextp_global;

void tick() {
    top->clock = !top->clock;
    contextp_global->timeInc(1);  // 1 timeprecision period passes...
    top->eval();
    top->clock = !top->clock;
    contextp_global->timeInc(1);  // 1 timeprecision period passes...
    top->eval();
    // trace->dump(timestamp);
    // timestamp += 500/MHz;
}


int main(int argc, char** argv) {
    // This is a more complicated example, please also see the simpler examples/make_hello_c.

    // Prevent unused variable warnings
    if (false && argc && argv) {}

    // Create logs/ directory in case we have traces to put under it
    Verilated::mkdir("logs");

    // Construct a VerilatedContext to hold simulation time, etc.
    // Multiple modules (made later below with Vtop) may share the same
    // context to share time, or modules may have different contexts if
    // they should be independent from each other.

    // Using unique_ptr is similar to
    // "VerilatedContext* contextp = new VerilatedContext" then deleting at end.
    const std::unique_ptr<VerilatedContext> contextp{new VerilatedContext};
    contextp_global = contextp.get();

    // Do not instead make Vtop as a file-scope static variable, as the
    // "C++ static initialization order fiasco" may cause a crash

    // Set debug level, 0 is off, 9 is highest presently used
    // May be overridden by commandArgs argument parsing
    contextp->debug(0);

    // Randomization reset policy
    // May be overridden by commandArgs argument parsing
    contextp->randReset(2);

    // Verilator must compute traced signals
    contextp->traceEverOn(true);

    // Pass arguments so Verilated code can see them, e.g. $value$plusargs
    // This needs to be called before you create any model
    contextp->commandArgs(argc, argv);

    // Construct the Verilated model, from Vtop.h generated from Verilating "top.v".
    // Using unique_ptr is similar to "Vtop* top = new Vtop" then deleting at end.
    // "TOP" will be the hierarchical name of the module.
    // const std::unique_ptr<Vtop> top{new Vtop{contextp.get(), "TOP"}};
    top = new Vtop{contextp.get(), "TOP"};

    // Generate random data
    const int VLEN = 4096;
    const int NUM_INOUTS_16b = VLEN/16;
    const int MIN_CODEBOOK_SIZE = 4;
    const int NUM_INOUTS_64b = NUM_INOUTS_16b/4;
    uint16_t idx[NUM_INOUTS_16b], val[NUM_INOUTS_16b], gt[NUM_INOUTS_16b], out[NUM_INOUTS_16b];
    uint64_t packed_idx_u64[NUM_INOUTS_64b], packed_val_u64[NUM_INOUTS_64b];

    // Set Vtop's input signals
    top->reset = 1;
    top->clock = 1;
    tick();

    top->reset = 0;
    top->io_mode = 0;
    top->io_inValid = 0;
    top->io_selIdxVal = 0;
    top->io_permute = 0;

    // Simulate until $finish
    for(int cb = 1; cb <= 7; cb++) {
        // generate_random_uint16(idx, CODEBOOK_SIZE*(1<<m), NUM_INOUTS_16b);
        // generate_random_uint16(idx, (1<<15)-1, NUM_INOUTS_16b);
        // generate_random_uint16(val, NUM_INOUTS_16b, NUM_INOUTS_16b);

        generate_seq_uint16(idx, MIN_CODEBOOK_SIZE*(1<<(cb-1)), NUM_INOUTS_16b, true);
        generate_seq_uint16(val, NUM_INOUTS_16b, NUM_INOUTS_16b, false);

        bit_pack_uint16_to_uint64(idx, packed_idx_u64, NUM_INOUTS_64b);
        printf("val:\t");
        print_uint16(val, NUM_INOUTS_16b);

        bit_pack_uint16_to_uint64(val, packed_val_u64, NUM_INOUTS_64b);
        printf("idx:\t");
        print_uint16(idx, NUM_INOUTS_16b);

        // Preprocess the index data to avoid out-of-bound access
        for(int i = 0; i < NUM_INOUTS_16b; i++) {
            gt[i] = val[idx[i] + (MIN_CODEBOOK_SIZE*(1<<(cb-1))*(i/(MIN_CODEBOOK_SIZE*(1<<(cb-1)))))];
        }

        printf("gt:\t");
        print_uint16(gt, NUM_INOUTS_16b);


        // Step 1: load index and val data
        top->io_inValid = 1;
        top->io_selIdxVal = 0;
        for(int i = 0; i < NUM_INOUTS_64b; i++) {
            top->io_inData[i] = packed_idx_u64[i];
        }

        tick();
            
        // Load val data
        top->io_selIdxVal = 1;
        for(int i = 0; i < NUM_INOUTS_64b; i++) {
            top->io_inData[i] = packed_val_u64[i];
        }

        tick();

        // Step 2: read out permuted data
        top->io_inValid = 0;
        top->io_permute = 1;
        top->io_mode = cb;
        top->io_mask_idx_bit = 0xFFFF;
        top->io_rshift_idx_bit = 0;
        top->io_outReady = 1;

        while(!top->io_outValid) {
            tick();
            top->io_permute = 0; // remove to set to low
            // top->io_mode = 0;
        }
        bit_unpack_uint64_to_uint16(top->io_outData, out, NUM_INOUTS_64b);

        tick();

        printf("out:\t");
        print_uint16(out, NUM_INOUTS_16b);
        if(!check_gather_result(gt, out, NUM_INOUTS_16b)) {
            printf("\nCB%d Error: gather result mismatch\n\n", 2<<cb);
            // return -1;
        } else{
            printf("\nCB%d Pass!\n", 2<<cb);
        }


        // Read outputs
        // VL_PRINTF("[%" PRId64 "] clk=%x rst=%x\n", contextp->time(), top->clock, top->reset);

        // Toggle control signals on an edge that doesn't correspond
        // to where the controls are sampled; in this example we do
        // this only on a negedge of clk, because we know
        // reset is not sampled there.
        // if (!top->clock) {
        //     if (contextp->time() > 1 && contextp->time() < 10) {
        //         // top->reset_l = !1;  // Assert reset
        //     } else {
        //         // top->reset_l = !0;  // Deassert reset
        //     }
        // }

        // Evaluate model
        // (If you have multiple models being simulated in the same
        // timestep then instead of eval(), call eval_step() on each, then
        // eval_end_step() on each. See the manual.)
        // top->eval();
    }

    // Final model cleanup
    top->final();

    // Coverage analysis (calling write only after the test is known to pass)
#if VM_COVERAGE
    Verilated::mkdir("logs");
    contextp->coveragep()->write("logs/coverage.dat");
#endif

    delete top;
    // Return good completion status
    // Don't use exit() or destructor won't get called

    return 0;
}
