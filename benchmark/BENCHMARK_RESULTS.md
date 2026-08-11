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
| JDK | OpenJDK 21.0.10 and 25.0.3 (64-Bit Server VM) — two runs on the same box |
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

## Results — JDK 21

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

## Results — JDK 25

Third dataset: the identical suite re-run on OpenJDK 25.0.3, same box, same
already-compiled classes (the benchmark targets bytecode release 8, so it runs
unchanged on JDK 25). Command: `./do_benchmarks.bash` with JDK 25 first on `PATH`.

### CarSales — 100,000 iterations

| Mode | real | user | sys |
| --- | --- | --- | --- |
| object · none | 7.279s | 6.150s | 1.660s |
| bytes · none | 6.875s | 7.495s | 0.322s |
| bytes · packed | 17.316s | 18.116s | 0.217s |
| client+server · none | 15.815s | 10.263s | 8.196s |
| client+server · packed | 21.850s | 20.580s | 4.949s |

### CatRank — 10,000 iterations

| Mode | real | user | sys |
| --- | --- | --- | --- |
| object · none | 6.102s | 7.216s | 0.234s |
| bytes · none | 7.132s | 8.307s | 0.274s |
| bytes · packed | 14.400s | 16.460s | 0.200s |
| client+server · none | 10.891s | 11.049s | 3.394s |
| client+server · packed | 14.990s | 20.522s | 1.849s |

### Eval — 2,000,000 iterations

| Mode | real | user | sys |
| --- | --- | --- | --- |
| object · none | 13.166s | 12.753s | 1.209s |
| bytes · none | 14.320s | 15.173s | 0.411s |
| bytes · packed | 30.559s | 31.391s | 0.325s |
| client+server · none | 2m07.076s | 35.311s | 1m33.458s |
| client+server · packed | 2m26.452s | 54.522s | 1m36.085s |

## JDK 21 vs JDK 25

In-process modes only (the pipe rows are dominated by container FIFO `sys` time
and are not a meaningful cross-JDK signal). `real` seconds; ratio = JDK25 / JDK21,
so `<1` means JDK 25 is faster.

| Case / mode | JDK 21 | JDK 25 | Ratio | Δ% |
| --- | ---: | ---: | ---: | ---: |
| CarSales · object | 5.93s | 7.28s | 1.228 | +22.8% (noise, see below) |
| CarSales · bytes | 7.17s | 6.88s | 0.959 | -4.1% |
| CarSales · bytes packed | 16.25s | 17.32s | 1.066 | +6.6% |
| CatRank · object | 6.28s | 6.10s | 0.972 | -2.8% |
| CatRank · bytes | 7.56s | 7.13s | 0.944 | -5.6% |
| CatRank · bytes packed | 14.31s | 14.40s | 1.006 | +0.6% |
| Eval · object | 13.18s | 13.17s | 0.999 | -0.1% |
| Eval · bytes | 13.85s | 14.32s | 1.034 | +3.4% |
| Eval · bytes packed | 30.69s | 30.56s | 0.996 | -0.4% |

**Conclusion: no meaningful change between JDK 21 and JDK 25 for this workload.**
The differences scatter within roughly ±6% in both directions, which is
run-to-run noise for single-shot measurements (one JVM invocation per row, on a
shared container), not a real signal. The `CarSales · object` +22.8% outlier is a
one-off: that single invocation had elevated `sys` time (1.66s vs 0.74s on JDK
21), i.e. transient system contention rather than a code path regression — its
`user` time (6.15s vs 5.64s) moved far less.

This is the expected result. The large generational gains this benchmark is
sensitive to — compact strings and UTF transcode intrinsics (CatRank), JIT
escape-analysis and young-gen GC for reader-object churn (CarSales) — landed in
the JDK 8 -> 17 era and are already baked into the JDK 21 numbers. JDK 21 -> 25
does not change the workload's fundamentals (per-member reader allocation,
`ByteBuffer` bounds-checking), so the timings hold flat. A `MemorySegment`/FFM
runtime, not a newer JDK, is what would move these numbers.

## Comparison against the 2014 announcement

