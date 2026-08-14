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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.capnproto.BuilderArena.AllocationStrategy;

/**
 * An {@link Allocator} that carves message segments out of an FFM
 * {@link Arena} (JEP 454): native off-heap memory with a
 * deterministic lifetime. All segments are freed at {@link #close()} instead of
 * whenever the garbage collector gets around to it.
 *
 * <p>Unlike per-message {@code ByteBuffer.allocateDirect} (whose memory is only
 * reclaimed when the GC runs the buffer's Cleaner, which can balloon the direct
 * pool under allocation churn), closing this allocator frees the native memory
 * immediately. Accessing a segment of a message after its allocator has been
 * closed throws {@code IllegalStateException}.
 *
 * <p>Segments are handed to the runtime as little-endian {@link ByteBuffer}
 * views of the native memory ({@code MemorySegment.asByteBuffer()}), so the
 * generated accessor code runs unchanged on top of arena storage.
 *
 * <p>Typical use, one arena per message:
 * <pre>{@code
 * try (ArenaAllocator allocator = new ArenaAllocator()) {
 *     MessageBuilder message = new MessageBuilder(allocator);
 *     // build, serialize ...
 * } // native memory freed here
 * }</pre>
 *
 * <p>An allocator may also adopt an externally managed arena, so that several
 * messages (or a message plus scratch buffers) share one request-scoped
 * lifetime:
 * <pre>{@code
 * try (Arena arena = Arena.ofConfined()) {
 *     MessageBuilder request = new MessageBuilder(new ArenaAllocator(arena));
 *     MessageBuilder response = new MessageBuilder(new ArenaAllocator(arena));
 *     // ...
 * } // everything freed together
 * }</pre>
 *
 * <p>By default the backing FFM arena is confined: the message must be built
 * and read on the thread that created the allocator. Pass in
 * {@code Arena.ofShared()} if the message has to cross threads.
 */
public final class ArenaAllocator implements Allocator, AutoCloseable {

    private final Arena arena;
    private final boolean ownsArena;

    // (minimum) number of bytes in the next allocation, mirroring DefaultAllocator
    private int nextSize = BuilderArena.SUGGESTED_FIRST_SEGMENT_WORDS * Constants.BYTES_PER_WORD;

    public AllocationStrategy allocationStrategy = AllocationStrategy.GROW_HEURISTICALLY;

    /** See {@link DefaultAllocator#maxSegmentBytes}. */
    public int maxSegmentBytes = Integer.MAX_VALUE - 2;

    /**
     * Creates an allocator backed by its own confined arena. The native memory
     * is freed when {@link #close()} is called.
     */
    public ArenaAllocator() {
        this.arena = Arena.ofConfined();
        this.ownsArena = true;
    }

    public ArenaAllocator(AllocationStrategy allocationStrategy) {
        this();
        this.allocationStrategy = allocationStrategy;
    }

    /**
     * Creates an allocator that carves segments out of the given arena. The
     * caller retains ownership of the arena: {@link #close()} on this
     * allocator is a no-op, and the memory is freed when the caller closes
     * the arena itself.
     */
    public ArenaAllocator(Arena arena) {
        this.arena = arena;
        this.ownsArena = false;
    }

    public ArenaAllocator(Arena arena, AllocationStrategy allocationStrategy) {
        this(arena);
        this.allocationStrategy = allocationStrategy;
    }

    /** The arena that segments are allocated from. */
    public Arena getArena() {
        return this.arena;
    }

    public void setNextAllocationSizeBytes(int nextSize) {
        this.nextSize = nextSize;
    }

    @Override
    public ByteBuffer allocateSegment(int minimumSize) {
        int size = Math.max(minimumSize, this.nextSize);
        // Arena.allocate zeroes the memory, satisfying the Allocator contract.
        MemorySegment segment = this.arena.allocate(size, Constants.BYTES_PER_WORD);
        ByteBuffer result = segment.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);

        switch (this.allocationStrategy) {
            case GROW_HEURISTICALLY:
                if (size < this.maxSegmentBytes - this.nextSize) {
                    this.nextSize += size;
                } else {
                    this.nextSize = this.maxSegmentBytes;
                }
                break;
            case FIXED_SIZE:
                break;
        }
        return result;
    }

    /**
     * Frees the native memory of every segment allocated by this allocator,
     * unless the backing arena was supplied by (and thus belongs to) the caller.
     */
    @Override
    public void close() {
        if (this.ownsArena) {
            this.arena.close();
        }
    }
}
