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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class FfmBufferedOutputStreamTest {

  /** A channel that consumes at most `maxPerWrite` bytes per write() call. */
  private static final class ShortWritingChannel implements WritableByteChannel {
    final ByteArrayOutputStream received = new ByteArrayOutputStream();
    final int maxPerWrite;

    ShortWritingChannel(int maxPerWrite) {
      this.maxPerWrite = maxPerWrite;
    }

    @Override
    public int write(ByteBuffer src) {
      int n = Math.min(this.maxPerWrite, src.remaining());
      for (int i = 0; i < n; ++i) {
        this.received.write(src.get());
      }
      return n;
    }

    @Override
    public boolean isOpen() {
      return true;
    }

    @Override
    public void close() { }
  }

  @Test
  public void testFlushDrainsBufferOnShortWritingChannel() throws IOException {
    // A WritableByteChannel may legally consume fewer bytes than requested
    // per call; flush() must keep writing until the buffer is drained
    // instead of silently dropping the tail.
    byte[] payload = new byte[100];
    for (int i = 0; i < payload.length; ++i) {
      payload[i] = (byte) i;
    }

    ShortWritingChannel channel = new ShortWritingChannel(3);
    FfmBufferedOutputStream stream = new FfmBufferedOutputStream(channel);
    stream.write(ByteBuffer.wrap(payload));
    stream.flush();

    assertArrayEquals(payload, channel.received.toByteArray());
  }
}
