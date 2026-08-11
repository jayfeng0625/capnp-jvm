# FFM Primitives Prototype — JDK 25 (results & analysis)

This follows up on `BENCHMARK_RESULTS.md`, whose conclusion was that the
`ByteBuffer` runtime is near its ceiling on modern JDKs and that *"a
`MemorySegment`/FFM runtime, not a newer JDK, is what would move these numbers."*
Here we actually build that prototype on **JDK 25**, keep the **unit tests
unchanged**, and measure.

**TL;DR.** A drop-in swap of the primitive field accessors from `ByteBuffer` to
the FFM `MemorySegment` API is **feasible** (compiles on JDK 25, all 27 tests
pass untouched) but does **not** improve this workload — it is ~6% slower on
geomean and up to **+16–26% slower** on the allocation-heavy in-process object
paths. Isolated micro-measurement shows FFM heap access is *at parity* with
`ByteBuffer`; the macro regression comes from (a) a per-`SegmentReader`
`MemorySegment` wrapper allocation (~16 KB/iter extra heap churn) and (b)
`MemorySegment.get(layout,…)` not intrinsifying as cleanly as `ByteBuffer`'s
dedicated accessors inside the deep, scattered real call tree. The real FFM
opportunity is **off-heap `Arena` memory + bulk fill/copy**, not a scalar swap.

## Environment

| Item | Value |
| --- | --- |
| Date | 2026-08-11 |
| CPU | Intel(R) Xeon(R) @ 2.80GHz, 4 vCPUs |
| JDK | Temurin **25.0.4+7** (LTS) — FFM is a final API since JDK 22 (JEP 454), no `--enable-preview` |
| Cap'n Proto | 1.0.1 |
| capnproto-java | 0.1.17-SNAPSHOT |
| Baseline | `ByteBuffer` runtime, recompiled at `--release 25` (same bytecode level as FFM) |

## What the prototype changes

FFM primitive access is wired in *without* disturbing the public API or the
tests:

- `SegmentReader` gains a `final MemorySegment memory` view built **over exactly
  the same memory** as the existing public `ByteBuffer buffer`
  (`MemorySegment.ofArray(buffer.array())` for heap buffers — global scope, no
  per-access liveness check; `ofBuffer` fallback for direct/sliced/read-only).
  `duplicate()`/`ofArray` **share** the backing store, so the two views stay
  coherent and **no bytes are copied**.
- Little-endian, `*_UNALIGNED` `ValueLayout` constants (cap'n proto data is LE
  and not guaranteed naturally aligned to a heap-array base).
- The scalar field accessors in `SegmentReader`, `SegmentBuilder`,
  `StructReader`, `StructBuilder`, `ListReader`, `ListBuilder` and the scalar
  pointer read/writes in `WireHelpers` route through the `MemorySegment`.
  Bulk paths (`memset`/`memcpy`/blob `put(slice)`) intentionally stay on
  `ByteBuffer` — they share memory with the segment, so they remain correct.
- `runtime/pom.xml`: compile at `--release 25` on JDK ≥ 22 (needed for
  `java.lang.foreign`); `--release 8` retained for JDK ≤ 21. The benchmark and
  generated code still target release 8 and call the runtime unchanged — the FFM
  types never appear in the public API.

Tests: **27 run, 0 failures, 0 errors, 1 pre-existing skip** — no test file was
modified.

## Methodology

In-JVM **steady-state** timing (`BenchHarness`): 2 warm-up batches then 5 timed
batches per config, one fresh JVM per config, **median** ns/iteration reported.
Deterministic input (`FastRand` fixed seed) makes runs reproducible. In-process
modes only (`object`, `bytes`, `bytes-packed`) — the pipe modes in
`do_benchmarks.bash` are dominated by container FIFO `sys` time and are not a
code signal. This is more sensitive than the fresh-JVM wall-clock method in
`BENCHMARK_RESULTS.md`; it isolates the runtime rather than JVM start-up.

## Results — baseline (ByteBuffer) vs FFM, JDK 25

