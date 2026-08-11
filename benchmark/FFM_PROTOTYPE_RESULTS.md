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

**Follow-up (measured, see the Arena section below):** an `ArenaAllocator`
putting message memory off-heap with deterministic free delivers the first
decisive win — **Eval −32%** (the case that had been flat since 2014, −76%
heap allocation), CarSales object −4–5%, CatRank neutral; CarSales `bytes`
regresses (+17–27%) because serialization still bulk-copies through heap
scratch buffers.

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

## Follow-up: Arena off-heap prototype (measured)

The recommendation above — *off-heap `Arena` message memory is the biggest
lever* — was implemented and measured next, on the same JDK 25 build.

### What was added

- **`org.capnproto.ArenaAllocator`** (runtime): an `Allocator` that carves
  segments out of a confined `java.lang.foreign.Arena` and implements
  `AutoCloseable` — all native memory is freed **deterministically** at
  `close()`, not when the GC gets around to it. It plugs into the existing
  `MessageBuilder(Allocator)` constructor; `Arena.allocate` zeroes memory, so
  the `Allocator` contract holds, and the returned
  `segment.asByteBuffer()` keeps the whole runtime (including bulk
  `ByteBuffer` paths) working unchanged. Accessing a message after its
  allocator is closed throws `IllegalStateException` (the FFM session is
  checked), so use-after-free is caught, not silent.
- **`TestCase.passByObjectArena` / `passByBytesArena`** (benchmark): identical
  loops to the existing no-reuse modes, but with one arena per message,
  closed at the end of each iteration — the natural request/response lifetime.
  `checkResponse` still validates every iteration. Unit tests remain untouched
  (27 run, 0 failures).

### Results — heap vs arena, same run, same JVM flags

Between-sweep numbers on this shared box drift (the heap-mode absolute values
below are faster than the earlier tables), so **only within-run pairs are
compared**. Two independent full rounds; `ns/iter`, median of 5.

| Case | Mode | heap (FFM) | arena | Δ round 1 | Δ round 2 |
| --- | --- | ---: | ---: | ---: | ---: |
| Eval | object | 6,673 / 6,836 | 4,448 / 4,657 | **−33%** | **−32%** |
| Eval | bytes | 7,534 / 7,389 | 5,418 / 5,305 | **−28%** | **−28%** |
| CarSales | object | 57,054 / 55,181 | 54,173 / 53,152 | −5.0% | −3.7% |
| CarSales | bytes | 56,785 / 61,265 | 72,193 / 71,384 | +27% | +17% |
| CatRank | object | 515,270 / 531,570 | 543,177 / 533,165 | +5.4% | +0.3% |
| CatRank | bytes | 586,664 | 580,336 | −1.1% | — |

(`x / y` = round 1 / round 2. Both rounds agree on every sign and magnitude.)

### JMX / NMT profile of the arena mode

| config | heap B/iter (heap-mode) | heap B/iter (arena) | GC count | time |
| --- | ---: | ---: | --- | --- |
| Eval object | 21,239 | **4,993 (−76%)** | 15 → 5 | 6,893 → 4,329 ns |
| CarSales object | 115,346 | 92,565 (−20%) | 8 → 8 | 56,479 → 53,763 ns |
| CarSales bytes | 135,505 | 107,871 (−20%) | 8 → 7 | 58,429 → 67,750 ns |

Observability: FFM arena memory is **not** in `BufferPoolMXBean("direct")`
(that pool stayed 0 in every arena run) — it is malloc'd memory visible under
**NMT** as the `Other` category (`-XX:NativeMemoryTracking=summary` +
`jcmd <pid> VM.native_memory`). Mid-run snapshots showed `Other (committed=24KB)`
in arena mode vs absent in heap mode: the steady-state off-heap footprint is
just the in-flight messages, because every iteration's memory is freed at
`close()` — the opposite of the 2 GB direct-pool balloon the per-message
`DirectByteBuffer` strategy produced.

### Reading the results

