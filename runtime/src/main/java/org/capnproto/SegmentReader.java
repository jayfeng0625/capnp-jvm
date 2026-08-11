// Copyright (c) 2013-2014 Sandstorm Development Group, Inc. and contributors
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

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class SegmentReader {

    public final ByteBuffer buffer;
    final Arena arena;

    // FFM (java.lang.foreign) view over exactly the same memory as `buffer`.
    // Cap'n Proto wire data is little-endian and fields are not guaranteed to
    // be naturally aligned relative to a heap array base, so all accesses use
    // the *_UNALIGNED layouts with an explicit little-endian byte order.
    final MemorySegment memory;

    static final ValueLayout.OfShort  SHORT_LE  = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfInt    INT_LE    = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfLong   LONG_LE   = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfFloat  FLOAT_LE  = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfDouble DOUBLE_LE = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    public SegmentReader(ByteBuffer buffer, Arena arena) {
        this.buffer = buffer;
        this.arena = arena;
        this.memory = wrap(buffer);
    }

    /**
     * Build an FFM MemorySegment over exactly the same memory as {@code buffer},
     * where byte index N in the buffer maps to offset N in the segment.
     *
     * For a plain heap buffer (the common case: {@code ByteBuffer.allocate}) we
     * wrap its backing array directly with {@link MemorySegment#ofArray}. Such a
     * segment is attached to the always-alive global scope, so the JIT can elide
     * the per-access liveness check that {@link MemorySegment#ofBuffer} imposes.
     * Anything else (direct buffers, non-zero-offset slices, read-only buffers)
     * falls back to {@code ofBuffer} over a full-capacity duplicate.
     */
    private static MemorySegment wrap(ByteBuffer buffer) {
        if (buffer.hasArray()
            && buffer.arrayOffset() == 0
            && buffer.array().length == buffer.capacity()) {
            return MemorySegment.ofArray(buffer.array());
        }
        ByteBuffer view = buffer.duplicate();
        view.clear();
        return MemorySegment.ofBuffer(view);
    }

    public static final SegmentReader EMPTY = new SegmentReader(ByteBuffer.allocate(8), null);

    public final long get(int index) {
        return this.memory.get(LONG_LE, (long) index * Constants.BYTES_PER_WORD);
    }

    // Byte-offset primitive accessors backed by the FFM MemorySegment.
    public final byte getByte(int byteOffset) {
        return this.memory.get(ValueLayout.JAVA_BYTE, byteOffset);
    }

    public final short getShort(int byteOffset) {
        return this.memory.get(SHORT_LE, byteOffset);
    }

    public final int getInt(int byteOffset) {
        return this.memory.get(INT_LE, byteOffset);
    }

    public final long getLong(int byteOffset) {
        return this.memory.get(LONG_LE, byteOffset);
    }

    public final float getFloat(int byteOffset) {
        return this.memory.get(FLOAT_LE, byteOffset);
    }

    public final double getDouble(int byteOffset) {
        return this.memory.get(DOUBLE_LE, byteOffset);
    }

    /**
     * Verify that the `size`-long (in words) range starting at word index
     * `start` is within bounds.
     */
    public final boolean isInBounds(int startInWords, int sizeInWords) {
        if (startInWords < 0 || sizeInWords < 0) return false;
        long startInBytes = (long) startInWords * Constants.BYTES_PER_WORD;
        long sizeInBytes = (long) sizeInWords * Constants.BYTES_PER_WORD;
        return startInBytes + sizeInBytes <= this.buffer.capacity();
    }
}
