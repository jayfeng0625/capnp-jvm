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
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Serialization using the standard (unpacked) stream encoding
 * (https://capnproto.org/encoding.html#serialization-over-a-stream), with
 * message memory held in native segments managed by FFM arenas (JEP 454).
 *
 * <p>Reading from a channel places all segments in one contiguous native
 * allocation whose lifetime is the returned {@link FfmMessage}; closing it
 * frees the memory deterministically. {@link #map} goes further and does not
 * read the message at all: the file is mapped into memory and the OS pages in
 * only the parts the reader actually touches.
 *
 * <p>Scalar access still happens through little-endian {@link ByteBuffer}
 * views of the native segments, which measure faster than raw
 * {@code MemorySegment} accessors for cap'n proto's scattered fixed-offset
 * field loads, so the core runtime and generated code run unchanged.
 *
 * <p>Writing is native-aware: segments built with an {@link ArenaAllocator}
 * are direct buffers, so they reach {@link WritableByteChannel#write} with no
 * on-heap staging copy, and gathering channels receive the segment table and
 * all segments as a single vectored write.
 */
public final class FfmSerialize {

    static final int MAX_SEGMENT_WORDS = (1 << 28) - 1;

    private static final ValueLayout.OfInt INT_LE =
        ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private static ByteBuffer viewOf(MemorySegment segment) {
        return segment.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void checkSegmentWords(int words) throws IOException {
        if (words > MAX_SEGMENT_WORDS) {
            // Trying to construct the segment would cause overflow.
            throw new DecodeException("segment has too many words (" + words + ")");
        }
    }

    /**
     * Attempts to fill the provided byte buffer using bytes from the provided ReadableByteChannel. Once the buffer has
     * been filled *or* the ReadableByteChannel has reached end-of-stream, returns the number of bytes read.
     */
    private static int tryFillBuffer(ByteBuffer buffer, ReadableByteChannel bc) throws IOException {
        int initialPosition = buffer.position();

        while (buffer.hasRemaining()) {
            int r = bc.read(buffer);
            if (r == 0) {
                throw new IOException("Read zero bytes. Is the channel in non-blocking mode?");
            } else if (r < 0) {
                break;
            }
        }

        return buffer.position() - initialPosition;
    }

    public static void fillBuffer(ByteBuffer buffer, ReadableByteChannel bc) throws IOException {
        while (buffer.hasRemaining()) {
            int r = bc.read(buffer);
            if (r < 0) {
                throw new IOException("premature EOF");
            } else if (r == 0) {
                throw new IOException("Read zero bytes. Is the channel in non-blocking mode?");
            }
        }
    }

    /**
     * Attempts to read a message from the provided ReadableByteChannel with default options. Returns an empty optional
     * if the channel reached end-of-stream on first read. The returned message owns a confined arena and must be
     * closed to free its native memory.
     */
    public static Optional<FfmMessage> tryRead(ReadableByteChannel bc) throws IOException {
        return tryRead(bc, ReaderOptions.DEFAULT_READER_OPTIONS);
    }

    /**
     * Attempts to read a message from the provided ReadableByteChannel with the provided options. Returns an empty
     * optional if the channel reached end-of-stream on first read. The returned message owns a confined arena and
     * must be closed to free its native memory.
     */
    public static Optional<FfmMessage> tryRead(ReadableByteChannel bc, ReaderOptions options) throws IOException {
        Arena arena = Arena.ofConfined();
        try {
            Optional<MessageReader> reader = tryRead(bc, options, arena);
            if (reader.isEmpty()) {
                arena.close();
                return Optional.empty();
            }
            return Optional.of(new FfmMessage(reader.get(), arena));
        } catch (Throwable e) {
            arena.close();
            throw e;
        }
    }

    /**
     * Reads a message from the provided ReadableByteChannel with default options. The returned message owns a
     * confined arena and must be closed to free its native memory.
     */
    public static FfmMessage read(ReadableByteChannel bc) throws IOException {
        return read(bc, ReaderOptions.DEFAULT_READER_OPTIONS);
    }

    /**
     * Reads a message from the provided ReadableByteChannel with the provided options. The returned message owns a
     * confined arena and must be closed to free its native memory.
     */
    public static FfmMessage read(ReadableByteChannel bc, ReaderOptions options) throws IOException {
        Arena arena = Arena.ofConfined();
        try {
            return new FfmMessage(read(bc, options, arena), arena);
        } catch (Throwable e) {
            arena.close();
            throw e;
        }
    }

    /**
     * Attempts to read a message from the provided ReadableByteChannel into segments allocated from the given arena.
     * The caller owns the arena: the returned reader is valid until the caller closes it.
     */
    public static Optional<MessageReader> tryRead(ReadableByteChannel bc,
                                                  ReaderOptions options,
                                                  Arena arena) throws IOException {
        MemorySegment firstWord = arena.allocate(Constants.BYTES_PER_WORD, Constants.BYTES_PER_WORD);
        ByteBuffer firstWordBuffer = viewOf(firstWord);
        int nBytes = tryFillBuffer(firstWordBuffer, bc);
        if (firstWordBuffer.hasRemaining()) {
            // We failed to read a whole word
            if (0 == nBytes) {
                // We were unable to read anything at all: the byte channel has reached end-of-stream
                return Optional.empty();
            } else {
                // We read fewer than 1 word's worth of bytes
                throw new IOException("premature EOF");
            }
        } else {
            // We filled the buffer
            return Optional.of(doRead(bc, options, arena, firstWord));
        }
    }

    /**
     * Reads a message from the provided ReadableByteChannel into segments allocated from the given arena. The caller
     * owns the arena: the returned reader is valid until the caller closes it.
     */
    public static MessageReader read(ReadableByteChannel bc,
                                     ReaderOptions options,
                                     Arena arena) throws IOException {
        MemorySegment firstWord = arena.allocate(Constants.BYTES_PER_WORD, Constants.BYTES_PER_WORD);
        fillBuffer(viewOf(firstWord), bc);
        return doRead(bc, options, arena, firstWord);
    }

    private static MessageReader doRead(ReadableByteChannel bc,
                                        ReaderOptions options,
                                        Arena arena,
                                        MemorySegment firstWord) throws IOException {
        int rawSegmentCount = firstWord.get(INT_LE, 0);
        if (rawSegmentCount < 0 || rawSegmentCount > 511) {
            throw new DecodeException("segment count must be between 0 and 512");
        }

        int segmentCount = 1 + rawSegmentCount;

        int segment0Size = firstWord.get(INT_LE, 4);

        if (segment0Size < 0) {
            throw new DecodeException("segment 0 has more than 2^31 words, which is unsupported");
        }

        long totalWords = segment0Size;

        // in words
        int[] moreSizes = new int[segmentCount - 1];

        if (segmentCount > 1) {
            MemorySegment moreSizesRaw = arena.allocate(4L * (segmentCount & ~1), Constants.BYTES_PER_WORD);
            fillBuffer(viewOf(moreSizesRaw), bc);
            for (int ii = 0; ii < segmentCount - 1; ++ii) {
                int size = moreSizesRaw.get(INT_LE, ii * 4L);
                if (size < 0) {
                    throw new DecodeException("segment " + (ii + 1) +
                                              " has more than 2^31 words, which is unsupported");
                }

                moreSizes[ii] = size;
                totalWords += size;
            }
        }

        if (totalWords > options.traversalLimitInWords) {
            throw new DecodeException("Message size exceeds traversal limit.");
        }

        checkSegmentWords(segment0Size);
        for (int size : moreSizes) {
            checkSegmentWords(size);
        }

        // All segments live in one contiguous native allocation: a single
        // arena carve, cache-dense, and freed as one unit with the arena.
        MemorySegment body = arena.allocate(totalWords * Constants.BYTES_PER_WORD, Constants.BYTES_PER_WORD);

        ByteBuffer[] segmentSlices = new ByteBuffer[segmentCount];
        long offsetWords = 0;
        for (int ii = 0; ii < segmentCount; ++ii) {
            long sizeWords = (ii == 0) ? segment0Size : moreSizes[ii - 1];
            segmentSlices[ii] = viewOf(body.asSlice(offsetWords * Constants.BYTES_PER_WORD,
                                                    sizeWords * Constants.BYTES_PER_WORD));
            offsetWords += sizeWords;
        }

        // Fill segment by segment (not the whole body in one read): a packed
        // channel validates that runs end cleanly on the boundary of each
        // read, so per-segment reads must match the ByteBuffer module's
        // accept/reject behavior exactly.
        for (ByteBuffer slice : segmentSlices) {
            fillBuffer(slice.duplicate(), bc);
        }

        return new MessageReader(segmentSlices, options);
    }

    /**
     * Parses a message in place from the provided MemorySegment with default options. No bytes are copied: the
     * returned reader's segments alias the given memory, and remain valid for as long as it does.
     */
    public static MessageReader read(MemorySegment segment) throws IOException {
        return read(segment, ReaderOptions.DEFAULT_READER_OPTIONS);
    }

    /**
     * Parses a message in place from the provided MemorySegment with the provided options. No bytes are copied: the
     * returned reader's segments alias the given memory, and remain valid for as long as it does. Works with native,
     * mapped, and heap ({@code MemorySegment.ofArray}) segments alike.
     */
    public static MessageReader read(MemorySegment segment, ReaderOptions options) throws IOException {
        if (segment.byteSize() < Constants.BYTES_PER_WORD) {
            throw new DecodeException("premature end of segment: expected at least one word");
        }

        int rawSegmentCount = segment.get(INT_LE, 0);
        int segmentCount = 1 + rawSegmentCount;
        if (rawSegmentCount < 0 || rawSegmentCount > 511) {
            throw new DecodeException("segment count must be between 0 and 512");
        }

        ByteBuffer[] segmentSlices = new ByteBuffer[segmentCount];

        long segmentSizesBase = 4;
        long segmentSizesSize = segmentCount * 4L;

        long align = Constants.BYTES_PER_WORD - 1;
        long segmentBase = (segmentSizesBase + segmentSizesSize + align) & ~align;

        if (segmentBase > segment.byteSize()) {
            throw new DecodeException("premature end of segment: segment table extends beyond its end");
        }

        long totalWords = 0;

        for (int ii = 0; ii < segmentCount; ++ii) {
            int segmentSize = segment.get(INT_LE, segmentSizesBase + ii * 4L);
            if (segmentSize < 0) {
                throw new DecodeException("segment " + ii +
                                          " has more than 2^31 words, which is unsupported");
            }
            checkSegmentWords(segmentSize);

            long segmentStart = segmentBase + totalWords * Constants.BYTES_PER_WORD;
            if (segmentSize * (long) Constants.BYTES_PER_WORD > segment.byteSize() - segmentStart) {
                throw new DecodeException("premature end of segment: segment " + ii +
                                          " extends beyond its end");
            }

            segmentSlices[ii] = viewOf(segment.asSlice(segmentStart,
                                                       segmentSize * (long) Constants.BYTES_PER_WORD));

            totalWords += segmentSize;
        }

        if (options.traversalLimitInWords != -1 && totalWords > options.traversalLimitInWords) {
            throw new DecodeException("Message size exceeds traversal limit.");
        }

        return new MessageReader(segmentSlices, options);
    }

    /**
     * Memory-maps the message stored in the given file with default options. Nothing is read eagerly: the OS pages in
     * only the parts of the message the reader touches. The returned message owns the mapping and must be closed to
     * unmap it deterministically.
     */
    public static FfmMessage map(Path path) throws IOException {
        return map(path, ReaderOptions.DEFAULT_READER_OPTIONS);
    }

    /**
     * Memory-maps the message stored in the given file with the provided options. Nothing is read eagerly: the OS
     * pages in only the parts of the message the reader touches. The returned message owns the mapping and must be
     * closed to unmap it deterministically.
     */
    public static FfmMessage map(Path path, ReaderOptions options) throws IOException {
        Arena arena = Arena.ofConfined();
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            // The mapping stays valid after the channel is closed.
            return new FfmMessage(map(channel, 0, channel.size(), options, arena), arena);
        } catch (Throwable e) {
            arena.close();
            throw e;
        }
    }

    /**
     * Memory-maps {@code size} bytes of the given file starting at {@code position} and parses a message in place.
     * The mapping is owned by the given arena; the returned reader is valid until the caller closes it.
     */
    public static MessageReader map(FileChannel channel,
                                    long position,
                                    long size,
                                    ReaderOptions options,
                                    Arena arena) throws IOException {
        MemorySegment mapped = channel.map(FileChannel.MapMode.READ_ONLY, position, size, arena);
        return read(mapped, options);
    }

    public static long computeSerializedSizeInWords(MessageBuilder message) {
        return computeSerializedSizeInWords(message.getSegmentsForOutput());
    }

    //VisibleForTesting
    static long computeSerializedSizeInWords(ByteBuffer[] segments) {
        // From the capnproto documentation (https://capnproto.org/encoding.html#serialization-over-a-stream):
        // "When transmitting over a stream, the following should be sent..."
        long bytes = 0;
        // "(4 bytes) The number of segments, minus one..."
        bytes += 4;
        // "(N * 4 bytes) The size of each segment, in words."
        bytes += segments.length * 4L;
        // "(0 or 4 bytes) Padding up to the next word boundary."
        if (bytes % 8 != 0) {
            bytes += 4;
        }

        // The content of each segment, in order.
        for (int i = 0; i < segments.length; ++i) {
            ByteBuffer s = segments[i];
            bytes += s.limit();
        }

        return bytes / Constants.BYTES_PER_WORD;
    }

    private static ByteBuffer makeSegmentTable(Arena arena, ByteBuffer[] segments) {
        int tableSize = (segments.length + 2) & (~1);

        // Arena.allocate zero-fills, so any padding entry is already zero.
        ByteBuffer table = viewOf(arena.allocate(4L * tableSize, Constants.BYTES_PER_WORD));

        table.putInt(0, segments.length - 1);

        for (int i = 0; i < segments.length; ++i) {
            table.putInt(4 * (i + 1), segments[i].limit() / 8);
        }

        return table;
    }

    private static void writeSegments(WritableByteChannel outputChannel,
                                      ByteBuffer[] segments) throws IOException {
        // The segment table is native scratch too, so a gathering channel sees
        // an all-direct buffer array: one vectored write, no staging copies.
        try (Arena tableArena = Arena.ofConfined()) {
            ByteBuffer table = makeSegmentTable(tableArena, segments);

            if (outputChannel instanceof GatheringByteChannel gatheringChannel) {
                ByteBuffer[] buffers = new ByteBuffer[segments.length + 1];
                buffers[0] = table;
                System.arraycopy(segments, 0, buffers, 1, segments.length);

                long remaining = table.remaining();
                for (ByteBuffer segment : segments) {
                    remaining += segment.remaining();
                }
                while (remaining > 0) {
                    remaining -= gatheringChannel.write(buffers, 0, buffers.length);
                }
            } else {
                while (table.hasRemaining()) {
                    outputChannel.write(table);
                }

                for (ByteBuffer buffer : segments) {
                    while (buffer.hasRemaining()) {
                        outputChannel.write(buffer);
                    }
                }
            }
        }
    }

    /**
     * Serializes a MessageBuilder to a WritableByteChannel. Messages built with an {@link ArenaAllocator} are written
     * directly from their native segments, with no on-heap staging copy.
     */
    public static void write(WritableByteChannel outputChannel,
                             MessageBuilder message) throws IOException {
        writeSegments(outputChannel, message.getSegmentsForOutput());
    }

    /**
     * Serializes a MessageReader to a WritableByteChannel.
     */
    public static void write(WritableByteChannel outputChannel,
                             MessageReader message) throws IOException {
        ByteBuffer[] segments = new ByteBuffer[message.arena.segments.size()];
        for (int ii = 0 ; ii < message.arena.segments.size(); ++ii) {
            segments[ii] = message.arena.segments.get(ii).buffer.duplicate();
        }

        writeSegments(outputChannel, segments);
    }
}