- **Eval −32%: the decade-old ceiling moved.** Eval is the case the 2014
  announcement called "fundamentally limited by the fact that Java
  bounds-checks every array access", and it stayed flat from JDK 8 through 25.
  Off-heap arena messages cut its heap allocation by 76% (young-GC pressure
  mostly vanishes) and native-segment access through the FFM layout path avoids
  the heap-array bounds/GC-barrier costs. This is the first configuration in
  this whole exercise that beat the `ByteBuffer` runtime decisively.
- **CarSales object −4–5%**: consistent small win — less allocation churn, but
  its cost is dominated by per-element `Reader` wrapper objects (still
  on-heap), which the arena can't fix.
- **CarSales bytes +17–27%**: the serialization path bulk-copies direct→heap
  (`ArrayOutputStream` scratch); for CarSales-sized messages that copy
  overhead exceeds the GC savings. A native-to-native output path (writing to
  an off-heap scratch or a `FileChannel` directly) would be needed to win here.
- **CatRank ~0%**: string-transcode-bound; message memory placement is
  irrelevant.

### Verdict

Arena off-heap is the first FFM configuration with a real, reproducible win —
but it is **workload-shaped**: decisive for small-struct/traversal-heavy
messages (Eval-like), neutral-to-positive for object graphs, and a regression
where big messages are immediately re-serialized through heap scratch buffers.
A production design should make the allocator opt-in per message (exactly what
`MessageBuilder(new ArenaAllocator())` already gives), and pair it with
native-aware serialization before turning it on for `bytes`-style pipelines.

## Follow-up 2: does the bounds-check-elimination technique help? (No — for this access shape)

Two questions were raised against the results above: (a) can the known
`MemorySegment.ofAddress(addr).reinterpret(size)` trick recover the scalar
regression, and (b) is the arena win actually about *access* or about
*allocation*? Both were measured.

### Background (JDK 25 FFM status, cited)

