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
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Optional;

/**
 * Serialization using the packed encoding
 * (https://capnproto.org/encoding.html#packing), with unpacked message memory
 * held in native segments managed by FFM arenas. The packed byte stream is
 * identical to the ByteBuffer module's {@code SerializePacked}.
 */
public final class FfmSerializePacked {

    /**
     * Attempts to read a message from the provided BufferedInputStream with default options. Returns an empty optional
     * if the input stream reached end-of-stream on first read. The returned message owns a confined arena and must be
     * closed to free its native memory.
     */
    public static Optional<FfmMessage> tryRead(BufferedInputStream input) throws IOException {
        return tryRead(input, ReaderOptions.DEFAULT_READER_OPTIONS);
    }

    /**
     * Attempts to read a message from the provided BufferedInputStream with the provided options. Returns an empty
     * optional if the input stream reached end-of-stream on first read. The returned message owns a confined arena
     * and must be closed to free its native memory.
     */
    public static Optional<FfmMessage> tryRead(BufferedInputStream input, ReaderOptions options) throws IOException {
        FfmPackedInputStream packedInput = new FfmPackedInputStream(input);
        return FfmSerialize.tryRead(packedInput, options);
    }

    /**
     * Reads a message from the provided BufferedInputStream with default options. The returned message owns a
     * confined arena and must be closed to free its native memory.
     */
    public static FfmMessage read(BufferedInputStream input) throws IOException {
        return read(input, ReaderOptions.DEFAULT_READER_OPTIONS);
    }

    /**
     * Reads a message from the provided BufferedInputStream with the provided options. The returned message owns a
     * confined arena and must be closed to free its native memory.
     */
    public static FfmMessage read(BufferedInputStream input, ReaderOptions options) throws IOException {
        FfmPackedInputStream packedInput = new FfmPackedInputStream(input);
        return FfmSerialize.read(packedInput, options);
    }

    /**
     * Reads a message from the provided BufferedInputStream into segments allocated from the given arena. The caller
     * owns the arena: the returned reader is valid until the caller closes it.
     */
    public static MessageReader read(BufferedInputStream input, ReaderOptions options, Arena arena) throws IOException {
        FfmPackedInputStream packedInput = new FfmPackedInputStream(input);
        return FfmSerialize.read(packedInput, options, arena);
    }

    /**
     * Wraps the provided ReadableByteChannel in a natively-buffered stream and attempts to read a message from it
     * with default options. Returns an empty optional if the channel reached end-of-stream on first read.
     */
    public static Optional<FfmMessage> tryReadFromUnbuffered(ReadableByteChannel input) throws IOException {
        return tryReadFromUnbuffered(input, ReaderOptions.DEFAULT_READER_OPTIONS);
    }

    /**
     * Wraps the provided ReadableByteChannel in a natively-buffered stream and attempts to read a message from it
     * with the provided options. Returns an empty optional if the channel reached end-of-stream on first read.
     */
    public static Optional<FfmMessage> tryReadFromUnbuffered(ReadableByteChannel input,
                                                                ReaderOptions options) throws IOException {
        FfmPackedInputStream packedInput = new FfmPackedInputStream(new FfmBufferedInputStream(input));
        return FfmSerialize.tryRead(packedInput, options);
    }

    /**
     * Wraps the provided ReadableByteChannel in a natively-buffered stream and reads a message from it with default
     * options.
     */
    public static FfmMessage readFromUnbuffered(ReadableByteChannel input) throws IOException {
        return readFromUnbuffered(input, ReaderOptions.DEFAULT_READER_OPTIONS);
    }

    /**
     * Wraps the provided ReadableByteChannel in a natively-buffered stream and reads a message from it with the
     * provided options.
     */
    public static FfmMessage readFromUnbuffered(ReadableByteChannel input,
                                                   ReaderOptions options) throws IOException {
        FfmPackedInputStream packedInput = new FfmPackedInputStream(new FfmBufferedInputStream(input));
        return FfmSerialize.read(packedInput, options);
    }

    /**
     * Serializes a MessageBuilder to a BufferedOutputStream.
     */
    public static void write(BufferedOutputStream output,
                             MessageBuilder message) throws IOException {
        FfmPackedOutputStream packedOutputStream = new FfmPackedOutputStream(output);
        FfmSerialize.write(packedOutputStream, message);
    }

    /**
     * Serializes a MessageReader to a BufferedOutputStream.
     */
    public static void write(BufferedOutputStream output,
                             MessageReader message) throws IOException {
        FfmPackedOutputStream packedOutputStream = new FfmPackedOutputStream(output);
        FfmSerialize.write(packedOutputStream, message);
    }

    /**
     * Serializes a MessageBuilder to an unbuffered output stream, buffering through native memory.
     */
    public static void writeToUnbuffered(WritableByteChannel output,
                                         MessageBuilder message) throws IOException {
        FfmBufferedOutputStream buffered = new FfmBufferedOutputStream(output);
        write(buffered, message);
        buffered.flush();
    }

    /**
     * Serializes a MessageReader to an unbuffered output stream, buffering through native memory.
     */
    public static void writeToUnbuffered(WritableByteChannel output,
                                         MessageReader message) throws IOException {
        FfmBufferedOutputStream buffered = new FfmBufferedOutputStream(output);
        write(buffered, message);
        buffered.flush();
    }

}
