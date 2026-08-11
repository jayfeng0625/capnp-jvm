# capnp-jvm — Design Rationale

Founding rationale for **capnp-jvm**, a Cap'n Proto implementation for the JVM
built on the platform internals that exist **now** (FFM, JDK 25 LTS) and designed
so the internals that come **later** (Valhalla value classes, JDK 28+) drop in
without an API break.

Everything asserted here is one of: **(m)** measured in this repository (raw data
under `benchmark/ffm/`, analysis in `benchmark/FFM_PROTOTYPE_RESULTS.md` and
`benchmark/FFM_VALHALLA_JDK25.md`); **(v)** verified live on JDK 25 in this
session (`benchmark/ffm/MmapSmoke.java`); **(d)** quoted from this repo's own
docs (`website/index.md`, `benchmark/BENCHMARK_RESULTS.md`); or **(e)** external
(JEPs / cited articles).

Environment for all measurements: Temurin JDK **25.0.4** (FFM final, JEP 454) and
OpenJDK **27-jep401ea3** Valhalla EA (JEP 401 preview), 4-vCPU shared Linux box.
Absolute numbers drift between JVM launches on this box; every comparison below
is a **within-run pair** or a JMX allocation delta (stable).

---

## 1. Cap'n Proto's nine promises, and what the JVM needs for each

The feature list from [capnproto.org](https://capnproto.org/) is the contract.
Column three names the JVM internal that carries the promise; column four is
what the JVM *adds* beyond the C++ reference.

| # | Promise | Carried by | JVM complement | Status |
| --- | --- | --- | --- | --- |
| 1 | **Incremental reads** (outer objects precede inner) | format + lazy readers; pointer-follow bounds check guards not-yet-arrived bytes | Loom-friendly async feed | free |
| 2 | **Random access** (read one field without parsing) | lazy readers + JIT-inlined accessors | — (reader-allocation tax removed by Valhalla, §3) | free; tax measured (m) |
| 3 | **mmap** (OS pages in only what you touch) | **FFM**: `FileChannel.map(mode, off, size, arena)` → `MemorySegment` | 64-bit indexing (no 2 GB cap), **deterministic unmap**, checked access | **verified (v)** |
| 4 | **Inter-language** (share one structure with C++/Python) | **FFM**: native `MemorySegment` + `Linker` downcalls | no JNI, no copy — Java becomes a cheap polyglot peer | available (e) |
| 5 | **Inter-process** (shared memory, no kernel pipe) | **FFM**: MAP_SHARED mapping as `MemorySegment` | GC never moves native memory; access stays bounds/liveness-checked | **mechanism verified (v)** |
| 6 | **Arena allocation** (fast, cache-local, no alloc churn) | **FFM**: confined `Arena` (`ArenaAllocator` here) | deterministic bulk-free at `close()`; heap GC untouched | **measured (m)** |
| 7 | **Tiny generated code** (inline accessors) | format + JIT inlining of small accessors | — | free |
| 8 | **Tiny runtime library** | FFM ships in `java.base` — zero dependencies | — | free |
| 9 | **Time-traveling RPC** (promise pipelining) | not in capnproto-java ("The entire object-capability RPC layer" is listed as Future Work (d)) | **Loom** virtual threads (JEP 444, final JDK 21) + `CompletableFuture` pipelining | to build |

Promises 1, 2, 7, 8 are **format properties** — any correct implementation keeps
them. Promises 3–6 are **native-memory properties**, and all four hang off one
internal: **FFM**. Promise 9 is an RPC layer that has to be written; Loom is the
internal that makes its "calling another process ≈ calling another thread" pitch
real on the JVM.

### The mmap/IPC verification (v)

`benchmark/ffm/MmapSmoke.java`, stock JDK 25:

```
mapped 3221225472 bytes (3.2 GB) -> MemorySegment, byteSize=3221225472
write via mapping A @2500000000, read via mapping B: VISIBLE (shared pages)
arena closed -> unmapped deterministically; file logical size=3221225472, deleted
```

- 3.2 GB in **one** segment with `long` indexing — a `ByteBuffer` caps at 2³¹−1.
- Two independent mappings of the same file see each other's writes (MAP_SHARED
  — the exact mechanism of cross-process shared memory).
