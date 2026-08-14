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
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A {@link BufferedOutputStream} that writes into a {@link MemorySegment}.
 * The FFM counterpart of {@code ArrayOutputStream}.
 *
 * <p>The segment must be no larger than 2 GB; the stream is a view, so the
 * segment's backing memory must stay alive (its arena open) while the stream
 * is in use.
 */
public final class MemorySegmentOutputStream implements BufferedOutputStream {

    public final ByteBuffer buf;

    public MemorySegmentOutputStream(MemorySegment segment) {
        this.buf = segment.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    @Override
    public final int write(ByteBuffer src) throws IOException {
        int available = this.buf.remaining();
        int size = src.remaining();
        if (available < size) {
            throw new IOException("backing buffer was not large enough");
        }
        this.buf.put(src);
        return size;
    }

    @Override
    public final ByteBuffer getWriteBuffer() {
        return this.buf;
    }

    @Override
    public final void close() throws IOException {
        return;
    }

    @Override
    public final boolean isOpen() {
        return true;
    }

    @Override
    public final void flush() { }
}
