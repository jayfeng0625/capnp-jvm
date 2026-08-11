import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

// Heap vs off-heap, per-"message" allocate -> write -> read -> discard, the
// pattern the no-reuse benchmark exercises. Instrumented with JMX:
//   - heap bytes allocated / message   (com.sun.management.ThreadMXBean)
//   - GC count / time                  (GarbageCollectorMXBean)
//   - direct buffer pool delta         (BufferPoolMXBean "direct")
// Off-heap native (Arena) memory is NOT counted by any of those pools -- it is
// freed deterministically at Arena.close(), which is the whole point.
public class OffHeapJmx {
    static final ValueLayout.OfInt  INT_LE  = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfLong LONG_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    static final int SIZE = 2048;   // bytes per message
    static final int OPS  = 240;    // mixed field ops per message

    static long heapBB() {
        ByteBuffer b = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN);
        long acc = 0;
        for (int i = 0; i < OPS; i++) { int o = (i * 8) % (SIZE - 8); b.putInt(o, i); b.putLong(o, i); }
        for (int i = 0; i < OPS; i++) { int o = (i * 8) % (SIZE - 8); acc += b.getInt(o) + b.getLong(o); }
        return acc;
    }
    static long directBB() {
        ByteBuffer b = ByteBuffer.allocateDirect(SIZE).order(ByteOrder.LITTLE_ENDIAN);
        long acc = 0;
        for (int i = 0; i < OPS; i++) { int o = (i * 8) % (SIZE - 8); b.putInt(o, i); b.putLong(o, i); }
        for (int i = 0; i < OPS; i++) { int o = (i * 8) % (SIZE - 8); acc += b.getInt(o) + b.getLong(o); }
        return acc;
    }
    static long heapMS() {
        MemorySegment m = MemorySegment.ofArray(new byte[SIZE]);
        long acc = 0;
        for (int i = 0; i < OPS; i++) { int o = (i * 8) % (SIZE - 8); m.set(INT_LE, o, i); m.set(LONG_LE, o, i); }
        for (int i = 0; i < OPS; i++) { int o = (i * 8) % (SIZE - 8); acc += m.get(INT_LE, o) + m.get(LONG_LE, o); }
        return acc;
    }
    static long nativeMS() {
        long acc = 0;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment m = a.allocate(SIZE);
            for (int i = 0; i < OPS; i++) { int o = (i * 8) % (SIZE - 8); m.set(INT_LE, o, i); m.set(LONG_LE, o, i); }
            for (int i = 0; i < OPS; i++) { int o = (i * 8) % (SIZE - 8); acc += m.get(INT_LE, o) + m.get(LONG_LE, o); }
        }
        return acc;
    }

    interface Msg { long one(); }

    static long directPool() {
        for (BufferPoolMXBean b : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class))
            if (b.getName().equals("direct")) return b.getMemoryUsed();
        return -1;
    }

    static void measure(String name, Msg m) {
        for (int i = 0; i < 200_000; i++) m.one();        // warmup
        com.sun.management.ThreadMXBean tmx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
        long tid = Thread.currentThread().getId();
        int N = 1_000_000;
        long gc0 = 0, gt0 = 0; for (GarbageCollectorMXBean g : gcs) { gc0 += g.getCollectionCount(); gt0 += g.getCollectionTime(); }
        long a0 = tmx.getThreadAllocatedBytes(tid), d0 = directPool(), t0 = System.nanoTime();
        long sink = 0;
        for (int i = 0; i < N; i++) sink += m.one();
        long t1 = System.nanoTime(), a1 = tmx.getThreadAllocatedBytes(tid), d1 = directPool();
        long gc1 = 0, gt1 = 0; for (GarbageCollectorMXBean g : gcs) { gc1 += g.getCollectionCount(); gt1 += g.getCollectionTime(); }
        System.out.printf("%-12s nsPerMsg=%6.1f  heapBytesPerMsg=%7.1f  gcCount=%4d gcTimeMs=%5d  directPoolDelta=%d  (sink=%d)%n",
            name, (double)(t1 - t0)/N, (double)(a1 - a0)/N, (gc1 - gc0), (gt1 - gt0), (d1 - d0), sink);
    }

    public static void main(String[] args) {
        measure("heap BB",   OffHeapJmx::heapBB);
        measure("heap MS",   OffHeapJmx::heapMS);
        measure("direct BB", OffHeapJmx::directBB);
        measure("native MS", OffHeapJmx::nativeMS);
        System.out.println("--- second round ---");
        measure("heap BB",   OffHeapJmx::heapBB);
        measure("heap MS",   OffHeapJmx::heapMS);
        measure("direct BB", OffHeapJmx::directBB);
        measure("native MS", OffHeapJmx::nativeMS);
    }
}