- Unmap happens at `Arena.close()`, not when a GC eventually runs a Cleaner.

---

## 2. The load-bearing internal available now: FFM (measured)

`ArenaAllocator` (in `runtime/`) carves message segments from a **confined
`java.lang.foreign.Arena`** and plugs into the existing
`MessageBuilder(Allocator)` seam. Message memory becomes native, cache-dense,
and is freed deterministically per message. The scalar accessors stay on
`ByteBuffer` (see §4 — that choice is evidence, not conservatism).

Consolidated sweep, heap vs arena, same run (`raw-consolidated-sweep.txt`):

| Case / mode | heap ns/iter | arena ns/iter | Δ |
| --- | ---: | ---: | ---: |
| Eval object | 6,027 | 3,617 | **−40%** |
| Eval bytes | 6,757 | 4,165 | **−38%** |
| CarSales object | 44,332 | 45,159 | +1.9% |
| CarSales bytes | 50,224 | 57,133 | +13.8% |
| CatRank object | 471,417 | 475,011 | +0.8% |
| CatRank bytes | 549,497 | 556,584 | +1.3% |

Supporting profile (JMX `ThreadMXBean`/GC beans): Eval heap allocation
**21.2 → 5.0 KB/iter (−76%)** (−85% with `ByteBuffer` access), GC count 15 → 5.
Off-heap footprint is invisible to `BufferPoolMXBean` (it is malloc'd, not NIO
"direct") and shows under NMT `Other` as only the in-flight messages (~24 KB).
Contrast measured for per-message `DirectByteBuffer`: direct pool ballooning to
**2 GB** with ~1 s of GC (`OffHeapJmx.java`) — Cleaner-based freeing is exactly
what the arena design avoids.

