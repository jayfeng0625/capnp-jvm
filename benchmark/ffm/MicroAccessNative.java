import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

// Validates finding #3 (Cimadamore/Minborg): does rebuilding the segment view
// per access via MemorySegment.ofAddress(base).reinterpret(size) let C2 drop the
// spatial/temporal checks that a field-sourced MemorySegment cannot, for a
// cap'n-proto-shaped *scattered* access pattern (a handful of reads per
// "struct", not one tight loop over the whole segment)?
//
// Run with:  java --enable-native-access=ALL-UNNAMED MicroAccessNative
public class MicroAccessNative {
    static final ValueLayout.OfInt  INT_LE  = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfLong LONG_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    static final int SIZE = 4096;     // segment bytes (compile-time constant)
    static final int STRUCTS = 4000;  // "structs" read per pass

    // One struct's worth of reads (8 int + 4 long), scattered within a struct.
    static long structField(MemorySegment m, int base) {
        return m.get(INT_LE, base) + m.get(INT_LE, base+4) + m.get(INT_LE, base+8) + m.get(INT_LE, base+12)
             + m.get(INT_LE, base+16) + m.get(INT_LE, base+20) + m.get(INT_LE, base+24) + m.get(INT_LE, base+28)
             + m.get(LONG_LE, base+32) + m.get(LONG_LE, base+40) + m.get(LONG_LE, base+48) + m.get(LONG_LE, base+56);
    }
    // Same, but each ACCESS reconstructs the view from a raw address + constant size.
    static long structReinterpretConst(long addr, int base) {
        return getI(addr,base) + getI(addr,base+4) + getI(addr,base+8) + getI(addr,base+12)
             + getI(addr,base+16) + getI(addr,base+20) + getI(addr,base+24) + getI(addr,base+28)
             + getL(addr,base+32) + getL(addr,base+40) + getL(addr,base+48) + getL(addr,base+56);
    }
    static int  getI(long addr, int off) { return MemorySegment.ofAddress(addr).reinterpret(SIZE).get(INT_LE, off); }
    static long getL(long addr, int off) { return MemorySegment.ofAddress(addr).reinterpret(SIZE).get(LONG_LE, off); }
    // Same, but size comes from a variable (mirrors a per-segment size field).
    static long structReinterpretVar(long addr, long size, int base) {
        return getIv(addr,size,base) + getIv(addr,size,base+4) + getIv(addr,size,base+8) + getIv(addr,size,base+12)
             + getIv(addr,size,base+16) + getIv(addr,size,base+20) + getIv(addr,size,base+24) + getIv(addr,size,base+28)
             + getLv(addr,size,base+32) + getLv(addr,size,base+40) + getLv(addr,size,base+48) + getLv(addr,size,base+56);
    }
    static int  getIv(long addr, long size, int off) { return MemorySegment.ofAddress(addr).reinterpret(size).get(INT_LE, off); }
    static long getLv(long addr, long size, int off) { return MemorySegment.ofAddress(addr).reinterpret(size).get(LONG_LE, off); }

    static long bbStruct(ByteBuffer b, int base) {
        return b.getInt(base) + b.getInt(base+4) + b.getInt(base+8) + b.getInt(base+12)
             + b.getInt(base+16) + b.getInt(base+20) + b.getInt(base+24) + b.getInt(base+28)
             + b.getLong(base+32) + b.getLong(base+40) + b.getLong(base+48) + b.getLong(base+56);
    }

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
        System.out.printf("%-22s median=%6.2f ns/struct  (sink=%d)%n", name, s[reps/2] / (3000.0 * STRUCTS), sink);
    }

    public static void main(String[] args) {
        try (Arena arena = Arena.ofShared()) {              // stable native address, stays alive
            MemorySegment seg = arena.allocate(SIZE, 8);
            for (int i = 0; i < SIZE; i++) seg.set(ValueLayout.JAVA_BYTE, i, (byte)(i*31+7));
            final long addr = seg.address();
            final long size = seg.byteSize();
            ByteBuffer bb = seg.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);

            for (int round = 0; round < 2; round++) {
                System.out.println("--- round " + round + " ---");
                bench("direct ByteBuffer",     () -> { long a=0; for (int i=0;i<STRUCTS;i++) a+=bbStruct(bb, (i*64)%(SIZE-64)); return a; });
                bench("MS field .get",         () -> { long a=0; for (int i=0;i<STRUCTS;i++) a+=structField(seg, (i*64)%(SIZE-64)); return a; });
                bench("MS reinterpret const",  () -> { long a=0; for (int i=0;i<STRUCTS;i++) a+=structReinterpretConst(addr, (i*64)%(SIZE-64)); return a; });
                bench("MS reinterpret var",    () -> { long a=0; for (int i=0;i<STRUCTS;i++) a+=structReinterpretVar(addr, size, (i*64)%(SIZE-64)); return a; });
            }
        }
    }
}
