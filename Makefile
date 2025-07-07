# Generate Verilog code
doit:
	sbt run

# Run the test
test:
	cd verilator && make

clean:
	rm -rf generated/
	rm -rf test_run_dir/
	rm -rf target/
	cd verilator && make clean

