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

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FfmSerializeTest {

  /**
   * @param arena: segment `i` contains `i` words each set to `i`
   */
  private void checkSegmentContents(int exampleSegmentCount, ReaderArena arena) {
    assertEquals(arena.segments.size(), exampleSegmentCount);
    for (int i = 0; i < exampleSegmentCount; ++i) {
      SegmentReader segment = arena.segments.get(i);
      LongBuffer segmentWords = segment.buffer.asLongBuffer();

      assertEquals(segmentWords.capacity(), i);
      segmentWords.rewind();
      while (segmentWords.hasRemaining()) {
        assertEquals(segmentWords.get(), i);
      }
    }
  }

  /**
   * @param exampleSegmentCount number of segments
   * @param exampleBytes byte array containing `segmentCount` segments; segment `i` contains `i` words each set to `i`
   */
  private void expectSerializesTo(int exampleSegmentCount, byte[] exampleBytes) throws IOException {
    // ----
    // read via ReadableByteChannel into arena-owned native memory
    try (FfmMessage message = FfmSerialize.read(
             new MemorySegmentInputStream(MemorySegment.ofArray(exampleBytes)))) {
      checkSegmentContents(exampleSegmentCount, message.getReader().arena);

      // write back out into a native segment
      try (Arena outputArena = Arena.ofConfined()) {
        MemorySegment output = outputArena.allocate(exampleBytes.length, Constants.BYTES_PER_WORD);
        FfmSerialize.write(new MemorySegmentOutputStream(output), message.getReader());
        assertArrayEquals(exampleBytes, output.toArray(ValueLayout.JAVA_BYTE));
      }
    }

    // ------
    // parse in place from a MemorySegment, no copies
    {
      MessageReader messageReader = FfmSerialize.read(MemorySegment.ofArray(exampleBytes));
      checkSegmentContents(exampleSegmentCount, messageReader.arena);
    }

    // ------
    // memory-map the message from a file
    {
      Path file = Files.createTempFile("capnp-ffm-serialize", ".bin");
      try {
        Files.write(file, exampleBytes);
        try (FfmMessage message = FfmSerialize.map(file)) {
          checkSegmentContents(exampleSegmentCount, message.getReader().arena);
        }
      } finally {
        Files.delete(file);
      }
    }
  }

  @Test
  public void testSegmentReading() throws IOException {
    // When transmitting over a stream, the following should be sent. All integers are unsigned and little-endian.
    // - (4 bytes) The number of segments, minus one (since there is always at least one segment).
    // - (N * 4 bytes) The size of each segment, in words.
    // - (0 or 4 bytes) Padding up to the next word boundary.
    // - The content of each segment, in order.

    expectSerializesTo(1, new byte[]{
        0, 0, 0, 0, // 1 segment
        0, 0, 0, 0  // Segment 0 contains 0 bytes
        // No padding
        // Segment 0 (empty)
      });

    expectSerializesTo(2, new byte[]{
        1, 0, 0, 0, // 2 segments
        0, 0, 0, 0, // Segment 0 contains 0 words
        1, 0, 0, 0, // Segment 1 contains 1 words
        // Padding
        0, 0, 0, 0,
        // Segment 0 (empty)
        // Segment 1
        1, 0, 0, 0, 0, 0, 0, 0
      });

    expectSerializesTo(3, new byte[] {
        2, 0, 0, 0, // 3 segments
        0, 0, 0, 0, // Segment 0 contains 0 words
        1, 0, 0, 0, // Segment 1 contains 1 words
        2, 0, 0, 0, // Segment 2 contains 2 words
        // No padding
        // Segment 0 (empty)
        // Segment 1
        1, 0, 0, 0, 0, 0, 0, 0,
        // Segment 2
        2, 0, 0, 0, 0, 0, 0, 0,
        2, 0, 0, 0, 0, 0, 0, 0
      });

    expectSerializesTo(4, new byte[]{
        3, 0, 0, 0, // 4 segments
        0, 0, 0, 0, // Segment 0 contains 0 words
        1, 0, 0, 0, // Segment 1 contains 1 words
        2, 0, 0, 0, // Segment 2 contains 2 words
        3, 0, 0, 0, // Segment 3 contains 3 words
        // Padding
        0, 0, 0, 0,
        // Segment 0 (empty)
        // Segment 1
        1, 0, 0, 0, 0, 0, 0, 0,
        // Segment 2
        2, 0, 0, 0, 0, 0, 0, 0,
        2, 0, 0, 0, 0, 0, 0, 0,
        // Segment 3
        3, 0, 0, 0, 0, 0, 0, 0,
        3, 0, 0, 0, 0, 0, 0, 0,
        3, 0, 0, 0, 0, 0, 0, 0
      });
  }

  @Test
  public void testTryRead() throws IOException {
    // `tryRead` returns a present optional when given correct input
    {
      byte[] input = new byte[]{
              0, 0, 0, 0, // 1 segment
              0, 0, 0, 0  // Segment 0 contains 0 bytes
              // No padding
              // Segment 0 (empty)
      };
      Optional<FfmMessage> message =
          FfmSerialize.tryRead(new MemorySegmentInputStream(MemorySegment.ofArray(input)));
      assertTrue(message.isPresent());
      message.get().close();
    }

    // `tryRead` returns an empty optional when given no input
    {
      Optional<FfmMessage> message =
          FfmSerialize.tryRead(new MemorySegmentInputStream(MemorySegment.ofArray(new byte[]{})));
      assertFalse(message.isPresent());
    }

    // `tryRead` throws when given too few bytes to form the first word
    {
      byte[] input = new byte[]{
              0, 0, 0, 0, // 1 segment
              0, 0, 0     // Premature end of stream after 7 bytes
      };
      assertThrows(IOException.class,
          () -> FfmSerialize.tryRead(new MemorySegmentInputStream(MemorySegment.ofArray(input))));
    }
  }

  @Test
  public void testSegment0SizeOverflow() throws IOException {
        byte[] input = {0, 0, 0, 0, -1, -1, -1, -113};
        ReadableByteChannel channel =
            Channels.newChannel(new ByteArrayInputStream(input));
        assertThrows(DecodeException.class, () -> FfmSerialize.read(channel));
  }

  @Test
  public void testSegment1SizeOverflow() throws IOException {
      byte[] input = {
          1, 0, 0, 0, 1, 0, 0, 0,
          -1, -1, -1, -113, 0, 0, 0, 0};
        ReadableByteChannel channel =
            Channels.newChannel(new ByteArrayInputStream(input));
        assertThrows(DecodeException.class, () -> FfmSerialize.read(channel));
  }

  @Test
  public void testTruncatedSegmentThrows() {
      // Segment 0 claims 2 words but only 1 follows.
      byte[] input = {
          0, 0, 0, 0, 2, 0, 0, 0,
          42, 0, 0, 0, 0, 0, 0, 0};
      assertThrows(DecodeException.class,
          () -> FfmSerialize.read(MemorySegment.ofArray(input)));
  }

  @Test
  public void testCloseFreesNativeMemoryDeterministically() throws IOException {
      byte[] input = new byte[]{
          0, 0, 0, 0, // 1 segment
          1, 0, 0, 0, // Segment 0 contains 1 word
          42, 0, 0, 0, 0, 0, 0, 0
      };
      FfmMessage message =
          FfmSerialize.read(new MemorySegmentInputStream(MemorySegment.ofArray(input)));
      SegmentReader segment = message.getReader().arena.segments.get(0);
      assertEquals(42, segment.buffer.getLong(0));

      message.close();

      // The arena is closed: touching the message's memory must throw, not
      // read freed memory.
      assertThrows(IllegalStateException.class, () -> segment.buffer.getLong(0));

      // AutoCloseable convention: a second close must be a no-op, not a
      // throw (e.g. an early manual close inside try-with-resources).
      assertDoesNotThrow(message::close);
  }

  @Test
  public void testRoundTripThroughFileChannel() throws IOException {
      String greeting = "Hello, native world!";
      Path file = Files.createTempFile("capnp-ffm-roundtrip", ".bin");
      try {
          long expectedWords;
          try (ArenaAllocator allocator = new ArenaAllocator()) {
              MessageBuilder message = new MessageBuilder(allocator);
              message.setRoot(Text.factory, new Text.Reader(greeting));

              expectedWords = FfmSerialize.computeSerializedSizeInWords(message);

              // FileChannel is a GatheringByteChannel: this exercises the
              // vectored, all-native write path.
              try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                  FfmSerialize.write(channel, message);
              }
          }

          assertEquals(expectedWords * Constants.BYTES_PER_WORD, Files.size(file));

          try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
               FfmMessage message = FfmSerialize.read(channel)) {
              assertEquals(greeting, message.getRoot(Text.factory).toString());
          }

          // The same bytes, memory-mapped instead of read — and consumed
          // through the byte-view-first Text API: no String is constructed,
          // no text bytes are copied out of the mapped pages.
          try (FfmMessage message = FfmSerialize.map(file)) {
              Text.Reader text = message.getRoot(Text.factory);
              assertTrue(text.contentEquals(greeting));
              assertTrue(text.contains("native"));
              assertEquals(-1, text.indexOf("absent"));
              assertEquals(greeting.length(), text.size()); // ASCII: bytes == chars
              assertTrue(text.asByteBuffer().isReadOnly());
              assertEquals(greeting, text.toString()); // String only on demand
          }
      } finally {
          Files.delete(file);
      }
  }

    @Test
    @Disabled("Ignored by default because the huge array used in the test results in a long execution")
    public void computeSerializedSizeInWordsShouldNotOverflowOnLargeSegmentCounts() {
        ByteBuffer dummySegmentBuffer = ByteBuffer.allocate(0);
        ByteBuffer[] segments = new ByteBuffer[Integer.MAX_VALUE / 2];
        Arrays.fill(segments, dummySegmentBuffer);
        assertEquals(FfmSerialize.computeSerializedSizeInWords(segments), (segments.length * 4L + 4) / Constants.BYTES_PER_WORD);
    }
}
