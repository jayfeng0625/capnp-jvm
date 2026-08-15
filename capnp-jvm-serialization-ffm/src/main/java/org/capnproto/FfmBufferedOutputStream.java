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
import java.nio.channels.WritableByteChannel;

/**
 * A {@link BufferedOutputStream} that buffers writes to a
 * {@link WritableByteChannel} in native memory. The FFM counterpart of
 * {@code BufferedOutputStreamWrapper}: because the buffer is a direct buffer,
 * flushing it to the channel goes straight to the kernel, with no intermediate
 * on-heap copy.
 *
 * <p>By default the buffer is allocated from an automatic arena, so the
 * stream needs no explicit lifetime management beyond {@link #close()} (which
 * only closes the underlying channel, mirroring
 * {@code BufferedOutputStreamWrapper}). Pass an arena to tie the buffer to a
 * caller-managed scope instead.
 */
public final class FfmBufferedOutputStream implements BufferedOutputStream {

    private static final int DEFAULT_BUFFER_BYTES = 8192;

    private final WritableByteChannel inner;
    private final ByteBuffer buf;

    public FfmBufferedOutputStream(WritableByteChannel w) {
        this(w, Arena.ofAuto(), DEFAULT_BUFFER_BYTES);
    }

    public FfmBufferedOutputStream(WritableByteChannel w, Arena arena, int bufferSizeBytes) {
        this.inner = w;
        this.buf = arena.allocate(bufferSizeBytes, Constants.BYTES_PER_WORD)
            .asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    @Override
    public final int write(ByteBuffer src) throws IOException {
        int available = this.buf.remaining();
        int size = src.remaining();
        if (size <= available) {
            this.buf.put(src);
        } else if (size <= this.buf.capacity()) {
            //# Too much for this buffer, but not a full buffer's worth,
            //# so we'll go ahead and copy.
            ByteBuffer slice = src.slice();
            slice.limit(available);
            this.buf.put(slice);

            this.buf.rewind();
            while(this.buf.hasRemaining()) {
                this.inner.write(this.buf);
            }
            this.buf.rewind();

            src.position(src.position() + available);
            this.buf.put(src);
        } else {
            //# Writing so much data that we might as well write
            //# directly to avoid a copy.

            int pos = this.buf.position();
            this.buf.rewind();
            ByteBuffer slice = this.buf.slice();
            slice.limit(pos);
            while (slice.hasRemaining()) {
                this.inner.write(slice);
            }
            while (src.hasRemaining()) {
                this.inner.write(src);
            }
        }
        return size;
    }

    @Override
    public final ByteBuffer getWriteBuffer() {
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

    @Override
    public final void flush() throws IOException {
        int pos = this.buf.position();
        this.buf.rewind();
        this.buf.limit(pos);
        // A channel may consume fewer bytes than requested per call; keep
        // writing until the buffer is drained so no tail is dropped.
        while (this.buf.hasRemaining()) {
            this.inner.write(this.buf);
        }
        this.buf.clear();
    }
}