`ns/iter`, median of 5; ratio = FFM / baseline, so `>1` means FFM is slower.

| Case | Mode | baseline | FFM | ratio | Δ% |
| --- | --- | ---: | ---: | ---: | ---: |
| CarSales | object | 60,982 | 77,014 | 1.263 | **+26.3%** |
| CarSales | bytes | 80,619 | 92,391 | 1.146 | +14.6% |
| CarSales | bytes-packed | 196,015 | 199,991 | 1.020 | +2.0% |
| CatRank | object | 713,158 | 677,124 | 0.949 | **−5.1%** |
| CatRank | bytes | 878,170 | 882,254 | 1.005 | +0.5% |
| CatRank | bytes-packed | 1,575,316 | 1,605,999 | 1.019 | +1.9% |
| Eval | object | 6,310 | 7,296 | 1.156 | +15.6% |
| Eval | bytes | 7,520 | 7,771 | 1.033 | +3.3% |
| Eval | bytes-packed | 16,342 | 16,507 | 1.010 | +1.0% |

**Geometric mean ratio ≈ 1.063 → FFM is ~6% slower overall.** The regression
concentrates in the `object` modes, and its size tracks *primitive-read
density*: CarSales/Eval `object` (many scalar field reads on small structs)
regress most; CatRank (string/`byte[]`-transcode bound, few scalar reads) is
unaffected or slightly better; the `packed`/`bytes` modes (dominated by
pack/unpack and copy work) are near-neutral.

## Why — diagnosis

Three targeted experiments separate the causes.

### 1. Isolated per-access cost is at parity (microbench)

`MicroAccess` — tight loop of mixed LE unaligned short/int/long reads on the
*same* heap `byte[]`:

| accessor | ns/pass |
| --- | ---: |
| `ByteBuffer` (LE) | ~232–249 |
| `MemorySegment.ofArray` (LE layout) | ~243–248 |
| `MemorySegment.ofBuffer` (LE layout) | ~244 |
| `MemorySegment` via cached `VarHandle` | ~245–305 |

So in a shallow, fully-inlined loop **heap FFM access equals `ByteBuffer`**. The
macro regression is therefore *not* a fundamental per-read cost.

### 2. Construction vs. access split (A/B)

Building a variant that **still constructs** the `MemorySegment` per
`SegmentReader` but reads back through `ByteBuffer` isolates the two costs:

| config | baseline | construct-only | full FFM |
| --- | ---: | ---: | ---: |
| CarSales object | 60,982 | 64,866 (+6%) | 77,014 (+26%) |
| Eval object | 6,310 | 6,120 (**−3%**) | 7,296 (+16%) |

→ per-segment **construction** costs ~0–6%; the rest is **access in situ**.
Raising C2 inlining limits (`-XX:MaxInlineLevel/FreqInlineSize/MaxInlineSize`)
did **not** recover it — `MemorySegment.get(layout,…)` needs full inlining +
layout-constant folding to become a raw load, and in the large, scattered,
allocation-interleaved hot methods that does not happen, whereas `ByteBuffer`'s
accessors are recognized intrinsics that fire regardless of context.

### 3. Allocation & copies (JMX)

`BenchHarnessJmx` — `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`,
GC MXBeans, and `BufferPoolMXBean("direct")`:

| config | baseline heap B/iter | FFM heap B/iter | FFM extra | direct pool |
| --- | ---: | ---: | ---: | ---: |
| CarSales object | 111,798 | 127,933 | **+16.1 KB (+14%)** | 0 |
| CarSales bytes | 126,961 | 143,347 | +16.4 KB | 0 |
| CatRank object | 1,038,857 | 1,059,590 | +20.7 KB (+2%) | 0 |
| Eval object | 18,989 | 21,277 | +2.3 KB (+12%) | 0 |

The extra allocation is **wrapper-object sized, not buffer-sized**, and the
direct pool never moves → the FFM view **copies nothing**; it only adds
`MemorySegment` wrapper churn on the heap (more young-gen GC pressure, which
hurts the already allocation-bound `object` paths most).

