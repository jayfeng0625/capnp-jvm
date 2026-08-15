# capnp-jvm-serialization-ffm

Cap'n Proto serialization built on the Foreign Function & Memory API (FFM,
JEP 454 — final since JDK 22, on the JDK 25 LTS baseline). This module sits
alongside `capnp-jvm-serialization-bytebuffer` and implements the same
interfaces — `BufferedInputStream` / `BufferedOutputStream` from
`capnp-jvm-serialization` and the `Allocator` seam from `capnp-jvm-core` — so
the two are drop-in alternatives that produce identical bytes on the wire.

## Design

The shape of this module follows what was *measured* when prototyping FFM
primitives on JDK 25 (research and raw data on the
`claude/ffm-primitives-jdk25-benchmark-n003lw` branch: `DESIGN_RATIONALE.md`,
`benchmark/FFM_PROTOTYPE_RESULTS.md`, `benchmark/ffm/`), not what looks most
FFM-idiomatic:

1. **Store natively, in arenas.** Message memory is carved from confined
   `java.lang.foreign.Arena`s (`ArenaAllocator` for building,
   `NativeMessage` for reading — one arena per message, or one per request
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
   (`NativeBufferedInputStream` / `NativeBufferedOutputStream`) happens in
   native memory, so kernel-facing reads and writes skip the JDK's internal
   heap→direct copy. `NativeSerialize.map(path)` goes further and reads
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
| `NativeMessage` | — (deterministic-lifetime reader handle) |
| `NativeSerialize` | `Serialize` |
| `NativeSerializePacked` | `SerializePacked` |
| `NativePackedInputStream` / `NativePackedOutputStream` | `PackedInputStream` / `PackedOutputStream` |
| `NativeBufferedInputStream` / `NativeBufferedOutputStream` | `BufferedInputStreamWrapper` / `BufferedOutputStreamWrapper` |
| `MemorySegmentInputStream` / `MemorySegmentOutputStream` | `ArrayInputStream` / `ArrayOutputStream` |

## Use

```java
// Build a message in native memory, freed deterministically:
try (ArenaAllocator allocator = new ArenaAllocator()) {
    MessageBuilder message = new MessageBuilder(allocator);
    // ... build ...
    NativeSerialize.write(channel, message);   // vectored, zero-copy from native segments
}

// Read a message into native memory:
try (NativeMessage message = NativeSerialize.read(channel)) {
    Foo.Reader root = message.getRoot(Foo.factory);
    // ...
}

// Or map a file and never read the parts you don't touch:
try (NativeMessage message = NativeSerialize.map(path)) {
    // ...
}
```

Confined arenas (the default) are single-threaded: build and read a message
on the thread that created its allocator, or pass `Arena.ofShared()` /
`Arena.ofAuto()` where a message must cross threads.

## Measured behavior (what to expect where)

Directional wall-clock numbers from this repository's benchmark harness
(`do_benchmarks.bash` arena modes, JDK 25, shared 4-vCPU box; every
heap/arena pair measured back to back in one session — trust the signs and
rough magnitudes, not the digits):

| Benchmark | heap | arena | Δ |
| --- | ---: | ---: | ---: |
| Eval object | 6.8 s | 5.2 s | **−24%** |
| Eval bytes | 7.6 s | 6.5 s | **−15%** |
| Eval bytes packed | 15.0 s | 11.9 s | **−21%** |
| CarSales bytes | 8.4 s | 9.0 s | +8% |
| CarSales bytes packed | 19.1 s | 15.5 s | **−18%** |

The pattern matches the research branch: workloads bounded by message-buffer
allocation (Eval-shaped) win large; workloads bounded by reader-object
allocation (CarSales-shaped) stay near-neutral until Valhalla value-class
readers land. Packed mode deserves a note: a byte-at-a-time packed writer
pays a liveness/ownership check on every read from a confined arena's buffer
view, which initially made dense-message packing (CarSales packed) ~20%
*slower* than heap. `NativePackedOutputStream` therefore packs a word at a
time — each word is read once as a long and its nonzero bytes are compacted
with register arithmetic (`Long.compress`, intrinsified on x86) — producing
identical wire bytes (verified against a reference implementation over
randomized inputs) while turning that regression into the −18% win above.
