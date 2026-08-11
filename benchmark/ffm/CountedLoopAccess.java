import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

// Gives the research doc's core thesis its best shot: a List(Struct)-shaped
// MONOTONIC counted loop (i = 0..count) walking fixed-stride records, where C2
// should be able to predicate/hoist the segment bounds check. Compares direct
// ByteBuffer vs a hoisted-local MemorySegment (LE unaligned layouts), reading
// the cap'n-proto struct field density (8 int + 4 long per 64-byte record).
//
// Run with:  java --enable-native-access=ALL-UNNAMED CountedLoopAccess
public class CountedLoopAccess {
    static final ValueLayout.OfInt  INT_LE  = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfLong LONG_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    static final int COUNT  = 4000;   // records in the list
    static final int STRIDE = 64;     // bytes per record

    // Full struct read: 8 int + 4 long, monotonic counted loop over the list.
    static long bbList(ByteBuffer b) {
        long acc = 0;
        for (int i = 0; i < COUNT; i++) {
            int o = i * STRIDE;
            acc += b.getInt(o) + b.getInt(o+4) + b.getInt(o+8) + b.getInt(o+12)
                 + b.getInt(o+16) + b.getInt(o+20) + b.getInt(o+24) + b.getInt(o+28)
                 + b.getLong(o+32) + b.getLong(o+40) + b.getLong(o+48) + b.getLong(o+56);
        }
        return acc;
    }
    static long msList(MemorySegment m) {   // hoisted local segment
        long acc = 0;
        for (int i = 0; i < COUNT; i++) {
            long o = (long) i * STRIDE;
            acc += m.get(INT_LE, o) + m.get(INT_LE, o+4) + m.get(INT_LE, o+8) + m.get(INT_LE, o+12)
                 + m.get(INT_LE, o+16) + m.get(INT_LE, o+20) + m.get(INT_LE, o+24) + m.get(INT_LE, o+28)
                 + m.get(LONG_LE, o+32) + m.get(LONG_LE, o+40) + m.get(LONG_LE, o+48) + m.get(LONG_LE, o+56);
        }
        return acc;
    }
    // Single-field-per-record traversal (the classic BCE-hoistable strided walk).
    static long bbSum(ByteBuffer b)   { long a=0; for (int i=0;i<COUNT;i++) a+=b.getLong(i*STRIDE); return a; }
    static long msSum(MemorySegment m){ long a=0; for (int i=0;i<COUNT;i++) a+=m.get(LONG_LE,(long)i*STRIDE); return a; }

    interface Pass { long run(); }
    static void bench(String name, Pass p) {
        for (int i = 0; i < 15000; i++) p.run();
        int reps = 9; long[] t = new long[reps]; long sink = 0;
        for (int r = 0; r < reps; r++) {
            long t0 = System.nanoTime();
            for (int k = 0; k < 3000; k++) sink += p.run();
            t[r] = System.nanoTime() - t0;
        }
        long[] s = t.clone(); Arrays.sort(s);
        System.out.printf("%-20s median=%6.2f ns/record  (sink=%d)%n", name, s[reps/2] / (3000.0 * COUNT), sink);
    }

    public static void main(String[] args) {
        try (Arena arena = Arena.ofShared()) {
            MemorySegment seg = arena.allocate((long) COUNT * STRIDE, 8);
            for (long i = 0; i < seg.byteSize(); i++) seg.set(ValueLayout.JAVA_BYTE, i, (byte)(i*31+7));
            ByteBuffer bb = seg.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            for (int round = 0; round < 2; round++) {
                System.out.println("--- round " + round + " (full struct: 12 reads/record) ---");
                bench("BB full",  () -> bbList(bb));
                bench("MS full",  () -> msList(seg));
                System.out.println("--- round " + round + " (single long/record) ---");
                bench("BB sum",   () -> bbSum(bb));
                bench("MS sum",   () -> msSum(seg));
            }
        }
    }
}
