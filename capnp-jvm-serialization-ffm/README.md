# capnp-jvm-serialization-ffm

Cap'n Proto serialization built on the Foreign Function and Memory API (FFM, JEP 454, final since JDK 22, on the JDK 25 LTS baseline).
FFM supplies four things here: native message storage, deterministic ownership, file mapping, and native stream I/O.
Scalar Cap'n Proto field access intentionally stays on ByteBuffer views, because that path measured faster than raw `MemorySegment` accessors on JDK 25.
So FFM owns the buffer and its lifetime, while the existing ByteBuffer accessor reads and writes the fields inside it.
This module implements the same interfaces as `capnp-jvm-serialization-bytebuffer`: `BufferedInputStream` and `BufferedOutputStream` from `capnp-jvm-serialization`, and `Allocator` from `capnp-jvm-core`.
The two modules are drop-in alternatives that produce identical bytes on the wire.

## Design

The shape of this module follows what was *measured* when prototyping FFM
primitives on JDK 25 (research and raw data on the
`claude/ffm-primitives-jdk25-benchmark-n003lw` branch: `DESIGN_RATIONALE.md`,
`benchmark/FFM_PROTOTYPE_RESULTS.md`, `benchmark/ffm/`), not what looks most
FFM-idiomatic:

1. **Store natively, in arenas.** Message memory is carved from confined
   `java.lang.foreign.Arena`s (`ArenaAllocator` for building,
   `FfmMessage` for reading — one arena per message, or one per request
   scope via the arena-adopting constructor). `Arena.allocate` zero-fills,
   satisfying the wire contract; `close()` frees the native memory
   deterministically, instead of waiting for the GC to run a Cleaner the way
   per-message direct ByteBuffers do (measured ballooning the direct pool to
   2 GB with ~1 s of GC). Use-after-close throws `IllegalStateException`
   rather than reading freed memory. Measured on the Eval benchmark:
   ~40% faster, −76% heap allocation.

2. **Access through ByteBuffer views.** Scalar accessors stay on each
   segment's little-endian `MemorySegment.asByteBuffer()` view. Raw
   `MemorySegment` accessors measured ~46% *slower* for cap'n proto's actual
   access shape — scattered fixed-offset field loads — because C2 only hoists
   segment bounds checks out of monotonic counted loops; the
   `ofAddress().reinterpret()` trick did not help. The accessor layer is the
   core module's existing ByteBuffer path, so the runtime and generated code
   run unchanged, and the choice can be revisited without an API break if C2
   improves.