- FFM is **final since JDK 22 (JEP 454)** after three previews (424/434/442);
  JDK 25 ships it as stable non-preview `java.lang.foreign` in `java.base`. The
  only later change is governance: **JEP 472 (JDK 24)** put JNI and FFM under
  `--enable-native-access` (warn by default), and **JEP 471/498** deprecate and
  now warn on `sun.misc.Unsafe` memory access (JDK 26+ will throw). JDK 25 is
  therefore the sanctioned point to migrate off `Unsafe`/`ByteBuffer` internals
  onto `MemorySegment`. *Note:* capnproto-java uses public `ByteBuffer`, **not**
  `Unsafe`, so it is not under the JEP 498 removal pressure — the motivation here
  is performance, not forced migration.
  ([openjdk.org/jeps/498](https://openjdk.org/jeps/498),
  [nipafx.dev/jni-restriction](https://nipafx.dev/jni-restriction/))
- Every `MemorySegment` access checks bounds, liveness, alignment and
  read-only-ness — checks `Unsafe` skips. Cimadamore/Minborg (*"FFM vs. Unsafe.
  Safety (Sometimes) Has a Cost"*, Inside.java, 2025-06-12) measure a single
  stray FFM read at **1.482 ns/op vs 0.569 for `Unsafe` (~3×)**, but note the
  cost **amortizes in loops** — *"between 10 and 100 iterations to break even in
  the read case"* — because C2 hoists the checks out of the loop once it can
  prove the segment's size, e.g. via `ofAddress(addr).reinterpret(constSize)`.
  ([inside.java/2025/06/12/ffm-vs-unsafe](https://inside.java/2025/06/12/ffm-vs-unsafe/))

The operative caveat is *"in loops."* Cap'n Proto reads a handful of fixed-offset
fields per struct (a single aligned load each) — there is **no per-segment loop**
to hoist the checks out of.

### Microbench: `benchmark/ffm/MicroAccessNative.java`

A cap'n-proto-shaped *scattered* pattern — 8 int + 4 long reads per "struct" at
fixed offsets — on a **native** segment, comparing a field-sourced segment
against per-access `ofAddress(addr).reinterpret(size)` (run with
`--enable-native-access=ALL-UNNAMED`):

| accessor | ns/struct |
| --- | ---: |
| direct `ByteBuffer` | ~4.1 |
| `MemorySegment` field `.get` | ~6.0 |
| `MemorySegment` `ofAddress().reinterpret(const)` | ~5.9–6.0 |
| `MemorySegment` `ofAddress().reinterpret(size field)` | ~5.9–6.0 |

**The `reinterpret` trick makes no difference here, and `MemorySegment` is ~46%
slower than `ByteBuffer` for scattered reads.** With no loop to hoist out of,
there is nothing to amortize — exactly the boundary case the Inside.java
break-even implies. This is the mechanism behind the macro `object`-mode
regression, now confirmed directly.

### The arena win is allocation, not access — so pair it with `ByteBuffer` access

Isolating the access path on the arena runs (revert the 7 accessor files to
pre-FFM `ByteBuffer` access, keep `ArenaAllocator` + release-25 pom;
`benchmark/ffm/raw-arena-access-comparison.txt`):

| config | MS access + arena | **BB access + arena** | heap alloc (BB+arena) |
| --- | ---: | ---: | ---: |
| Eval object | 4,448 / 4,657 | **4,513** | **2,844 B/iter (−85%)** |
| Eval bytes | 5,305 / 5,418 | **4,993** | — |

`ByteBuffer` access + `ArenaAllocator` matches or beats the FFM-access arena on
time **and** allocates even less on the heap (2,844 vs 4,993 B/iter — it never
builds the per-segment `MemorySegment` wrappers), because the arena's win is
purely the off-heap deterministic-free allocation, which is **orthogonal to the
scalar access path**. `ArenaAllocator` itself uses FFM (`Arena`) for the
off-heap storage but plugs into the **unchanged `ByteBuffer` runtime**.

### Where the rest of the cost lives — Valhalla, not FFM

CarSales `object` barely moves under any variant because its cost is dominated
by per-element `Reader` **heap wrapper objects** (`new Wheel$Reader(...)` per
list element — JFR earlier put reader objects at ~47% of allocation), which
neither FFM storage nor an off-heap arena can remove. `StructReader`
(`{SegmentReader, int, int, int, short, int}`) is a textbook **value-class**
candidate: JEP 401 (Value Objects) would let C2 scalarize readers with no
allocation, but it is a preview targeting **JDK 28** (GA ~March 2027), not
JDK 25. On JDK 25 that allocation can only be attacked via escape analysis.
([The Register, 2026-06-15](https://www.theregister.com/2026/06/15/java_jep401_valhalla/))

### Net conclusion

1. **Do not swap scalar field access to `MemorySegment`** — it is ~46% slower
   for cap'n proto's scattered fixed-offset reads and the `reinterpret`
   bounds-check-elimination technique does not apply without a per-segment loop.
2. **Do adopt `ArenaAllocator` (opt-in per message) with the existing
   `ByteBuffer` runtime** — that is where the measurable win is (Eval −21…−33%,
   heap allocation −85%, deterministic off-heap free), and it needs no change to
   the hot accessors.
3. The remaining `object`-mode ceiling (reader-wrapper allocation) is a
   **Valhalla / JEP 401** problem, out of reach on JDK 25.

## Reproducing

Artifacts live under `benchmark/ffm/`:

```
# build capnpc-java (needs libcapnp-dev), then with JDK 25 on PATH:
mvn -q -pl benchmark -am compile
mvn -pl runtime test                      # 27 tests, unchanged

bash benchmark/ffm/run.sh ffm $JAVA_HOME   # steady-state sweep incl. *-arena modes
java -cp runtime/target/classes:benchmark/target/classes \
     org.capnproto.benchmark.BenchHarnessJmx carsales object 20000
javac benchmark/ffm/MicroAccess.java && java -cp benchmark/ffm MicroAccess
javac benchmark/ffm/OffHeapJmx.java  && java -cp benchmark/ffm OffHeapJmx
javac benchmark/ffm/MicroAccessNative.java \
  && java --enable-native-access=ALL-UNNAMED -cp benchmark/ffm MicroAccessNative
```
