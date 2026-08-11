# The optimal cap'n-proto-java on JDK 25 today — and what Valhalla adds next

This is the authoritative summary of the FFM/Valhalla exploration. The blow-by-blow
(scalar-swap prototype, its regression, the `reinterpret` test, the JMX/off-heap
work) is in [`FFM_PROTOTYPE_RESULTS.md`](FFM_PROTOTYPE_RESULTS.md). Here is the
cleaned-up conclusion, the consolidated implementation, and the measured Valhalla
upside.

## The one-paragraph answer

On **JDK 25**, the most optimizable approach is **off-heap message storage via a
confined FFM `Arena` (`ArenaAllocator`) on the *unchanged* `ByteBuffer` access
path** — not a `MemorySegment` scalar-accessor swap. Measured: **Eval −38…40%**
with **−76…85% heap allocation**, neutral on CarSales/CatRank `object`. The
scalar `MemorySegment` swap was prototyped and **reverted**: it is at best at
parity with `ByteBuffer` (monotonic counted loops) and up to **+46% slower**
(scattered field reads), and the `ofAddress().reinterpret()` bounds-check trick
does not help cap'n proto's scattered access. The remaining ceiling —
**per-element reader allocation** — is a **Project Valhalla** problem, not an FFM
one; on a JEP 401 early-access build a value-class reader is **~2.7× faster and
allocates 0 bytes** vs 40 bytes/element today, but that is **JDK 28+** (preview),
not JDK 25.

## Consolidated implementation (what's in the tree now)

- **`org.capnproto.ArenaAllocator`** — an `Allocator`/`AutoCloseable` that carves
  segments from a confined `java.lang.foreign.Arena`; native memory is freed
  deterministically at `close()`. Plugs into the existing
  `MessageBuilder(Allocator)` constructor. This is the whole JDK-25 win, and it
  needs **no change to the hot accessors** — the runtime keeps using `ByteBuffer`
  (which the arena provides via `segment.asByteBuffer()`).
- The exploratory **`MemorySegment` scalar-accessor swap was removed** from
  `SegmentReader`/`StructReader`/`ListReader`/… (measured regression; see below).
  The 7 accessor files are back to the stock `ByteBuffer` implementation.
- Benchmark harnesses (`BenchHarness`, `BenchHarnessJmx`, `TestCase.*Arena`) and
  the microbenches under `benchmark/ffm/` are kept as reproducible artifacts.
- Unit tests are **unmodified**: 27 run, 0 failures.

## Why `ByteBuffer` access, not the `MemorySegment` swap

Three microbenchmarks (JDK 25, `benchmark/ffm/`) settle it:

| measurement | result | file |
| --- | --- | --- |
| scattered field reads (native) | `MemorySegment` **+46%** slower than `ByteBuffer`; `ofAddress().reinterpret(size)` gives **no** improvement | `MicroAccessNative.java` |
| monotonic counted-loop list walk | `MemorySegment` **== `ByteBuffer`** (1.55–1.59 vs 1.56–1.59 ns/record) — C2 hoists the checks | `CountedLoopAccess.java` |
| heap vs off-heap per-message | native `Arena` as fast as heap, **~0 GC**, deterministic free; per-message `DirectByteBuffer` balloons the pool to 2 GB | `OffHeapJmx.java` |

Cap'n proto reads ~12 fixed-offset fields per struct as single aligned loads with
no per-segment loop, so it is the *scattered* case — where `MemorySegment` has no
upside and a real downside. The published FFM "10–100 iterations to break even"
(Cimadamore/Minborg) requires a loop to hoist checks out of; the counted-loop
result above is the boundary where that finally holds. Net: the FFM value is in
the **allocator**, not the accessors.

## Consolidated benchmark — ByteBuffer runtime, heap vs `ArenaAllocator` (JDK 25)

`ns/iter`, median of 5, `benchmark/ffm/raw-consolidated-sweep.txt`:

| Case | Mode | heap | arena | Δ |
| --- | --- | ---: | ---: | ---: |
| Eval | object | 6,027 | 3,617 | **−40.0%** |
| Eval | bytes | 6,757 | 4,165 | **−38.4%** |
| CarSales | object | 44,332 | 45,159 | +1.9% |
| CarSales | bytes | 50,224 | 57,133 | +13.8% |
| CatRank | object | 471,417 | 475,011 | +0.8% |
| CatRank | bytes | 549,497 | 556,584 | +1.3% |

JMX heap allocation (`BenchHarnessJmx`): Eval object **21.2 → 5.0 KB/iter (−76%)**,
GC count 15 → 5. Off-heap arena memory is invisible to `BufferPoolMXBean` (native,
not "direct") and shows only ~24 KB committed under NMT mid-run — the in-flight
messages, freed each iteration.

**Read the split by *what each workload allocates*:**

- **Eval is message-buffer-bound** → the arena moves that memory off-heap and
  wins big (−40%).
