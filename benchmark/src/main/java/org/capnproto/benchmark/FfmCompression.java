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

package org.capnproto.benchmark;

import java.io.IOException;
import java.lang.foreign.Arena;

import org.capnproto.BufferedInputStream;
import org.capnproto.BufferedOutputStream;
import org.capnproto.MessageBuilder;
import org.capnproto.MessageReader;

/**
 * The {@link Compression} counterpart for the FFM serialization module:
 * unpacked message memory on the read side is allocated from the given arena
 * instead of the heap.
 */
public interface FfmCompression {
    public void writeBuffered(BufferedOutputStream writer,
                              MessageBuilder message) throws IOException;

    public MessageReader newBufferedReader(
        BufferedInputStream inputStream, Arena arena) throws IOException;

    public final FfmCompression PACKED = new PackedFfm();
    public final FfmCompression UNCOMPRESSED = new UncompressedFfm();

    public static FfmCompression of(Compression compression) {
        return compression == Compression.PACKED ? PACKED : UNCOMPRESSED;
    }
}
