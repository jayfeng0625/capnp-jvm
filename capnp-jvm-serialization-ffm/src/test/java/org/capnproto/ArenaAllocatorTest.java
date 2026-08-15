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

import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ArenaAllocatorTest {

  @Test
  public void testSegmentsAreDirectZeroedAndLittleEndian() {
    try (ArenaAllocator allocator = new ArenaAllocator()) {
      ByteBuffer segment = allocator.allocateSegment(24);

      assertTrue(segment.isDirect());
      assertEquals(ByteOrder.LITTLE_ENDIAN, segment.order());
      assertTrue(segment.capacity() >= 24);
      for (int i = 0; i < segment.capacity(); ++i) {
        assertEquals(0, segment.get(i));
      }
    }
  }

  @Test
  public void testMinimumSizeIsHonored() {
    try (ArenaAllocator allocator = new ArenaAllocator()) {
      int large = 1 << 20;
      assertTrue(allocator.allocateSegment(large).capacity() >= large);
    }
  }

  @Test
  public void testGrowHeuristically() {
    try (ArenaAllocator allocator = new ArenaAllocator()) {
      allocator.setNextAllocationSizeBytes(8);
      int first = allocator.allocateSegment(8).capacity();
      int second = allocator.allocateSegment(8).capacity();
      assertEquals(8, first);
      assertEquals(16, second);
    }
  }

  @Test
  public void testFixedSize() {
    try (ArenaAllocator allocator = new ArenaAllocator(BuilderArena.AllocationStrategy.FIXED_SIZE)) {
      allocator.setNextAllocationSizeBytes(64);
      assertEquals(64, allocator.allocateSegment(8).capacity());
      assertEquals(64, allocator.allocateSegment(8).capacity());
    }
  }

  @Test
  public void testCloseFreesSegmentsDeterministically() {
    ArenaAllocator allocator = new ArenaAllocator();
    ByteBuffer segment = allocator.allocateSegment(8);
    segment.putLong(0, 42);
    allocator.close();

    // Touching a segment after close must throw, not read freed memory.
    assertThrows(IllegalStateException.class, () -> segment.getLong(0));

    // AutoCloseable convention: closing again must be a no-op, not a throw
    // (e.g. an early manual close inside try-with-resources).
    assertDoesNotThrow(allocator::close);
  }

  @Test
  public void testAdoptedArenaIsNotClosedByAllocator() {
    try (Arena arena = Arena.ofConfined()) {
      ArenaAllocator allocator = new ArenaAllocator(arena);
      ByteBuffer segment = allocator.allocateSegment(8);
      allocator.close();

      // The caller owns the arena, so the segment is still alive.
      segment.putLong(0, 42);
      assertEquals(42, segment.getLong(0));
    }
  }

  @Test
  public void testMessageBuilderOnArenaAllocator() {
    try (ArenaAllocator allocator = new ArenaAllocator()) {
      MessageBuilder message = new MessageBuilder(allocator);
      message.setRoot(Text.factory, new Text.Reader("arena"));
      for (ByteBuffer segment : message.getSegmentsForOutput()) {
        assertTrue(segment.isDirect());
      }
    }
  }
}