- **CarSales is reader-object-bound**, not buffer-bound (JMX: the arena only
  removes ~20 KB/iter of the ~115 KB; ~92 KB/iter is `Reader` wrappers) → arena is
  neutral, and the win it *can't* deliver is exactly Valhalla's (below).
- **CatRank is string-transcode-bound** → neither helps; the lever there is
  eliding the extra `Text.Reader.toString()` copy (noted in the prototype doc).
- **CarSales bytes +14%**: serialization still bulk-copies off-heap→heap scratch
  (`ArrayOutputStream`); a native-aware output path would be needed there.

## Project Valhalla: what it solves, where, and by how much (measured)

The residual cost the arena cannot touch is **per-element reader allocation**.
`ListReader._getStructElement` (annotated in the source) calls
`factory.constructReader(...) → new …Reader(...)` **once per list element**, and
descending into a nested struct does the same. That factory call is polymorphic
(one implementation per generated type), so **escape analysis cannot scalarize
the reader** — the allocation is real on JDK 25.

`StructReader` is `{ SegmentReader segment; int data; int pointers; int dataSize;
short pointerCount; int nestingLimit }` — an identity-free immutable "fat
pointer", the textbook **JEP 401 value class**. Measured on a real JEP 401 build
(**JDK 27 `jep401ea3`**, `benchmark/ffm/MicroValhalla.java`), a reader of this
exact shape constructed across a non-inlined factory boundary:

| | speed | allocation |
| --- | ---: | ---: |
| identity class (today) | 6.4 ns/element | **40 bytes/element** |
| **value class (Valhalla)** | **2.4 ns/element** | **0 bytes/element** |
| *(local use, EA already works)* | *0.31 ns, equal* | *0, equal* |

So value classes deliver, **exactly where escape analysis fails today**:

- **Memory:** −40 bytes per struct/element traversed → **0**. For CarSales
  `object` that is ~54 KB/iter of reader allocation removed (~47% of its heap
  churn, per JFR) — the part the arena leaves on the table.
- **Speed:** **~2.7×** on the per-element descent/iteration cost; and by removing
  the allocation it removes the young-GC pressure that dominates the
  reader-bound cases.
- Where EA already scalarizes readers (small, fully-inlined, non-escaping loops)
  there is **no** change — value classes only help the cases that allocate today.

Source locations flagged as Valhalla candidates (grep `PROJECT VALHALLA`):
`StructReader` (class doc) and `ListReader._getStructElement` (the hot allocation
site). `StructBuilder`, `ListReader`, `ListBuilder`, `SegmentReader` share the
shape and the treatment.

### Complementary, on different clocks

| allocation source | fix | JDK | status | measured |
| --- | --- | --- | --- | --- |
| message segment buffers | `ArenaAllocator` (FFM) | **25 (now)** | shipped here | Eval −38…40%, heap −76% |
| per-element readers | `value class` readers (JEP 401) | **28+** | preview, EA-build only | 40 B→0/elem, ~2.7× |
| `List(Struct)` reader arrays | null-restricted flat arrays | post-28 | draft | (not yet measurable) |
| string transcode copy | elide `Text.toString` copy | 25 (now) | not done | — |

FFM (storage) and Valhalla (readers) target **different allocation sources**, so
they stack: Eval is captured by the arena today; CarSales is waiting on Valhalla;
a future combined runtime gets both. Nothing about adopting `ArenaAllocator` now
blocks the value-class migration later — readers are constructed through
factories, so flipping them to value classes is localized to the generated
`…Reader` types and `StructReader`/`ListReader`.

## Recommendation

1. **Adopt `ArenaAllocator` (opt-in per message) on JDK 25** — real win on
   traversal/allocation-bound messages, off-heap deterministic free, no accessor
   changes, tests green.
2. **Do not swap scalar access to `MemorySegment`** on JDK 25 — no speed upside,
   a scattered-read regression. Revisit only for >2 GB single segments (the one
   thing `ByteBuffer` can't address).
3. **Plan the value-class reader migration for JDK 28+** — it removes the
   reader-allocation ceiling the arena can't (measured 40 B→0/elem, ~2.7×), and
   the codebase's factory indirection makes it a localized change.

## Reproducing (Valhalla parts need the JEP 401 EA JDK)

```
# JDK 25 (FFM arena):
bash benchmark/ffm/run.sh consolidated $JDK25            # heap vs *-arena sweep
javac benchmark/ffm/CountedLoopAccess.java && java --enable-native-access=ALL-UNNAMED -cp benchmark/ffm CountedLoopAccess
javac benchmark/ffm/MicroAccessNative.java && java --enable-native-access=ALL-UNNAMED -cp benchmark/ffm MicroAccessNative

# JEP 401 EA JDK (value classes), e.g. jdk.java.net/valhalla:
$VJDK/bin/javac --release 27 --enable-preview -d benchmark/ffm benchmark/ffm/MicroValhalla.java
$VJDK/bin/java --enable-preview \
  -XX:CompileCommand=dontinline,MicroValhalla::makeId \
  -XX:CompileCommand=dontinline,MicroValhalla::makeVal -cp benchmark/ffm MicroValhalla
```
