// DESCRIPTION: Verilator: Verilog example module
//
// This file ONLY is placed under the Creative Commons Public Domain, for
// any use, without warranty, 2017 by Wilson Snyder.
// SPDX-License-Identifier: CC0-1.0
//======================================================================

// For std::unique_ptr
#include <memory>

// Include common routines
#include <verilated.h>

// Include model header, generated from Verilating "top.v"
#include "Vtop.h"

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

void generate_random_uint16(uint16_t *data, uint16_t range, int num) {
    for (int i = 0; i < num; i++) {
        data[i] = rand() % range;
    }
}

void generate_seq_uint16(uint16_t *data, uint16_t range, int num, bool reverse=false) {
    for (int i = 0; i < num; i++) {
        data[i] = reverse ? (range - 1 - i % range) : (i % range);
    }
}

void bit_pack_uint16_to_uint64(uint16_t *data, uint64_t *packed, int num_packed) {
    for (int i = 0; i < num_packed; i++) {
        packed[i] = 0;
        for(int j = 0; j < 4; j++) {
            packed[i] |= (uint64_t)data[i * 4 + j] << (j * 16);
        }
    }
}

void bit_unpack_uint64_to_uint16(uint64_t *packed, uint16_t *data, int num_packed) {
    for (int i = 0; i < num_packed; i++) {
        for(int j = 0; j < 4; j++) {
            data[i * 4 + j] = (packed[i] >> (j * 16)) & 0xffff;
        }
    }
}

void print_uint16(uint16_t *data, int num) {
    for (int i = 0; i < num; i++) {
        printf("%d\t", data[i]);
    }
    printf("\n");
}

void print_uint64(uint64_t *data, int num) {
    for (int i = 0; i < num; i++) {
        printf("%016lx\t", data[i]);
    }
    printf("\n");
}

bool check_gather_result(uint16_t *gt, uint16_t *out, int num) {
    for(int i = 0; i < num; i++) {
        if(gt[i] != out[i]) {
            return false;
        }
    }
    return true;
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
    const int NUM_INOUTS_16b = 256;
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
    for(int m = 0; m <= 1; m++) {
        generate_random_uint16(idx, 16*(m+1), NUM_INOUTS_16b);
        generate_random_uint16(val, NUM_INOUTS_16b, NUM_INOUTS_16b);

        // generate_seq_uint16(idx, 16*(m+1), NUM_INOUTS_16b, true);
        // generate_seq_uint16(val, NUM_INOUTS_16b, NUM_INOUTS_16b, false);

        bit_pack_uint16_to_uint64(idx, packed_idx_u64, NUM_INOUTS_64b);
        printf("val:\t");
        print_uint16(val, NUM_INOUTS_16b);

        bit_pack_uint16_to_uint64(val, packed_val_u64, NUM_INOUTS_64b);
        printf("idx:\t");
        print_uint16(idx, NUM_INOUTS_16b);

        // Preprocess the index data to avoid out-of-bound access
        for(int i = 0; i < NUM_INOUTS_16b; i++) {
            gt[i] = val[idx[i] + (16*(m+1)*(i/(16*(m+1))))];
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
        top->io_mode = m;
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
            printf("\nm=%d Error: gather result mismatch\n", m);
            // return -1;
        } else{
            printf("\nm=%d Pass!\n", m);
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