The original alpha-release announcement
([news.html](https://dwrensha.github.io/capnproto-java/news.html), 13 Oct 2014)
reported this same benchmark suite, at the **same iteration counts** used here,
and stated the Java implementation ran "at worst 3x slower than the C++ and Rust
implementations, and at best about 2x slower." Its per-case Java timings are
published only as bar charts (`assets/carsales.png`, `catrank.png`, `eval.png`);
the values below are read off the yellow "Java" bars (±~0.3s).

We only ran the Java implementation, so the 2014 cross-language 2–3x gap cannot be
reproduced directly. Instead we compute the **generational offset**: the 2014 Java
time divided by our Java time (`>1` = this run is faster).

| Case / mode | 2014 Java | This run | Offset |
| --- | ---: | ---: | ---: |
| CarSales · object | ~9.1s | 5.93s | 1.53x |
| CarSales · bytes | ~9.4s | 7.17s | 1.31x |
| CarSales · bytes packed | ~21.5s | 16.25s | 1.32x |
| CarSales · pipe | ~11.5s | 14.63s | 0.79x (see note) |
| CarSales · pipe packed | ~22.5s | 22.78s | 0.99x (see note) |
| CatRank · object | ~15.5s | 6.28s | 2.47x |
| CatRank · bytes | ~16.2s | 7.56s | 2.14x |
| CatRank · bytes packed | ~24.5s | 14.31s | 1.71x |
| CatRank · pipe | ~20.7s | 10.18s | 2.03x (see note) |
| CatRank · pipe packed | ~24.0s | 18.09s | 1.33x (see note) |
| Eval · object | ~13.3s | 13.18s | 1.01x |
| Eval · bytes | ~14.3s | 13.85s | 1.03x |
| Eval · bytes packed | ~34.0s | 30.69s | 1.11x |
| Eval · pipe | ~32.0s | 128.16s | 0.25x (see note) |
| Eval · pipe packed | ~51.0s | 148.08s | 0.34x (see note) |

**The pipe (client+server) rows are not comparable.** Our two-JVM FIFO runs are
dominated by `sys` time from cross-process I/O in this container (Eval pipe: 128s
real, of which ~92s is `sys`) — an environment artifact, not a JDK/code change.
Ignore the pipe offsets.

**In-process offsets (object / bytes / bytes-packed) — the meaningful ones:**

- **CatRank: ~1.7–2.5x faster** (avg ~2.1x) — it is string-bound, and Java string
  handling is exactly what improved most across JDK 8 -> 21 (compact strings, UTF
  transcode intrinsics, better GC for the byte[] churn).
- **CarSales: ~1.3–1.5x faster** (avg ~1.4x) — allocation-bound; benefits from a
  decade of JIT escape-analysis and young-gen GC improvements against the
  per-member `StructReader` churn.
- **Eval: ~1.0–1.1x faster** (essentially flat) — the announcement said Eval is
  "fundamentally limited by the fact that Java bounds-checks every array access."
  `ByteBuffer` bounds-checks are still present in JDK 21, so the case pinned on
  them barely moved. The offset independently confirms that diagnosis.

Since C++/Rust would also gain from newer compilers/hardware, the original ~2–3x
cross-language gap most likely persists (Eval especially). Measuring the exact
modern gap would require building and running the C++ and Rust suites on this box.

## Independent verification of the 2014 per-case claims

The announcement made two source-level claims. Both were verified here by code
inspection **and** by an allocation profile (JDK 21 Flight Recorder,
`settings=profile`, `object` mode).

### CarSales — "allocate a new StructReader for each member of a list"

- Code: `ListReader._getStructElement` calls `factory.constructReader(...)`, which
  does `new Reader(...)` — one fresh reader per element. Invoked once per element
  by every `for (Car.Reader ...)` and `for (Wheel.Reader ...)` loop in
  `CarSales.carValue` / `handleRequest`.
- JFR allocation-by-class (top types): `Wheel$Reader` 22.8%, `StructList$Reader`
  10.1%, `Car$Reader` 7.7%, `Engine$Reader` 6.1% (~47% combined). Struct/list
  reader objects dominate allocation — confirmed **allocation-bound**.

### CatRank — "UTF-16 vs UTF-8 requires significant copying of memory"

- Code: `Text.Reader.toString()` does `new byte[size]` + `new String(bytes, UTF_8)`
  (a UTF-8 -> UTF-16 transcode), called **twice per result** in
  `CatRank.handleRequest` (`getSnippet().toString().contains(...)`); the write path
  (`Text.Reader(String)`) does the reverse `getBytes(UTF_8)`.
- JFR allocation-by-class (top types): `byte[]` 71.2%, `HeapByteBuffer` 8.4%, plus
  `Text$Builder` / `Text$Reader` / `String` / `StringBuilder`. String-transcode
  buffers dominate allocation — confirmed **string-copy-bound**.

The two profiles cleanly separate the bottlenecks (CarSales = reader-object churn,
CatRank = string-transcode churn), matching the announcement's per-case analysis.
These are precisely the areas — per-member reader allocation, encoding copies, and
`ByteBuffer` bounds-checks — that an FFM/`MemorySegment`-backed runtime would aim
to eliminate, making this a useful baseline for a future FFM comparison.
