import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

// Isolates per-access scalar read cost: ByteBuffer vs several FFM variants,
// on the SAME heap byte[]. Little-endian, unaligned, mixed short/int/long
// reads to mimic the struct-field access mix.
public class MicroAccess {
    static final ValueLayout.OfShort SHORT_LE = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfInt   INT_LE   = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfLong  LONG_LE  = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    // Plain native-order (this box is little-endian, so semantically identical here)
    static final ValueLayout.OfShort SHORT_N = ValueLayout.JAVA_SHORT_UNALIGNED;
    static final ValueLayout.OfInt   INT_N   = ValueLayout.JAVA_INT_UNALIGNED;
    static final ValueLayout.OfLong  LONG_N  = ValueLayout.JAVA_LONG_UNALIGNED;
    static final VarHandle VH_SHORT = SHORT_LE.varHandle();
    static final VarHandle VH_INT   = INT_LE.varHandle();
    static final VarHandle VH_LONG  = LONG_LE.varHandle();

    static final int SIZE = 4096;              // bytes
    static final int READS = 4096;             // reads per pass

    static long bb(ByteBuffer b) {
        long acc = 0;
        for (int i = 0; i < READS; i += 16) {
            acc += b.getShort(i);
            acc += b.getInt(i + 2);
            acc += b.getLong(i + 6);
        }
        return acc;
    }
    static long msLE(MemorySegment m) {
        long acc = 0;
        for (int i = 0; i < READS; i += 16) {
            acc += m.get(SHORT_LE, i);
            acc += m.get(INT_LE, i + 2);
            acc += m.get(LONG_LE, i + 6);
        }
        return acc;
    }
    static long msN(MemorySegment m) {
        long acc = 0;
        for (int i = 0; i < READS; i += 16) {
            acc += m.get(SHORT_N, i);
            acc += m.get(INT_N, i + 2);
            acc += m.get(LONG_N, i + 6);
        }
        return acc;
    }
    static long vh(MemorySegment m) {
        long acc = 0;
        for (int i = 0; i < READS; i += 16) {
            acc += (short) VH_SHORT.get(m, (long) i);
            acc += (int)   VH_INT.get(m, (long) (i + 2));
            acc += (long)  VH_LONG.get(m, (long) (i + 6));
        }
        return acc;
    }

    interface Pass { long run(); }

    static void bench(String name, Pass p) {
        for (int i = 0; i < 20000; i++) p.run();     // warmup
        int reps = 9;
        long[] t = new long[reps];
        long sink = 0;
        for (int r = 0; r < reps; r++) {
            long t0 = System.nanoTime();
            for (int k = 0; k < 20000; k++) sink += p.run();
            t[r] = System.nanoTime() - t0;
        }
        long[] s = t.clone(); Arrays.sort(s);
        System.out.printf("%-14s median=%6.2f ns/pass  (sink=%d)%n", name, s[reps/2] / 20000.0, sink);
    }

    public static void main(String[] args) {
        byte[] arr = new byte[SIZE];
        for (int i = 0; i < SIZE; i++) arr[i] = (byte) (i * 31 + 7);
        ByteBuffer bb = ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN);
        MemorySegment msArray  = MemorySegment.ofArray(arr);
        MemorySegment msBuffer = MemorySegment.ofBuffer(ByteBuffer.wrap(arr));

        bench("ByteBuffer",   () -> bb(bb));
        bench("MS/ofArray/LE",() -> msLE(msArray));
        bench("MS/ofArray/N", () -> msN(msArray));
        bench("MS/ofBuffer/LE",() -> msLE(msBuffer));
        bench("MS/VarHandle",  () -> vh(msArray));
        // second round to reduce ordering effects
        bench("ByteBuffer",   () -> bb(bb));
        bench("MS/ofArray/LE",() -> msLE(msArray));
        bench("MS/VarHandle",  () -> vh(msArray));
    }
}
