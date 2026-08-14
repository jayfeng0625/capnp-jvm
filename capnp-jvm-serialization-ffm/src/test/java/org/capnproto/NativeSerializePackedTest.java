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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class NativeSerializePackedTest {

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
                NativePackedOutputStream packedOutputStream = new NativePackedOutputStream(writer);

                MemorySegment input = arena.allocate(Math.max(unpacked.length, 1), Constants.BYTES_PER_WORD);
                MemorySegment.copy(unpacked, 0, input, ValueLayout.JAVA_BYTE, 0, unpacked.length);
                try {
                    packedOutputStream.write(input.asSlice(0, unpacked.length).asByteBuffer());
                } catch (IOException e) {
                    fail("Failed writing to NativePackedOutputStream");
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
                NativePackedInputStream stream = new NativePackedInputStream(reader);

                MemorySegment output = arena.allocate(Math.max(unpacked.length, 1), Constants.BYTES_PER_WORD);
                int n = 0;
                try {
                    n = stream.read(output.asSlice(0, unpacked.length).asByteBuffer());
                } catch (IOException e) {
                    fail("Failed reading from NativePackedInputStream");
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
            () -> NativeSerializePacked.read(
                new MemorySegmentInputStream(MemorySegment.ofArray(emptyByteArray)),
                ReaderOptions.DEFAULT_READER_OPTIONS));
    }

    @Test
    @Timeout(value = 1000, unit = TimeUnit.MILLISECONDS)
    public void read_shouldThrowDecodingExceptionWhenTryingToReadMoreThanAvailableFromMemorySegmentInputStream() throws IOException {
        byte[] bytes = {17, 0, 127, 0, 0, 0, 0}; //segment0 size of 127 words, which is way larger than the tiny 7 byte input
        assertThrows(DecodeException.class,
            () -> NativeSerializePacked.read(
                new MemorySegmentInputStream(MemorySegment.ofArray(bytes)),
                ReaderOptions.DEFAULT_READER_OPTIONS));
    }

    @Test
    public void testRoundTripThroughNativeBufferedStreams() throws IOException {
        String greeting = "Hello, packed native world!";
        Path file = Files.createTempFile("capnp-ffm-packed", ".bin");
        try {
            try (ArenaAllocator allocator = new ArenaAllocator();
                 FileChannel channel = FileChannel.open(
                     file, StandardOpenOption.WRITE)) {
                MessageBuilder message = new MessageBuilder(allocator);
                message.setRoot(Text.factory, new Text.Reader(greeting));
                NativeSerializePacked.writeToUnbuffered(channel, message);
            }

            try (FileChannel channel = FileChannel.open(
                     file, StandardOpenOption.READ);
                 NativeMessage message = NativeSerializePacked.readFromUnbuffered(channel)) {
                assertEquals(greeting, message.getRoot(Text.factory).toString());
            }
        } finally {
            Files.delete(file);
        }
    }
}
