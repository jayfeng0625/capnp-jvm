# Benchmark Results — Baseline (as-is)

Baseline run of the existing benchmark suite via `./do_benchmarks.bash`, with no
changes to the benchmark or runtime code. This captures the current
`ByteBuffer`-backed runtime as a reference point for later comparison.

## Environment

| Item | Value |
| --- | --- |
| Date | 2026-08-11 |
| CPU | Intel(R) Xeon(R) Processor @ 2.80GHz, 4 vCPUs |
| Memory | 15 GiB |
| JDK | OpenJDK 21.0.10 (64-Bit Server VM) |
| Cap'n Proto | 1.0.1 |
| capnproto-java | 0.1.17-SNAPSHOT |
| Runtime | `ByteBuffer`-backed (current `master`) |

Build steps: `make` (build `capnpc-java`), `mvn compile`, then `./do_benchmarks.bash`.

## What the suite runs

Each of the three test cases (`CarSales`, `CatRank`, `Eval`) is run in five modes:

- **object / none** — build & consume in-process as objects, no serialization.
- **bytes / none** — serialize to / from an in-memory byte buffer, uncompressed.
- **bytes / packed** — same, using Cap'n Proto packed compression.
- **client+server / none** — two JVMs exchanging uncompressed messages over a FIFO pipe.
- **client+server / packed** — same, packed compression.

All runs use `no-reuse` (fresh allocation each iteration). Timings are wall-clock
`real` time from the shell `time` builtin. For the client+server rows the reported
time covers the whole `client | server` pipeline.

## Results

### CarSales — 100,000 iterations

| Mode | real | user | sys |
| --- | --- | --- | --- |
| object · none | 5.929s | 5.639s | 0.738s |
| bytes · none | 7.170s | 7.887s | 0.301s |
| bytes · packed | 16.248s | 17.009s | 0.241s |
| client+server · none | 14.626s | 9.794s | 7.964s |
| client+server · packed | 22.782s | 21.752s | 4.913s |

### CatRank — 10,000 iterations

| Mode | real | user | sys |
| --- | --- | --- | --- |
| object · none | 6.276s | 7.897s | 0.266s |
| bytes · none | 7.556s | 8.653s | 0.601s |
| bytes · packed | 14.308s | 15.881s | 0.275s |
| client+server · none | 10.180s | 10.836s | 2.218s |
| client+server · packed | 18.092s | 23.703s | 2.025s |

### Eval — 2,000,000 iterations

| Mode | real | user | sys |
| --- | --- | --- | --- |
| object · none | 13.183s | 12.917s | 1.414s |
| bytes · none | 13.847s | 14.228s | 0.665s |
| bytes · packed | 30.694s | 31.383s | 0.330s |
| client+server · none | 2m08.163s | 34.445s | 1m32.444s |
| client+server · packed | 2m28.083s | 56.418s | 1m35.936s |

## Notes

- Packed compression roughly doubles the wall time versus uncompressed in every
  case — the extra CPU cost of packing/unpacking dominates.
- The client+server pipeline rows spend a large fraction of their time in `sys`
  (pipe I/O and JVM process overhead across two JVMs). `Eval` is especially
  affected because of its very high iteration count (2M) over a FIFO.
- Numbers include JVM startup and JIT warm-up (each row is a fresh `java`
  invocation); they are indicative, not steady-state throughput measurements.
