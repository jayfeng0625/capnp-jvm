import java.lang.management.ManagementFactory;
import java.util.Arrays;

// Models capnproto-java's reader-allocation cost and what Project Valhalla
// (JEP 401 value classes) would do about it. The reader mirrors StructReader's
// exact field set { segment-ref, int data, int pointers, int dataSize,
// short pointerCount, int nestingLimit } and is constructed once per list
// element through a factory boundary -- exactly ListReader._getStructElement ->
// factory.constructReader(...) -> new StructReader(...).
//
// Run on a JEP 401 JDK:
//   javac --release 27 --enable-preview MicroValhalla.java
//   java  --enable-preview \
//         -XX:CompileCommand=dontinline,MicroValhalla::makeId \
//         -XX:CompileCommand=dontinline,MicroValhalla::makeVal MicroValhalla
// The dontinline commands model the real, non-inlined (bi/megamorphic) factory
// call site, where escape analysis cannot scalarize the identity reader.
public class MicroValhalla {
    static final int N = 2000;                 // list elements per pass
    static final long[] BACK = new long[1024];
    static { for (int i = 0; i < BACK.length; i++) BACK[i] = i * 2654435761L; }

    // ---- identity reader: today's capnproto-java StructReader shape ----
    static final class RId {
        final long[] seg; final int data; final int pointers;
        final int dataSize; final short pointerCount; final int nestingLimit;
        RId(long[] s, int d, int p, int ds, short pc, int nl) {
            seg = s; data = d; pointers = p; dataSize = ds; pointerCount = pc; nestingLimit = nl;
        }
        long read() { return seg[data & 1023] + dataSize; }
    }
    static RId makeId(int i) { return new RId(BACK, i, i + 1, 64, (short) 1, 0x7fffffff); }

    // ---- value reader: same shape as a Valhalla value class ----
    static value class RVal {
        final long[] seg; final int data; final int pointers;
        final int dataSize; final short pointerCount; final int nestingLimit;
        RVal(long[] s, int d, int p, int ds, short pc, int nl) {
            seg = s; data = d; pointers = p; dataSize = ds; pointerCount = pc; nestingLimit = nl;
        }
        long read() { return seg[data & 1023] + dataSize; }
    }
    static RVal makeVal(int i) { return new RVal(BACK, i, i + 1, 64, (short) 1, 0x7fffffff); }

    static long passId()  { long a = 0; for (int i = 0; i < N; i++) a += makeId(i).read();  return a; }
    static long passVal() { long a = 0; for (int i = 0; i < N; i++) a += makeVal(i).read(); return a; }

    interface Pass { long run(); }
    static void bench(String name, Pass p) {
        com.sun.management.ThreadMXBean tmx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        for (int i = 0; i < 30000; i++) p.run();               // warmup
        int reps = 9; long[] t = new long[reps]; long sink = 0;
        long a0 = tmx.getThreadAllocatedBytes(tid);
        for (int r = 0; r < reps; r++) {
            long t0 = System.nanoTime();
            for (int k = 0; k < 5000; k++) sink += p.run();
            t[r] = System.nanoTime() - t0;
        }
        long a1 = tmx.getThreadAllocatedBytes(tid);
        long[] s = t.clone(); Arrays.sort(s);
        System.out.printf("%-18s median=%6.2f ns/element  allocBytes/element=%5.2f  (sink=%d)%n",
            name, s[reps/2] / (5000.0 * N), (double)(a1 - a0) / (9.0 * 5000 * N), sink);
    }

    public static void main(String[] args) {
        for (int round = 0; round < 2; round++) {
            System.out.println("--- round " + round + " ---");
            bench("identity reader", MicroValhalla::passId);
            bench("value reader",    MicroValhalla::passVal);
        }
    }
}