## Unnecessary copies (audit)

- **FFM view: none.** `duplicate()` + `ofArray`/`ofBuffer` share memory; JMX
  confirms no off-heap copy and only wrapper-sized heap growth.
- **`Text.Reader.toString()`** allocates `new byte[size]`, copies the segment
  bytes into it, *then* `new String(bytes, UTF_8)` transcodes — an extra copy
  before the transcode (the CatRank `byte[]`-71% hotspot). Elidable for
  heap-backed buffers via `new String(array, arrayOffset+offset, size, UTF_8)`.
- **`WireHelpers.memset`** is a byte-at-a-time loop (`// TODO faster`) — a direct
  `MemorySegment.fill((byte)0)` target.
- **`SegmentBuilder.clear()`** zeroes word-by-word — also bulk-fillable.

These bulk/fill/transcode paths — not scalar field access — are where FFM can
actually reduce work.

## Heap vs off-heap (JMX)

`OffHeapJmx` — per-"message" allocate → mixed write/read → discard, 1M messages,
the no-reuse pattern:

| strategy | ns/msg | heap B/msg | GC time | direct pool Δ |
| --- | ---: | ---: | ---: | ---: |
| heap `ByteBuffer` | ~1,180–1,250 | 2,064 | low | 0 |
| heap `MemorySegment` | ~1,180–1,270 | 2,064 | low | 0 |
| direct `ByteBuffer` (per-msg) | 8,500 → **24,400** | 136 | **939 ms** | **→ 2 GB** |
| native `MemorySegment` (`Arena`) | ~1,020–1,390 | 168 | **~0** | 0 |

- **heap MS ≡ heap BB** in both speed and allocation — confirms the drop-in swap
  is a wash (matches the macro table).
- **per-message direct `ByteBuffer` is a trap** — slow native alloc + zeroing +
  Cleaner registration, freed only by GC → the pool balloons to 2 GB and GC time
  spikes to ~1 s.
- **native `Arena` segments are as fast as heap** with a *tiny* heap footprint,
  **deterministic free** at `Arena.close()`, and **near-zero GC** — and they are
  invisible to `BufferPool`/GC pools because the memory is managed explicitly.

## Conclusion & recommendation

1. **Feasible, tests unchanged** — FFM primitives compile and run on JDK 25 with
   all unit tests passing untouched; the migration can be incremental (the FFM
   view shares memory with the existing `ByteBuffer`).
2. **A scalar-access swap is not worth it.** On heap, `MemorySegment` equals
   `ByteBuffer` per-access but adds wrapper-allocation and loses in-context
   intrinsification → a net ~6% regression, worst on the hot `object` paths.
3. **Where to actually spend FFM effort:**
   - **Off-heap message arenas** (`Arena`-allocated `MemorySegment`), not
     per-message `DirectByteBuffer`: as fast as heap, deterministic free,
     near-zero GC — the biggest lever for the allocation-bound `object`/`bytes`
     modes.
   - **Bulk ops**: `MemorySegment.fill` for `memset`/`clear`, `MemorySegment.copy`
     for `memcpy`, and eliding the extra `Text.toString` copy.
   - If pursued, make the segment implementation **monomorphic** at the shared
     accessor call sites and keep the hot accessors tiny so `MemorySegment.get`
     can intrinsify.

## Reproducing

Artifacts live under `benchmark/ffm/`:

```
# build capnpc-java (needs libcapnp-dev), then with JDK 25 on PATH:
mvn -q -pl benchmark -am compile
mvn -pl runtime test                      # 27 tests, unchanged

bash benchmark/ffm/run.sh ffm $JAVA_HOME   # 9-config steady-state sweep
java -cp runtime/target/classes:benchmark/target/classes \
     org.capnproto.benchmark.BenchHarnessJmx carsales object 20000
javac benchmark/ffm/MicroAccess.java && java -cp benchmark/ffm MicroAccess
javac benchmark/ffm/OffHeapJmx.java  && java -cp benchmark/ffm OffHeapJmx
```
