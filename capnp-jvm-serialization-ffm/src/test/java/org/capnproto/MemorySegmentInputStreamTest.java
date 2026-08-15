// Copyright (c) 2018 Sandstorm Development Group, Inc. and contributors
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

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MemorySegmentInputStreamTest {
  @Test
  public void testEmptySegment() throws IOException {
    MemorySegmentInputStream stream = new MemorySegmentInputStream(MemorySegment.ofArray(new byte[0]));
    ByteBuffer dst = ByteBuffer.allocate(10);

    // read() should return -1 at the end of the stream
    // https://docs.oracle.com/javase/7/docs/api/java/nio/channels/ReadableByteChannel.html
    assertEquals(-1, stream.read(dst));
  }

  @Test
  public void testRequestMoreBytesThanArePresent() throws IOException {
    byte[] oneByte = new byte[]{42};
    MemorySegmentInputStream stream = new MemorySegmentInputStream(MemorySegment.ofArray(oneByte));
    ByteBuffer dst = ByteBuffer.allocate(10);
    assertEquals(1, stream.read(dst));
    assertEquals(-1, stream.read(dst)); // end of stream
  }

  @Test
  public void testNativeSegment() throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment segment = arena.allocate(8, 8);
      segment.set(ValueLayout.JAVA_BYTE, 0, (byte) 42);

      MemorySegmentInputStream stream = new MemorySegmentInputStream(segment);
      ByteBuffer dst = ByteBuffer.allocate(10);
      assertEquals(8, stream.read(dst));
      assertEquals(42, dst.get(0));
      assertEquals(-1, stream.read(dst)); // end of stream
    }
  }
}
