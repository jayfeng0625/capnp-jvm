// Copyright (c) 2013-2014 Sandstorm Development Group, Inc. and contributors
// Copyright (c) 2026 Jay Feng
// Licensed under the MIT License:
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.

package org.capnproto;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Applies the packed encoding (https://capnproto.org/encoding.html#packing)
 * on the way to a {@link BufferedOutputStream}. Produces the identical wire
 * bytes as the ByteBuffer module's {@code PackedOutputStream}, but packs a
 * word at a time: each word is read once as a long and compacted with
 * register arithmetic, instead of sixteen per-byte buffer accesses — which
 * carry a liveness check each on native (arena-backed) buffers. Pairs with
 * the FFM streams so the codec reads its input straight out of native
 * message segments and packs into a native output buffer.
 */
public final class NativePackedOutputStream implements WritableByteChannel {

    private static final long LOW_BITS = 0x0101010101010101L;
    private static final long HIGH_BITS = 0x8080808080808080L;
    private static final long LOW_SEVEN = 0x7F7F7F7F7F7F7F7FL;
    private static final long MOVE_MASK_MAGIC = 0x0002040810204081L;

    // Long.compress / Long.expand lower to single PEXT / PDEP instructions on
    // x86-64 with BMI2, and to an SVE2 bit-permute on aarch64 when the VM runs
    // with UseSVE >= 2. Everywhere else they run a scalar software fallback, so
    // the shift path (tagOf / compactNonzeroBytes) is faster and avoids the
    // 64-bit multiply too. The gating VM flags are read reflectively so this
    // class keeps its java.base-only floor: on a runtime image without
    // jdk.management the read fails and the ISA decides alone.
    private static final boolean HAS_FAST_BIT_GATHER = probeFastBitGather();

    private static boolean probeFastBitGather() {
        return isFastBitGather(System.getProperty("os.arch", ""),
                               readVmFlag("UseBMI2Instructions") > 0,
                               readVmFlag("UseSVE"));
    }

    /**
     * Whether Long.compress / Long.expand are backed by a hardware bit-gather
     * on `arch`, given the VM's `useBmi2` (the x86 PEXT / PDEP gate) and
     * `useSve` (the aarch64 SVE generation; 2 is the first with a bit-permute).
     */
    static boolean isFastBitGather(String arch, boolean useBmi2, int useSve) {
        return switch (arch) {
            case "amd64", "x86_64" -> useBmi2;
            case "aarch64" -> useSve >= 2;
            default -> false;
        };
    }

    /**
     * The value of a HotSpot VM flag as an int (booleans as 0/1), or -1 when it
     * cannot be read: the flag is absent on this ISA, or jdk.management is not
     * in the runtime image. Reflection keeps this class compiling and linking
     * against java.base alone.
     */
    private static int readVmFlag(String name) {
        try {
            Class<?> factory = Class.forName("java.lang.management.ManagementFactory");
            Class<?> diagnostic = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
            Object bean = factory.getMethod("getPlatformMXBean", Class.class)
                .invoke(null, diagnostic);
            Object option = diagnostic.getMethod("getVMOption", String.class)
                .invoke(bean, name);
            String value = (String) option.getClass().getMethod("getValue").invoke(option);
            return switch (value) {
                case "true" -> 1;
                case "false" -> 0;
                default -> Integer.parseInt(value);
            };
        } catch (Throwable t) {
            return -1;
        }
    }

    final BufferedOutputStream inner;

    public NativePackedOutputStream(BufferedOutputStream output) {
        this.inner = output;
    }

    /**
     * Bit i is set iff byte i (little-endian order) of `word` is nonzero:
     * the word's packing tag. Exact per-byte zero detection (Hacker's
     * Delight 6-2; the shorter {@code (v - 0x01..) & ~v & 0x80..} variant
     * has false positives after a zero byte), then the eight 0x80 flags are
     * gathered into one byte: a carry-free multiply where that is cheap,
     * else a shift-fold that keeps the aarch64 path off the 64-bit multiply.
     */
    static int tagOf(long word) {
        long zeros = ~(((word & LOW_SEVEN) + LOW_SEVEN) | word | LOW_SEVEN);
        if (HAS_FAST_BIT_GATHER) {
            int zeroMask = (int) ((zeros * MOVE_MASK_MAGIC) >>> 56);
            return 0xFF & ~zeroMask;
        }
        long nonzero = ~zeros & HIGH_BITS;   // bit 8i+7 set iff byte i is nonzero
        nonzero >>>= 7;                      // move each flag down to bit 8i
        nonzero &= LOW_BITS;
        nonzero |= nonzero >>> 7;
        nonzero |= nonzero >>> 14;
        nonzero |= nonzero >>> 28;
        return (int) (nonzero & 0xFF);
    }

    /**
     * The nonzero bytes of `word` moved to the low end in order, identical to
     * {@code Long.compress(word, byteMask)}. The shift path for ISAs where
     * {@code Long.compress} has no hardware bit-gather; `tag` drives which
     * bytes advance the write cursor, so the loop carries no data branch.
     */
    static long compactNonzeroBytes(long word, int tag) {
        long packed = 0L;
        int shift = 0;
        for (int i = 0; i < 8; i++) {
            packed |= ((word >>> (i << 3)) & 0xFFL) << shift;
            shift += ((tag >>> i) & 1) << 3;
        }
        return packed;
    }

    /** Number of zero bytes in `word`. */
    private static int zeroByteCount(long word) {
        return Integer.bitCount(0xFF & ~tagOf(word));
    }

    /** Absolute little-endian long store, independent of the buffer's order. */
    private static void putLongLe(ByteBuffer out, int index, long value) {
        if (out.order() != ByteOrder.LITTLE_ENDIAN) {
            value = Long.reverseBytes(value);
        }
        out.putLong(index, value);
    }

    @Override
    public int write(ByteBuffer inBuf) throws IOException {
        int length = inBuf.remaining();
        ByteBuffer out = this.inner.getWriteBuffer();

        ByteBuffer slowBuffer = ByteBuffer.allocate(20);

        // Little-endian view for word-at-a-time reads; shares inBuf's
        // coordinates but not its (caller-defined) byte order.
        ByteBuffer in = inBuf.duplicate().order(ByteOrder.LITTLE_ENDIAN);

        int inPtr = inBuf.position();
        int inEnd = inPtr + length;
        while (inPtr < inEnd) {
            if (out.remaining() < 10) {
                //# Oops, we're out of space. We need at least 10
                //# bytes for the fast path, since we don't
                //# bounds-check on every byte.

                if (out == slowBuffer) {
                    int oldLimit = out.limit();
                    out.limit(out.position());
                    out.rewind();
                    this.inner.write(out);
                    out.limit(oldLimit);
                }

                out = slowBuffer;
                out.rewind();
            }

            int tagPos = out.position();

            long word = in.getLong(inPtr);
            inPtr += 8;

            int tag = tagOf(word);
            out.put(tagPos, (byte) tag);

            //# Compact the nonzero bytes to the low end of the word and
            //# store them with a single bounded write (the >= 10 check above
            //# guarantees 8 bytes of room after the tag). Bytes beyond the
            //# tag's bit count land past `position` and are overwritten by
            //# whatever is emitted next.
            long compacted;
            if (HAS_FAST_BIT_GATHER) {
                long byteMask = Long.expand(tag, LOW_BITS) * 0xFFL;
                compacted = Long.compress(word, byteMask);
            } else {
                compacted = compactNonzeroBytes(word, tag);
            }
            putLongLe(out, tagPos + 1, compacted);
            out.position(tagPos + 1 + Integer.bitCount(tag));

            if (tag == 0) {
                //# An all-zero word is followed by a count of
                //# consecutive zero words (not including the first
                //# one).
                int runStart = inPtr;
                int limit = inEnd;
                if (limit - inPtr > 255 * 8) {
                    limit = inPtr + 255 * 8;
                }
                while(inPtr < limit && in.getLong(inPtr) == 0){
                    inPtr += 8;
                }
                out.put((byte)((inPtr - runStart)/8));

            } else if (tag == 0xff) {
                //# An all-nonzero word is followed by a count of
                //# consecutive uncompressed words, followed by the
                //# uncompressed words themselves.

                //# Count the number of consecutive words in the input
                //# which have no more than a single zero-byte. We look
                //# for at least two zeros because that's the point
                //# where our compression scheme becomes a net win.

                int runStart = inPtr;
                int limit = inEnd;
                if (limit - inPtr > 255 * 8) {
                    limit = inPtr + 255 * 8;
                }

                while (inPtr < limit) {
                    long w = in.getLong(inPtr);
                    inPtr += 8;
                    if (zeroByteCount(w) >= 2) {
                        //# Un-read the word with multiple zeros, since
                        //# we'll want to compress that one.
                        inPtr -= 8;
                        break;
                    }
                }

                int count = inPtr - runStart;
                out.put((byte)(count / 8));

                if (count <= out.remaining()) {
                    //# There's enough space to memcpy.
                    inBuf.position(runStart);
                    ByteBuffer slice = inBuf.slice();
                    slice.limit(count);
                    out.put(slice);
                } else {
                    //# Input overruns the output buffer. We'll give it
                    //# to the output stream in one chunk and let it
                    //# decide what to do.

                    if (out == slowBuffer) {
                        int oldLimit = out.limit();
                        out.limit(out.position());
                        out.rewind();
                        this.inner.write(out);
                        out.limit(oldLimit);
                    }

                    inBuf.position(runStart);
                    ByteBuffer slice = inBuf.slice();
                    slice.limit(count);
                    while(slice.hasRemaining()) {
                        this.inner.write(slice);
                    }

                    out = this.inner.getWriteBuffer();
                }
            }
        }

        if (out == slowBuffer) {
            out.limit(out.position());
            out.rewind();
            this.inner.write(out);
        }

        inBuf.position(inPtr);
        return length;
    }

    @Override
    public void close() throws IOException {
        this.inner.close();
    }

    @Override
    public boolean isOpen() {
        return this.inner.isOpen();
    }
}
