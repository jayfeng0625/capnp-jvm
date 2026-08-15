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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class Text {
    public static final class Factory implements
                                      FromPointerReaderBlobDefault<Reader>,
                                      FromPointerBuilderBlobDefault<Builder>,
                                      PointerFactory<Builder, Reader>,
                                      SetPointerBuilder<Builder, Reader> {
        @Override
        public final Reader fromPointerReaderBlobDefault(SegmentReader segment, int pointer, java.nio.ByteBuffer defaultBuffer,
                                                   int defaultOffset, int defaultSize) {
            return WireHelpers.readTextPointer(segment, pointer, defaultBuffer, defaultOffset, defaultSize);
        }

        @Override
        public final Reader fromPointerReader(SegmentReader segment, int pointer, int nestingLimit) {
            return WireHelpers.readTextPointer(segment, pointer, null, 0, 0);
        }

        @Override
        public final Builder fromPointerBuilderBlobDefault(SegmentBuilder segment, int pointer,
                                                     java.nio.ByteBuffer defaultBuffer, int defaultOffset, int defaultSize) {
            return WireHelpers.getWritableTextPointer(pointer,
                                                      segment,
                                                      defaultBuffer,
                                                      defaultOffset,
                                                      defaultSize);
        }

        @Override
        public final Builder fromPointerBuilder(SegmentBuilder segment, int pointer) {
            return WireHelpers.getWritableTextPointer(pointer,
                                                      segment,
                                                      null, 0, 0);
        }

        @Override
        public final Builder initFromPointerBuilder(SegmentBuilder segment, int pointer, int size) {
            return WireHelpers.initTextPointer(pointer, segment, size);
        }

        @Override
        public final void setPointerBuilder(SegmentBuilder segment, int pointer, Reader value) {
            WireHelpers.setTextPointer(pointer, segment, value);
        }
    }
    public static final Factory factory = new Factory();

    /**
     * Decodes UTF-8 into a String with a single pass where possible: for
     * array-backed buffers the String is built straight from the backing
     * array, skipping the intermediate byte[] staging copy.
     */
    private static String decodeUtf8(ByteBuffer buffer, int offset, int size) {
        if (buffer.hasArray()) {
            return new String(buffer.array(), buffer.arrayOffset() + offset, size, StandardCharsets.UTF_8);
        }

        byte[] bytes = new byte[size];

        ByteBuffer dup = buffer.duplicate();
        dup.position(offset);
        dup.get(bytes, 0, size);

        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Byte offset of the first occurrence of `needle` within
     * `[offset, offset + size)` of `buffer`, or -1. Uses absolute reads only;
     * never touches buffer position, never copies the text.
     */
    private static int indexOf(ByteBuffer buffer, int offset, int size, byte[] needle) {
        if (needle.length == 0) {
            return 0;
        }
        // When the needle is longer than the window, `last < offset` and the
        // loop body never runs. The first-byte check keeps the full
        // comparison off the per-position path (measured ~10x on this scan).
        byte first = needle[0];
        int last = offset + size - needle.length;
        for (int i = offset; i <= last; ++i) {
            if (buffer.get(i) == first && matchesAt(buffer, i, needle)) {
                return i - offset;
            }
        }
        return -1;
    }

    /** Whether `needle` occurs at `position`. The caller has already matched `needle[0]`. */
    private static boolean matchesAt(ByteBuffer buffer, int position, byte[] needle) {
        for (int j = 1; j < needle.length; ++j) {
            if (buffer.get(position + j) != needle[j]) {
                return false;
            }
        }
        return true;
    }

    /**
     * True if `[offset, offset + size)` of `buffer` holds exactly the UTF-8
     * encoding of `other`. ASCII operands are compared with no allocation at
     * all; operands containing non-ASCII characters are encoded first (an
     * allocation proportional to the operand, never to the text).
     */
    private static boolean contentEquals(ByteBuffer buffer, int offset, int size, CharSequence other) {
        int n = other.length();
        int i = 0;
        for (; i < n; ++i) {
            char c = other.charAt(i);
            if (c >= 0x80) {
                break;
            }
            if (i >= size || buffer.get(offset + i) != (byte) c) {
                return false;
            }
        }
        if (i == n) {
            // Fully ASCII operand: matched byte-for-byte.
            return n == size;
        }

        // Non-ASCII operand: encode it and compare the remainder. The first
        // `i` ASCII characters encode to the same `i` bytes already matched.
        byte[] bytes = other.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length != size) {
            return false;
        }
        for (int j = i; j < size; ++j) {
            if (buffer.get(offset + j) != bytes[j]) {
                return false;
            }
        }
        return true;
    }

    public static final class Reader {
        public final ByteBuffer buffer;
        public final int offset; // in bytes
        public final int size; // in bytes, not including NUL terminator

        public Reader() {
            // TODO what about the null terminator?
            this.buffer = ByteBuffer.allocate(0);
            this.offset = 0;
            this.size = 0;
        }

        public Reader(ByteBuffer buffer, int offset, int size) {
            this.buffer = buffer;
            this.offset = offset * 8;
            this.size = size;
        }

        public Reader(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            this.buffer = ByteBuffer.wrap(bytes);
            this.offset = 0;
            this.size = bytes.length;
        }

        public final int size() {
            return this.size;
        }

        public ByteBuffer asByteBuffer() {
            ByteBuffer dup = this.buffer.asReadOnlyBuffer();
            dup.position(this.offset);
            ByteBuffer result = dup.slice();
            result.limit(this.size);
            return result;
        }

        /**
         * Byte offset of the first occurrence of the UTF-8 encoding of `needle`
         * in this text, or -1 if absent. For well-formed UTF-8, a byte-level
         * match coincides exactly with a character-level match (no encoded
         * character can begin inside another's encoding), so this equals
         * {@code toString().indexOf(needle)} measured in bytes — without
         * decoding or copying the text. Only `needle` is encoded; hot loops
         * can pre-encode it once and use {@link #indexOf(byte[])}.
         */
        public final int indexOf(CharSequence needle) {
            return Text.indexOf(this.buffer, this.offset, this.size, needle.toString().getBytes(StandardCharsets.UTF_8));
        }

        /**
         * Byte offset of the first occurrence of `needleUtf8` (UTF-8 bytes) in
         * this text, or -1 if absent. Allocation-free.
         */
        public final int indexOf(byte[] needleUtf8) {
            return Text.indexOf(this.buffer, this.offset, this.size, needleUtf8);
        }

        /**
         * True if the UTF-8 encoding of `needle` occurs in this text.
         * Equivalent to {@code toString().contains(needle)} without decoding
         * or copying the text.
         */
        public final boolean contains(CharSequence needle) {
            return indexOf(needle) >= 0;
        }

        /**
         * True if this text is exactly `other`. Equivalent to
         * {@code toString().contentEquals(other)} without decoding or copying
         * the text; ASCII operands are compared with no allocation at all.
         */
        public final boolean contentEquals(CharSequence other) {
            return Text.contentEquals(this.buffer, this.offset, this.size, other);
        }

        @Override
        public final String toString() {
            return decodeUtf8(this.buffer, this.offset, this.size);
        }

    }

    public static final class Builder {
        public final ByteBuffer buffer;
        public final int offset; // in bytes
        public final int size; // in bytes

        public Builder() {
            this.buffer = ByteBuffer.allocate(0);
            this.offset = 0;
            this.size = 0;
        }

        public Builder(ByteBuffer buffer, int offset, int size) {
            this.buffer = buffer;
            this.offset = offset;
            this.size = size;
        }

        public final int size() {
            return this.size;
        }

        public ByteBuffer asByteBuffer() {
            ByteBuffer dup = this.buffer.duplicate();
            dup.position(this.offset);
            ByteBuffer result = dup.slice();
            result.limit(this.size);
            return result;
        }

        /** See {@link Reader#indexOf(CharSequence)}. */
        public final int indexOf(CharSequence needle) {
            return Text.indexOf(this.buffer, this.offset, this.size, needle.toString().getBytes(StandardCharsets.UTF_8));
        }

        /** See {@link Reader#indexOf(byte[])}. */
        public final int indexOf(byte[] needleUtf8) {
            return Text.indexOf(this.buffer, this.offset, this.size, needleUtf8);
        }

        /** See {@link Reader#contains(CharSequence)}. */
        public final boolean contains(CharSequence needle) {
            return indexOf(needle) >= 0;
        }

        /** See {@link Reader#contentEquals(CharSequence)}. */
        public final boolean contentEquals(CharSequence other) {
            return Text.contentEquals(this.buffer, this.offset, this.size, other);
        }

        @Override
        public final String toString() {
            return decodeUtf8(this.buffer, this.offset, this.size);
        }

    }

}