3. **Serialize natively end to end.** Reading from a channel places all
   segments in one contiguous native allocation, filled segment by segment
   (per-segment reads keep the packed decoder's boundary validation
   identical to the ByteBuffer module's).
   Writing hands the channel direct buffers (no on-heap staging hop — the
   measured cause of the arena prototype's +14% regression on
   serialization-heavy workloads), and gathering channels receive the segment
   table plus all segments as one vectored write. Stream buffering
   (`FfmBufferedInputStream` / `FfmBufferedOutputStream`) happens in
   native memory, so kernel-facing reads and writes skip the JDK's internal
   heap→direct copy. `FfmSerialize.map(path)` goes further and reads
   nothing at all: the file is mapped through the arena, the OS pages in only
   what the reader touches, 64-bit indexing lifts the 2 GB `ByteBuffer` cap,
   and closing the message unmaps deterministically.

No restricted FFM methods are used, so no `--enable-native-access` flag is
needed, and the module keeps the zero-third-party-dependency promise
(`java.base` only).

## Class map

| FFM module | ByteBuffer module counterpart |
| --- | --- |
| `ArenaAllocator` | `DefaultAllocator` (core) |
| `FfmMessage` | — (deterministic-lifetime reader handle) |
| `FfmSerialize` | `Serialize` |
| `FfmSerializePacked` | `SerializePacked` |
| `FfmPackedInputStream` / `FfmPackedOutputStream` | `PackedInputStream` / `PackedOutputStream` |
| `FfmBufferedInputStream` / `FfmBufferedOutputStream` | `BufferedInputStreamWrapper` / `BufferedOutputStreamWrapper` |
| `MemorySegmentInputStream` / `MemorySegmentOutputStream` | `ArrayInputStream` / `ArrayOutputStream` |

## Use

```java
// Build a message in native memory, freed deterministically:
try (ArenaAllocator allocator = new ArenaAllocator()) {
    MessageBuilder message = new MessageBuilder(allocator);
    // ... build ...
    FfmSerialize.write(channel, message);   // vectored, zero-copy from native segments
}

// Read a message into native memory:
try (FfmMessage message = FfmSerialize.read(channel)) {
    Foo.Reader root = message.getRoot(Foo.factory);
    // ...
}

// Or map a file and never read the parts you don't touch:
try (FfmMessage message = FfmSerialize.map(path)) {
    // ...
}
```

Confined arenas (the default) are single-threaded: build and read a message
on the thread that created its allocator, or pass `Arena.ofShared()` /
`Arena.ofAuto()` where a message must cross threads.

## Measured behavior (what to expect where)

FFM is not a general-purpose replacement that beats heap ByteBuffer everywhere.
It wins where message-buffer allocation, deterministic free, native I/O, or file mapping dominate the run.
It stays near-neutral, or slightly slower, where reader-object allocation or small-message arena setup dominates.
Scalar field access runs the same ByteBuffer path in both modules, so FFM does not make individual field reads faster.

Directional wall-clock numbers from this repository's benchmark harness follow (`do_benchmarks.bash` arena modes, JDK 25, shared 4-vCPU box).
Every heap/arena pair ran back to back in one session, so trust the signs and rough magnitudes, not the digits.

| Benchmark | heap | arena | Δ |
| --- | ---: | ---: | ---: |
| Eval object | 6.8 s | 5.2 s | **−24%** |
| Eval bytes | 7.6 s | 6.5 s | **−15%** |
| Eval bytes packed | 15.0 s | 11.9 s | **−21%** |
| CarSales bytes | 8.4 s | 9.0 s | +8% |
| CarSales bytes packed | 19.1 s | 15.5 s | **−18%** |

The pattern matches the research branch.
Workloads bounded by message-buffer allocation (Eval-shaped) win large.
Workloads bounded by reader-object allocation (CarSales-shaped) stay near-neutral until Valhalla value-class readers land.

Packed mode needs a separate note, because part of its gain comes from the codec, not from FFM.
A byte-at-a-time packed writer pays a liveness check on every read from a confined arena's buffer view.
That check initially made dense-message packing (CarSales packed) about 20% slower than heap.
`FfmPackedOutputStream` therefore packs a word at a time.
It reads each word once as a long, then compacts its nonzero bytes with register arithmetic.
This word-at-a-time rewrite is a SWAR codec change, independent of FFM.
The heap module's writer could adopt the same technique.
Where `Long.compress` lowers to a hardware bit-gather (x86-64 PEXT, or aarch64 under SVE2), it does the compaction.
Where `Long.compress` is a scalar software fallback, as on Apple and current server aarch64, a branch-free shift path compacts instead.
The code chooses the path once at class load, by a VM-flag capability probe.
Both paths produce identical wire bytes, verified against a reference implementation over randomized inputs.
The single word-at-a-time read removes the regression on its own.
On x86 the hardware bit-gather adds the rest of the win.
On aarch64 the shift path recovers most of it (the latest aarch64 log below).
So the packed wins reflect the SWAR/bit-gather writer as much as native storage.
A fair packed comparison would run the same word-at-a-time codec on the heap module and the FFM module.

## Benchmark log

Append-only record of full benchmark-matrix runs (`do_benchmarks.bash`
modes). Wall-clock `real` seconds, one run per cell; `client/server` rows
time the whole `client | server` pipeline over a FIFO. `no-reuse` is the
ByteBuffer module with fresh heap allocation per iteration; `arena` is the
FFM module with a fresh confined arena per iteration.
Δ = (arena − no-reuse) / no-reuse; negative means the arena run was faster.

### 2026-08-15 — Linux x86_64, 4 vCPU, Temurin 25.0.4+7, HEAD 9cace9b

30/30 runs exited 0 with no correctness failures reported by the harness.

| Case | Mode | Compression | Iterations | no-reuse | arena | Δ |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| CarSales | object | none | 100,000 | 7.527 | 7.748 | +2.9% |
| CarSales | bytes | none | 100,000 | 10.224 | 9.896 | −3.2% |
| CarSales | bytes | packed | 100,000 | 19.131 | 15.876 | −17.0% |
| CarSales | client/server | none | 100,000 | 17.533 | 17.714 | +1.0% |
| CarSales | client/server | packed | 100,000 | 26.237 | 22.107 | −15.7% |
| CatRank | object | none | 10,000 | 7.009 | 7.392 | +5.5% |
| CatRank | bytes | none | 10,000 | 8.525 | 9.217 | +8.1% |
| CatRank | bytes | packed | 10,000 | 15.722 | 13.475 | −14.3% |
| CatRank | client/server | none | 10,000 | 12.387 | 12.499 | +0.9% |
| CatRank | client/server | packed | 10,000 | 17.504 | 15.071 | −13.9% |
| Eval | object | none | 2,000,000 | 13.687 | 10.248 | −25.1% |
| Eval | bytes | none | 2,000,000 | 15.595 | 12.197 | −21.8% |
| Eval | bytes | packed | 2,000,000 | 30.558 | 23.222 | −24.0% |
| Eval | client/server | none | 2,000,000 | 122.115 | 124.149 | +1.7% |
| Eval | client/server | packed | 2,000,000 | 138.948 | 138.232 | −0.5% |

### 2026-08-15, macOS 26.5.2 arm64 (Apple M5 Pro, 18 cores), Temurin 25.0.4+7, HEAD dcb5f0e

30/30 runs exited 0 with no correctness failures reported by the harness.
`FfmSerializePackedTest` passes 7/7 here, so the word-at-a-time packed writer
produces identical wire bytes on the scalar `Long.compress` path.

| Case | Mode | Compression | Iterations | no-reuse | arena | Δ |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| CarSales | object | none | 100,000 | 2.624 | 3.363 | +28.2% |
| CarSales | bytes | none | 100,000 | 2.729 | 2.618 | −4.1% |
| CarSales | bytes | packed | 100,000 | 6.708 | 7.759 | +15.7% |
| CarSales | client/server | none | 100,000 | 3.868 | 4.172 | +7.9% |
| CarSales | client/server | packed | 100,000 | 6.899 | 7.977 | +15.6% |
| CatRank | object | none | 10,000 | 3.147 | 3.750 | +19.2% |
| CatRank | bytes | none | 10,000 | 3.293 | 3.734 | +13.4% |
| CatRank | bytes | packed | 10,000 | 5.563 | 6.758 | +21.5% |
| CatRank | client/server | none | 10,000 | 4.485 | 4.770 | +6.4% |
| CatRank | client/server | packed | 10,000 | 5.400 | 6.536 | +21.0% |
| Eval | object | none | 2,000,000 | 4.099 | 4.146 | +1.1% |
| Eval | bytes | none | 2,000,000 | 4.696 | 4.885 | +4.0% |
| Eval | bytes | packed | 2,000,000 | 10.347 | 11.578 | +11.9% |
| Eval | client/server | none | 2,000,000 | 20.329 | 20.030 | −1.5% |
| Eval | client/server | packed | 2,000,000 | 21.435 | 22.937 | +7.0% |

Note: every packed arena row regressed here, by +7% to +22%.
That is the sign inverse of the x86 run above, where packed arena won by 14% to 24%.
AArch64 has no scalar PEXT, and this JVM runs with UseSVE=0 (verified with `-XX:+PrintFlagsFinal`), so C2 emits no SVE2 bit-permute either.
So `Long.compress` in `FfmPackedOutputStream` runs the `java.lang.Long` software fallback, not a single hardware bit-gather.
The per-read liveness check on the confined arena buffer view is then no longer hidden by a cheap compaction.
The eight-register-shift emitter keeps the single `getLong` and drops `Long.compress`; it is the ready fallback for aarch64 parity.
The non-packed arena wins also shrank to near neutral, because this box runs the allocation-bound cases about 3x faster in absolute terms, so per-iteration arena setup and teardown now dominate the small messages.

### 2026-08-15, macOS 26.5.2 arm64 (Apple M5 Pro, 18 cores), Temurin 25.0.4+7, HEAD 8cfabdb

The shift-path packed writer is active here: the capability probe reads UseSVE=0, so `HAS_FAST_BIT_GATHER` is false.
30/30 runs exited 0 with no correctness failures reported by the harness.
`FfmSerializePackedTest` passes 9/9, including the shift gather checked byte-for-byte against the `Long.compress` intrinsic.

| Case | Mode | Compression | Iterations | no-reuse | arena | Δ |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| CarSales | object | none | 100,000 | 2.102 | 2.515 | +19.6% |
| CarSales | bytes | none | 100,000 | 2.301 | 2.676 | +16.3% |
| CarSales | bytes | packed | 100,000 | 6.737 | 5.765 | −14.4% |
| CarSales | client/server | none | 100,000 | 3.554 | 4.453 | +25.3% |
| CarSales | client/server | packed | 100,000 | 6.608 | 6.670 | +0.9% |
| CatRank | object | none | 10,000 | 3.128 | 3.681 | +17.7% |
| CatRank | bytes | none | 10,000 | 3.198 | 3.778 | +18.1% |
| CatRank | bytes | packed | 10,000 | 5.435 | 5.917 | +8.9% |
| CatRank | client/server | none | 10,000 | 4.510 | 4.857 | +7.7% |
| CatRank | client/server | packed | 10,000 | 5.538 | 6.000 | +8.3% |
| Eval | object | none | 2,000,000 | 4.306 | 4.182 | −2.9% |
| Eval | bytes | none | 2,000,000 | 4.596 | 4.820 | +4.9% |
| Eval | bytes | packed | 2,000,000 | 10.682 | 9.341 | −12.6% |
| Eval | client/server | none | 2,000,000 | 19.980 | 19.802 | −0.9% |
| Eval | client/server | packed | 2,000,000 | 21.569 | 20.615 | −4.4% |

Note: every packed arena row improved against the dcb5f0e run above, where the scalar `Long.compress` fallback made them +7% to +22% slower than heap.
CarSales and Eval bytes packed now win with the arena (−14.4%, −12.6%); the client/server packed rows moved to roughly neutral or a small win.
CatRank keeps a smaller +8% on its denser text words.
The non-packed rows are unchanged in character from that run, since they do not exercise the packed writer.
