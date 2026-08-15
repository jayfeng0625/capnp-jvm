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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class FfmSerializePackedTest {

    @Test
    public void testSimplePacking() {
        assertPacksTo(new byte[0], new byte[0]);

        assertPacksTo(new byte[]{0,0,0,0,0,0,0,0}, new byte[]{0,0});

        assertPacksTo(new byte[]{0,0,12,0,0,34,0,0}, new byte[]{0x24,12,34});

        assertPacksTo(new byte[]{1,3,2,4,5,7,6,8}, new byte[]{(byte)0xff,1,3,2,4,5,7,6,8,0});

        assertPacksTo(new byte[]{0,0,0,0,0,0,0,0, 1,3,2,4,5,7,6,8},
                new byte[]{0,0,(byte)0xff,1,3,2,4,5,7,6,8,0});

        assertPacksTo(new byte[]{0,0,12,0,0,34,0,0, 1,3,2,4,5,7,6,8},
                new byte[]{0x24, 12, 34, (byte)0xff,1,3,2,4,5,7,6,8,0});

        assertPacksTo(new byte[]{1,3,2,4,5,7,6,8, 8,6,7,4,5,2,3,1},
                new byte[]{(byte)0xff,1,3,2,4,5,7,6,8,1,8,6,7,4,5,2,3,1});

        assertPacksTo(new byte[]{1,2,3,4,5,6,7,8, 1,2,3,4,5,6,7,8, 1,2,3,4,5,6,7,8, 1,2,3,4,5,6,7,8, 0,2,4,0,9,0,5,1},
                new byte[]{(byte)0xff,1,2,3,4,5,6,7,8, 3, 1,2,3,4,5,6,7,8, 1,2,3,4,5,6,7,8, 1,2,3,4,5,6,7,8,
                        (byte)0xd6,2,4,9,5,1});

        assertPacksTo(new byte[]{1,2,3,4,5,6,7,8, 1,2,3,4,5,6,7,8, 6,2,4,3,9,0,5,1, 1,2,3,4,5,6,7,8, 0,2,4,0,9,0,5,1},
                new byte[]{(byte)0xff,1,2,3,4,5,6,7,8, 3, 1,2,3,4,5,6,7,8, 6,2,4,3,9,0,5,1, 1,2,3,4,5,6,7,8,
                        (byte)0xd6,2,4,9,5,1});

        assertPacksTo(new byte[]{8,0,100,6,0,1,1,2, 0,0,0,0,0,0,0,0, 0,0,0,0,0,0,0,0, 0,0,0,0,0,0,0,0, 0,0,1,0,2,0,3,1},
                new byte[]{(byte)0xed,8,100,6,1,1,2, 0,2, (byte)0xd4,1,2,3,1});

        assertPacksTo(new byte[]{0,0,0,0,2,0,0,0, 0,0,0,0,0,0,1,0, 0,0,0,0,0,0,0,0},
                new byte[]{0x10,2, 0x40,1, 0,0});

        assertPacksTo(new byte[8 * 200], new byte[]{0, (byte)199});

        byte[] ones = new byte[8 * 200];
        Arrays.fill(ones, (byte)1);
        byte[] packedOnes = new byte[10 + 8 * 199];
        Arrays.fill(packedOnes, (byte)1);
        packedOnes[0] = (byte)255;
        packedOnes[9] = (byte)199;
        assertPacksTo(ones,packedOnes);
    }

    private void assertPacksTo(byte[] unpacked, byte[] packed) {
        try (Arena arena = Arena.ofConfined()) {
            // pack from native input into a native output segment
            {
                MemorySegment output = arena.allocate(Math.max(packed.length, 1), Constants.BYTES_PER_WORD);
                MemorySegmentOutputStream writer =
                    new MemorySegmentOutputStream(output.asSlice(0, packed.length));
                FfmPackedOutputStream packedOutputStream = new FfmPackedOutputStream(writer);

                MemorySegment input = arena.allocate(Math.max(unpacked.length, 1), Constants.BYTES_PER_WORD);
                MemorySegment.copy(unpacked, 0, input, ValueLayout.JAVA_BYTE, 0, unpacked.length);
                try {
                    packedOutputStream.write(input.asSlice(0, unpacked.length).asByteBuffer());
                } catch (IOException e) {
                    fail("Failed writing to FfmPackedOutputStream");
                }

                assertTrue(Arrays.equals(
                    output.asSlice(0, packed.length).toArray(ValueLayout.JAVA_BYTE), packed));
            }

            // unpack from native input into a native output buffer
            {
                MemorySegment input = arena.allocate(Math.max(packed.length, 1), Constants.BYTES_PER_WORD);
                MemorySegment.copy(packed, 0, input, ValueLayout.JAVA_BYTE, 0, packed.length);
                MemorySegmentInputStream reader =
                    new MemorySegmentInputStream(input.asSlice(0, packed.length));
                FfmPackedInputStream stream = new FfmPackedInputStream(reader);

                MemorySegment output = arena.allocate(Math.max(unpacked.length, 1), Constants.BYTES_PER_WORD);
                int n = 0;
                try {
                    n = stream.read(output.asSlice(0, unpacked.length).asByteBuffer());
                } catch (IOException e) {
                    fail("Failed reading from FfmPackedInputStream");
                }

                assertEquals(n, unpacked.length);
                assertTrue(Arrays.equals(
                    output.asSlice(0, unpacked.length).toArray(ValueLayout.JAVA_BYTE), unpacked));
            }
        }
    }

    @Test
    @Timeout(value = 1000, unit = TimeUnit.MILLISECONDS)
    public void read_shouldThrowDecodingExceptionOnEmptyMemorySegmentInputStream() throws IOException {
        byte[] emptyByteArray = {};
        assertThrows(DecodeException.class,
            () -> FfmSerializePacked.read(
                new MemorySegmentInputStream(MemorySegment.ofArray(emptyByteArray)),
                ReaderOptions.DEFAULT_READER_OPTIONS));
    }

    @Test
    @Timeout(value = 1000, unit = TimeUnit.MILLISECONDS)
    public void read_shouldThrowDecodingExceptionWhenTryingToReadMoreThanAvailableFromMemorySegmentInputStream() throws IOException {
        byte[] bytes = {17, 0, 127, 0, 0, 0, 0}; //segment0 size of 127 words, which is way larger than the tiny 7 byte input
        assertThrows(DecodeException.class,
            () -> FfmSerializePacked.read(
                new MemorySegmentInputStream(MemorySegment.ofArray(bytes)),
                ReaderOptions.DEFAULT_READER_OPTIONS));
    }

    @Test
    @Timeout(value = 1000, unit = TimeUnit.MILLISECONDS)
    public void read_shouldRejectZeroRunSpanningSegmentBoundary() {
        // A 2-segment message (seg0 = 1 zero word, seg1 = 2 zero words) whose
        // body is packed as a single zero-run spanning both segments. The
        // packed format requires runs to end cleanly on segment boundaries,
        // and the ByteBuffer module rejects this stream with a
        // DecodeException; the FFM module must reject it identically.
        byte[] malformed = {
            0x11, 1, 1,        // header word 0: segment count-1 = 1, seg0 size = 1
            0x01, 2,           // header word 1: seg1 size = 2 (+ padding)
            0x00, 0x02         // body: zero word + run of 2 more = 24 bytes,
                               // overrunning segment 0's one-word window
        };
        assertThrows(DecodeException.class,
            () -> FfmSerializePacked.read(
                new MemorySegmentInputStream(MemorySegment.ofArray(malformed)),
                ReaderOptions.DEFAULT_READER_OPTIONS));
    }

    /**
     * Straightforward reference implementation of the packed encoding over
     * plain arrays, used as an independent oracle for the optimized writer.
     * Mirrors the wire rules exactly: per-word tag + nonzero bytes, zero-run
     * counts after 0x00 tags, uncompressed-run counts after 0xff tags with
     * the fewer-than-two-zero-bytes run heuristic, both runs capped at 255
     * words.
     */
    private static byte[] referencePack(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int p = 0;
        int end = in.length;
        while (p < end) {
            int tag = 0;
            for (int i = 0; i < 8; ++i) {
                if (in[p + i] != 0) tag |= 1 << i;
            }
            out.write(tag);
            for (int i = 0; i < 8; ++i) {
                if (in[p + i] != 0) out.write(in[p + i]);
            }
            p += 8;

            if (tag == 0) {
                int runStart = p;
                int limit = Math.min(end, p + 255 * 8);
                while (p < limit && isZeroWord(in, p)) {
                    p += 8;
                }
                out.write((p - runStart) / 8);
            } else if (tag == 0xff) {
                int runStart = p;
                int limit = Math.min(end, p + 255 * 8);
                while (p < limit) {
                    int zeros = 0;
                    for (int i = 0; i < 8; ++i) {
                        if (in[p + i] == 0) zeros++;
                    }
                    p += 8;
                    if (zeros >= 2) {
                        p -= 8;
                        break;
                    }
                }
                int count = p - runStart;
                out.write(count / 8);
                out.write(in, runStart, count);
            }
        }
        return out.toByteArray();
    }

    private static boolean isZeroWord(byte[] in, int p) {
        for (int i = 0; i < 8; ++i) {
            if (in[p + i] != 0) return false;
        }
        return true;
    }

    @Test
    public void testTagOf() {
        assertEquals(0x00, FfmPackedOutputStream.tagOf(0x0000000000000000L));
        assertEquals(0xFF, FfmPackedOutputStream.tagOf(0xFFFFFFFFFFFFFFFFL));
        assertEquals(0x01, FfmPackedOutputStream.tagOf(0x00000000000000FFL));
        assertEquals(0x80, FfmPackedOutputStream.tagOf(0x8000000000000000L));
        // 0x00 byte followed by 0x01: the pattern where the naive SWAR
        // zero-detect ((v - 0x01..) & ~v & 0x80..) reports a false positive.
        assertEquals(0x02, FfmPackedOutputStream.tagOf(0x0000000000000100L));
        // 0x80 bytes must not read as zero.
        assertEquals(0xFF, FfmPackedOutputStream.tagOf(0x8080808080808080L));
        // Mixed: bytes (LE order) 01 00 80 00 FF 00 7F 00 -> bits 0,2,4,6.
        assertEquals(0x55, FfmPackedOutputStream.tagOf(0x007F00FF00800001L));

        // Exhaustive per-bit check against a byte-wise oracle.
        Random rng = new Random(7);
        for (int i = 0; i < 10_000; ++i) {
            long word = rng.nextLong() & rng.nextLong() & rng.nextLong(); // bias toward zero bytes
            int expected = 0;
            for (int b = 0; b < 8; ++b) {
                if (((word >>> (8 * b)) & 0xFF) != 0) expected |= 1 << b;
            }
            assertEquals(expected, FfmPackedOutputStream.tagOf(word), Long.toHexString(word));
        }
    }

    @Test
    public void testIsFastBitGather() {
        // x86-64: the PEXT / PDEP intrinsic backing Long.compress needs BMI2.
        assertTrue(FfmPackedOutputStream.isFastBitGather("amd64", true, 0));
        assertTrue(FfmPackedOutputStream.isFastBitGather("x86_64", true, 0));
        assertFalse(FfmPackedOutputStream.isFastBitGather("amd64", false, 0));
        // aarch64: a hardware bit-permute needs SVE2 (UseSVE >= 2). NEON-only
        // and scalar cores fall back, so anything below 2 takes the shift path.
        assertFalse(FfmPackedOutputStream.isFastBitGather("aarch64", false, 0));
        assertFalse(FfmPackedOutputStream.isFastBitGather("aarch64", false, 1));
        assertTrue(FfmPackedOutputStream.isFastBitGather("aarch64", false, 2));
        // Unknown ISA: never assume a fast intrinsic.
        assertFalse(FfmPackedOutputStream.isFastBitGather("riscv64", true, 4));
        assertFalse(FfmPackedOutputStream.isFastBitGather("", false, 0));
    }

    @Test
    public void testCompactNonzeroBytesMatchesCompress() {
        // The shift-based gather (aarch64 path) must produce the same low bytes
        // as the Long.compress intrinsic (x86 path) for every word. Only the
        // low bitCount(tag) bytes are meaningful; the rest are overwritten.
        Random rng = new Random(99);
        for (int i = 0; i < 100_000; ++i) {
            long word = rng.nextLong() & rng.nextLong(); // bias toward zero bytes
            int tag = FfmPackedOutputStream.tagOf(word);
            long byteMask = Long.expand(tag, 0x0101010101010101L) * 0xFFL;
            long expected = Long.compress(word, byteMask);
            long actual = FfmPackedOutputStream.compactNonzeroBytes(word, tag);
            int meaningful = Integer.bitCount(tag);
            long keep = meaningful >= 8 ? -1L : (1L << (8 * meaningful)) - 1;
            assertEquals(expected & keep, actual & keep, Long.toHexString(word));
        }
    }

    @Test
    public void testWriterMatchesReferenceOnRandomInput() throws IOException {
        Random rng = new Random(42);
        try (Arena arena = Arena.ofConfined()) {
            for (int trial = 0; trial < 300; ++trial) {
                // Sizes past 255 words exercise both run caps; densities from
                // all-zero to all-dense exercise every tag path.
                int words = 1 + rng.nextInt(600);
                int density = rng.nextInt(101);
                byte[] input = new byte[words * 8];
                for (int i = 0; i < input.length; ++i) {
                    if (rng.nextInt(100) < density) {
                        input[i] = (byte) (1 + rng.nextInt(255));
                    }
                }

                byte[] expected = referencePack(input);

                // Pack from a native little-endian source on even trials and
                // from a heap big-endian buffer on odd ones: the writer's
                // output must not depend on the source buffer's byte order.
                ByteBuffer source;
                if (trial % 2 == 0) {
                    MemorySegment segment = arena.allocate(input.length, Constants.BYTES_PER_WORD);
                    MemorySegment.copy(input, 0, segment, ValueLayout.JAVA_BYTE, 0, input.length);
                    source = segment.asByteBuffer();
                } else {
                    source = ByteBuffer.wrap(input.clone());
                }

                MemorySegment outputSegment =
                    arena.allocate(2L * input.length + 64, Constants.BYTES_PER_WORD);
                MemorySegmentOutputStream writer = new MemorySegmentOutputStream(outputSegment);
                new FfmPackedOutputStream(writer).write(source);

                int packedLength = writer.buf.position();
                assertEquals(expected.length, packedLength, "trial " + trial);
                assertTrue(Arrays.equals(
                    outputSegment.asSlice(0, packedLength).toArray(ValueLayout.JAVA_BYTE), expected),
                    "trial " + trial);

                // And the packed bytes must unpack to the original input.
                MemorySegmentInputStream reader =
                    new MemorySegmentInputStream(outputSegment.asSlice(0, packedLength));
                MemorySegment unpacked = arena.allocate(Math.max(input.length, 8), Constants.BYTES_PER_WORD);
                int n = new FfmPackedInputStream(reader)
                    .read(unpacked.asSlice(0, input.length).asByteBuffer());
                assertEquals(input.length, n, "trial " + trial);
                assertTrue(Arrays.equals(
                    unpacked.asSlice(0, input.length).toArray(ValueLayout.JAVA_BYTE), input),
                    "trial " + trial);
            }
        }
    }

    @Test
    public void testRoundTripThroughFfmBufferedStreams() throws IOException {
        String greeting = "Hello, packed native world!";
        Path file = Files.createTempFile("capnp-ffm-packed", ".bin");
        try {
            try (ArenaAllocator allocator = new ArenaAllocator();
                 FileChannel channel = FileChannel.open(
                     file, StandardOpenOption.WRITE)) {
                MessageBuilder message = new MessageBuilder(allocator);
                message.setRoot(Text.factory, new Text.Reader(greeting));
                FfmSerializePacked.writeToUnbuffered(channel, message);
            }

            try (FileChannel channel = FileChannel.open(
                     file, StandardOpenOption.READ);
                 FfmMessage message = FfmSerializePacked.readFromUnbuffered(channel)) {
                assertEquals(greeting, message.getRoot(Text.factory).toString());
            }
        } finally {
            Files.delete(file);
        }
    }
}
