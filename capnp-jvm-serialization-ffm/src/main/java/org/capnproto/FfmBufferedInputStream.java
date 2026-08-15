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
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;

/**
 * A {@link BufferedInputStream} that buffers a {@link ReadableByteChannel}
 * into native memory. The FFM counterpart of
 * {@code BufferedInputStreamWrapper}: because the read-ahead buffer is a
 * direct buffer, channel reads land in it straight from the kernel, with no
 * intermediate on-heap copy.
 *
 * <p>By default the buffer is allocated from an automatic arena, so the
 * stream needs no explicit lifetime management beyond {@link #close()} (which
 * only closes the underlying channel, mirroring
 * {@code BufferedInputStreamWrapper}). Pass an arena to tie the buffer to a
 * caller-managed scope instead.
 */
public final class FfmBufferedInputStream implements BufferedInputStream {

    private static final int DEFAULT_BUFFER_BYTES = 8192;

    private final ReadableByteChannel inner;
    private final ByteBuffer buf;

    public FfmBufferedInputStream(ReadableByteChannel chan) {
        this(chan, Arena.ofAuto(), DEFAULT_BUFFER_BYTES);
    }

    public FfmBufferedInputStream(ReadableByteChannel chan, Arena arena, int bufferSizeBytes) {
        this.inner = chan;
        this.buf = arena.allocate(bufferSizeBytes, Constants.BYTES_PER_WORD)
            .asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
        this.buf.limit(0);
    }

    @Override
    public final int read(ByteBuffer dst) throws IOException {
        int numBytes = dst.remaining();
        if (numBytes < this.buf.remaining()) {
            //# Serve from the current buffer.
            ByteBuffer slice = this.buf.slice();
            slice.limit(numBytes);
            dst.put(slice);
            this.buf.position(this.buf.position() + numBytes);
            return numBytes;
        } else {
            //# Copy current available into destination.
            int fromFirstBuffer = this.buf.remaining();
            {
                ByteBuffer slice = this.buf.slice();
                slice.limit(fromFirstBuffer);
                dst.put(slice);
            }

            numBytes -= fromFirstBuffer;
            if (numBytes <= this.buf.capacity()) {
                //# Read the next buffer-full.
                this.buf.clear();
                int n = readAtLeast(this.inner, this.buf, numBytes);

                this.buf.rewind();
                ByteBuffer slice = this.buf.slice();
                slice.limit(numBytes);
                dst.put(slice);

                this.buf.limit(n);
                this.buf.position(numBytes);
                return fromFirstBuffer + numBytes;
            } else {
                //# Forward large read to the underlying stream.
                this.buf.clear();
                this.buf.limit(0);
                return fromFirstBuffer + readAtLeast(this.inner, dst, numBytes);
            }
        }
    }

    @Override
    public final ByteBuffer getReadBuffer() throws IOException {
        if (this.buf.remaining() == 0) {
            this.buf.clear();
            int n = readAtLeast(this.inner, this.buf, 1);
            this.buf.rewind();
            this.buf.limit(n);
        }
        return this.buf;
    }

    @Override
    public final void close() throws IOException {
        this.inner.close();
    }

    @Override
    public final boolean isOpen() {
        return this.inner.isOpen();
    }

    private static int readAtLeast(ReadableByteChannel reader, ByteBuffer buf, int minBytes) throws IOException {
        int numRead = 0;
        while (numRead < minBytes) {
            int res = reader.read(buf);
            if (res < 0) {
                throw new DecodeException("premature EOF");
            }
            numRead += res;
        }
        return numRead;
    }
}
