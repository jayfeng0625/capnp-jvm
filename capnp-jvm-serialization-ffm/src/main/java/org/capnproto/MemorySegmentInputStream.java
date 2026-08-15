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
 * A {@link BufferedInputStream} that reads from a {@link MemorySegment}
 * (native, mapped, or heap). The FFM counterpart of {@code ArrayInputStream}.
 *
 * <p>The segment must be no larger than 2 GB; the stream is a view, so the
 * segment's backing memory must stay alive (its arena open) while the stream
 * is in use.
 */
public class MemorySegmentInputStream implements BufferedInputStream {

    private final ByteBuffer buf;

    public MemorySegmentInputStream(MemorySegment segment) {
        ByteBuffer view = segment.asByteBuffer();
        this.buf = (view.isReadOnly() ? view : view.asReadOnlyBuffer()).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Override
    public final int read(ByteBuffer dst) throws IOException {
        int available = this.buf.remaining();
        int size = Math.min(dst.remaining(), available);
        if (size == 0) {
            // end of stream
            return -1;
        }

        ByteBuffer slice = this.buf.slice();
        slice.limit(size);
        dst.put(slice);

        this.buf.position(this.buf.position() + size);
        return size;
    }

    @Override
    public final ByteBuffer getReadBuffer() {
        if (buf.remaining() > 0) {
            return buf;
        } else {
            throw new DecodeException("Premature EOF while reading buffer");
        }
    }

    @Override
    public final void close() throws IOException {
        return;
    }

    @Override
    public final boolean isOpen() {
        return true;
    }
}
