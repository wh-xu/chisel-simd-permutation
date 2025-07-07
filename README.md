# Chisel-based SIMD Permutation Network for RISC-V Vector Processor

This project provides a Chisel-based implementation of a SIMD permutation network, designed for use in RISC-V vector processors. It includes a configurable 2D crossbar, vector register file, and a permutation network module, serving as a starting point for hardware design and research in vector processing.

## Features
- **Configurable 2D Crossbar:** Parameterized by number of nodes and data width, with optional pipelining.
- **Vector Register File:** Supports segmented storage, rotation, and multi-lane/bank organization.
- **SIMD Permutation Network:** Integrates crossbars and vector registers for flexible data permutation.
- **Chisel3-based:** Easily extensible and synthesizable to Verilog/SystemVerilog.

## Prerequisites
- [sbt (Scala Build Tool)](https://www.scala-sbt.org/)
- [Chisel 3](https://www.chisel-lang.org/)
- Java JDK 8+

## Getting Started

### Clone the Repository
```bash
git clone <repo-url>
cd chisel-simd-permutation
```

### Build and Generate Verilog
To generate Verilog code for the permutation network:
```bash
make
```
This runs the Chisel generator and outputs Verilog files to the `generated/` directory.

### Run Tests
To run the Verilator testbench:
```bash
make test
```

### Clean Build Artifacts
To clean up generated files and build artifacts:
```bash
make clean
```
