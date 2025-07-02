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
    const int NUM_INOUTS = 256;
    const int NUM_SEGS = 2;
    uint16_t idx[NUM_INOUTS], val[NUM_INOUTS], gt[NUM_INOUTS], out[NUM_INOUTS];
    uint64_t packed_idx_u64[NUM_INOUTS / 4], packed_val_u64[NUM_INOUTS / 4];

    generate_random_uint16(idx, 32, NUM_INOUTS);
    generate_random_uint16(val, 100, NUM_INOUTS);

    // generate_seq_uint16(idx, 32, NUM_INOUTS, true);
    // generate_seq_uint16(val, NUM_INOUTS, NUM_INOUTS, false);

    bit_pack_uint16_to_uint64(idx, packed_idx_u64, NUM_INOUTS/4);
    printf("val:\t");
    print_uint16(val, NUM_INOUTS);

    bit_pack_uint16_to_uint64(val, packed_val_u64, NUM_INOUTS/4);
    printf("idx:\t");
    print_uint16(idx, NUM_INOUTS);

    // Preprocess the index data to avoid out-of-bound access
    for(int i = 0; i < NUM_INOUTS; i++) {
        gt[i] = val[idx[i] + (32*(i/32))];
    }

    printf("gt:\t");
    print_uint16(gt, NUM_INOUTS);

    // Set Vtop's input signals
    top->reset = 0;
    top->clock = 1;
    top->io_sel_out = 0;

    // Simulate until $finish
    int cycles = 1;
    int cnt = 0;
    // while (!contextp->gotFinish()) {
    while (cycles-->0) {
        // Step 1: load index and val data
        for(int seg = 0; seg < NUM_SEGS; seg++) {
            top->io_addr = seg;
            top->io_in_valid = 1;

            top->io_sel_idx_val = 0;
            for(int i = 0; i < NUM_INOUTS/4/2; i++) {
                top->io_in_data[i] = packed_idx_u64[seg*NUM_INOUTS/4/2 + i];
            }

            tick();
            
            // Load val data
            top->io_sel_idx_val = 1;
            for(int i = 0; i < NUM_INOUTS/4/2; i++) {
                top->io_in_data[i] = packed_val_u64[seg*NUM_INOUTS/4/2 + i];
            }

            tick();
        }

        // Step 2: read out permuted data
        for(int seg = 0; seg < NUM_SEGS; seg++) {
            top->io_in_valid = 0;
            top->io_out_valid = 1;
            top->io_sel_out = 1;
            top->io_addr = seg;
            top->io_rotate = 0;

            tick();

            bit_unpack_uint64_to_uint16(top->io_out_data, out+seg*NUM_INOUTS/2, NUM_INOUTS/4/2);

        }

        printf("out:\t");
        print_uint16(out, NUM_INOUTS);
        if(!check_gather_result(gt, out, NUM_INOUTS)) {
            printf("Error: gather result mismatch\n");
            return -1;
        } else{
            printf("\nPass!\n");
        }

        // print_uint64(top->io_out_data, NUM_INOUTS/4/2);


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
    // delete contextp;
    // Return good completion status
    // Don't use exit() or destructor won't get called
    return 0;
}