Against the project's own 2014 baseline (d), fresh-JVM wall at 2M iterations
(2014 methodology): Eval object 2014 ≈ 13.3 s → JDK 25 heap 14.27 s (flat for a
decade — the case the announcement called *"fundamentally limited by the fact
that Java bounds-checks every array access"*) → **arena 7.65 s = 1.74× vs 2014,
1.87× vs JDK 25 heap**. The arena is the first thing in ten years of JVM
generations to move that case.

Interpretation is by *what each workload allocates*: Eval is
message-buffer-bound → arena wins. CarSales is **reader-object-bound** (§3) →
arena neutral. CatRank is string-transcode-bound (§5) → arena irrelevant.
CarSales bytes regresses (+14%) because serialization bulk-copies
native→heap scratch — hence design tenet T5 below.

---

## 3. The load-bearing internal coming later: Valhalla value classes (measured on EA)

**The one C++ property the JVM cannot yet preserve** is *"a reader is a fat
pointer passed by value."* In C++ a reader is ~4 words in registers; creating
one per struct descent or list element costs nothing. In Java a reader is a heap
object, saved only when **escape analysis (EA)** scalarizes it — and EA fails
exactly where capnproto-java lives: across the polymorphic, often-not-inlined
`factory.constructReader(...)` boundary used for every descent and every list
element (`ListReader._getStructElement`, flagged in source). This is not
hypothetical: JFR attributes **~47% of CarSales `object` allocation** to reader
objects (`Wheel$Reader` 22.8%, `StructList$Reader` 10.1%, `Car$Reader` 7.7%,
`Engine$Reader` 6.1%) (d/m). The repo's own Future Work list asks for *"iterators
for `StructList` that update in place instead of allocating for each element"*
(d) — the author hit the same wall in 2014.

Measured on the real JEP 401 EA build (`MicroValhalla.java`, reader with
`StructReader`'s exact field shape, constructed across a non-inlined factory
boundary — deterministic reproduction of the EA failure):

| | speed | allocation |
| --- | ---: | ---: |
| `class` reader (today) | 6.33–6.42 ns/element | **40 bytes/element** |
| **`value class` reader** | **2.34–2.40 ns/element (~2.7×)** | **0 bytes** |
| where EA already succeeds | 0.31 ns — identical | 0 — identical |

Value classes fix precisely the case EA loses, and change nothing where EA wins.
The two axes are orthogonal and **stack**: FFM/arena removes message-buffer
allocation (Eval-shaped workloads, **now**); value-class readers remove
per-element reader allocation (CarSales-shaped workloads, **JDK 28+ preview**).
Later still: null-restricted flat value arrays (post-JEP-401) for contiguous
reader arrays, and specialized generics.

---

## 4. Scalar access: the evidence says `ByteBuffer` views, not `MemorySegment` — for now

The intuitive FFM move — rewrite `getInt/getLong` accessors on `MemorySegment` —
was prototyped, measured, and **rejected** (m):

- **Scattered** fixed-offset field reads (cap'n proto's actual shape — ~a dozen
  single loads per struct, no per-segment loop): `MemorySegment` is **~46%
  slower** than a `ByteBuffer` view of the same native memory (4.1 vs
  6.0 ns/struct), and the known `ofAddress().reinterpret(size)`
  bounds-check-elimination trick gives **no** improvement
  (`MicroAccessNative.java`).
- **Monotonic counted loops** (list walks): parity — 1.55–1.59 ns/record both
  ways (`CountedLoopAccess.java`). C2 hoists the segment checks only here,
  matching the published "10–100 iterations to break even" guidance (e).
- Macro confirmation: the full accessor swap cost 6% geomean, up to +26% on
  object modes, and reverting it while keeping the arena kept all of the arena's
  win (`raw-arena-access-comparison.txt`).

**Tenet:** store natively (FFM arena), access via each segment's `ByteBuffer`
view (`segment.asByteBuffer()`) whose intrinsics win on scattered loads; keep
the accessor layer swappable so improved C2 layout-folding can flip it later;
use `MemorySegment` access only where `ByteBuffer` cannot go (a single segment
> 2 GB — rare, since messages are multi-segment anyway).

---

## 5. Honest gaps (and the design response)

| Gap | Why | Response |
| --- | --- | --- |
| **Text transcode** | Java `String` is UTF-16; capnp text is UTF-8. C++ hands out a pointer; Java must copy + transcode — CatRank's JFR profile is 71% `byte[]` churn (d/m), and current `Text.Reader.toString()` even does an extra `byte[]` copy before decoding | API design, not JVM internals: byte-view-first text API (`MemorySegment`/`ByteBuffer` slice, lazy `CharSequence`), `String` only on demand; elide the extra copy |
| **Native→heap serialization copy** | CarSales bytes +14% under arena (m) | native-aware output: write segments to `FileChannel`/`SocketChannel` directly from the segment, no heap scratch hop |
| **Reader allocation until Valhalla** | EA fails at factory boundaries (m) | in-place **cursor iteration** for lists now (also the 2014 wish (d)); readers kept immutable, identity-free, factory-constructed so the `value class` flip is localized |
| **RPC layer doesn't exist** | serialization-only port (d) | build on Loom: virtual-thread-per-call, `CompletableFuture`-based promise pipelining |
| **JIT warm-up** | C++ pays no warm-up; benchmarks here exclude it by design | AOT/CDS for short-lived processes; steady-state is the design target |

## 6. Where the JVM *beats* the C++ reference

- **Memory-safe shared memory.** Every access through FFM keeps spatial +
  temporal checks (cheap under a confined arena). A stray access into a shared
  mapping throws in Java; in C++ it corrupts every attached process. Safe
  zero-copy IPC is a feature C++ cannot offer.
- **Use-after-free is impossible by construction.** Touching a message after its
  arena closed throws `IllegalStateException` (verified in the arena prototype) —
  the deterministic-free benefit without the dangling-pointer cost.
- **No-JNI polyglot.** FFM `Linker` calls native capnp (or anything) with no JNI
  glue and shares segments by address.
- **GC-free hot path, eventually end-to-end.** Arena (message bytes, now) +
  value-class readers (JDK 28+) → a read path that allocates nothing on the
  managed heap, removing the GC-pause objection to Java in latency-sensitive
  systems.

## 7. capnp-jvm design tenets (v0)

- **T1 — Segment = `MemorySegment`, lifetime = confined `Arena`.** One arena per
  message (or per request scope); `AutoCloseable` allocator; `Arena.allocate`
  zero-fill satisfies the wire contract. Heap segments remain supported for
  small/throwaway messages.
- **T2 — Accessors on `ByteBuffer` views** (per §4), behind a swappable internal
  seam; `MemorySegment` path only for >2 GB segments.
- **T3 — Readers are value-class-shaped from day one**: immutable, identity-free
  (no `==`/`synchronized`/identity-hash reliance), constructed via factories —
  so JEP 401 adoption is a local change plus a recompile.
- **T4 — List traversal via in-place cursors** (no per-element reader
  allocation) in the public API now; per-element readers stay available for
  ergonomics and become free under Valhalla.
- **T5 — Serialization is native-aware**: segment → channel directly; packed
  codec operates on segments; no mandatory heap staging buffer.
- **T6 — Text/Data are byte-views first**; `String` is an explicit, visible
  conversion.
- **T7 — Keep the amplification-defense bounds checks on pointer follow**
  (they are cheap relative to what they guard, and they are also what makes
  incremental/streamed reading safe).
- **T8 — RPC on Loom** when it comes: virtual threads for blocking-style calls,
  promise pipelining via composed futures.
- **T9 — Zero third-party runtime dependencies** (`java.base` only), preserving
  the tiny-runtime promise.

## 8. Roadmap by JDK

| JDK | What unlocks | capnp-jvm action |
| --- | --- | --- |
| **25 LTS (now)** | FFM final (JEP 454); `--enable-native-access` governance (JEP 472); Loom final since 21 | Ship T1–T7. Expected: Eval-shaped workloads ~1.4–1.9× over the ByteBuffer status quo (m); mmap/IPC/polyglot become first-class |
| **28 (preview)** | JEP 401 value classes | Flip readers to `value class` behind `--enable-preview`; expect per-element descent ~2.7× and reader allocation → 0 (m, EA-build) |
| **post-28** | null-restricted flat arrays; specialized generics | flat reader arrays; unboxed generic containers |
| **26+ note** | `sun.misc.Unsafe` memory access throws (JEP 471/498) | none — this design never touches `Unsafe` |

## 9. Verification ledger

| Claim | How checked |
| --- | --- |
| Arena deltas (Eval −40/−38%, CarSales/CatRank neutral) | `raw-consolidated-sweep.txt`, medians recomputed this session |
| Heap-alloc −76/−85%, GC 15→5 | `BenchHarnessJmx` runs (logged in the two results docs) |
| 2014 offsets (1.74×/1.37×) | fresh-JVM `time` at 2M iters vs the announcement's chart values (d) |
| MS scattered +46%, `reinterpret` no help, counted-loop parity | `MicroAccessNative.java`, `CountedLoopAccess.java`, `raw-valhalla-and-countedloop.txt` |
| Value class 40 B→0, ~2.7× | `MicroValhalla.java` on `openjdk 27-jep401ea3+1-1` |
| Readers ≈ 47% of CarSales allocation | JFR profile recorded in `BENCHMARK_RESULTS.md` (22.8+10.1+7.7+6.1) |
| mmap 3.2 GB, MAP_SHARED visibility, deterministic unmap | `MmapSmoke.java` run on JDK 25 (output quoted in §1) |
| RPC absent; in-place iterators wished for | `website/index.md` "Future Work" section, quoted verbatim |
| Direct-ByteBuffer trap (2 GB pool, ~1 s GC) | `OffHeapJmx.java` |
| JEP status (454/472/471/498/444/401) | JEP index + research citations in `FFM_PROTOTYPE_RESULTS.md` |
