package org.capnproto;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.capnproto.BuilderArena.AllocationStrategy;

/**
 * An {@link Allocator} that carves message segments out of an FFM
 * {@link java.lang.foreign.Arena} (JEP 454), i.e. native off-heap memory with
 * deterministic lifetime: all segments are freed at {@link #close()} instead of
 * relying on the garbage collector.
 *
 * <p>Unlike per-message {@code ByteBuffer.allocateDirect} (whose memory is only
 * reclaimed when the GC runs the buffer's Cleaner, which can balloon the direct
 * pool under allocation churn), closing this allocator frees the native memory
 * immediately. Accessing a segment of a message after its allocator has been
 * closed throws {@code IllegalStateException}.
 *
 * <p>Typical use, one arena per message:
 * <pre>{@code
 * try (ArenaAllocator allocator = new ArenaAllocator()) {
 *     MessageBuilder message = new MessageBuilder(allocator);
 *     // build, serialize ...
 * } // native memory freed here
 * }</pre>
 *
 * <p>The backing FFM arena is confined: the message must be built and read on
 * the thread that created the allocator.
 */
public final class ArenaAllocator implements Allocator, AutoCloseable {

    private final java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined();

    // (minimum) number of bytes in the next allocation, mirroring DefaultAllocator
    private int nextSize;

    public AllocationStrategy allocationStrategy = AllocationStrategy.GROW_HEURISTICALLY;

    /** See {@link DefaultAllocator#maxSegmentBytes}. */
    public int maxSegmentBytes = Integer.MAX_VALUE - 2;

    public ArenaAllocator() {
        this(BuilderArena.SUGGESTED_FIRST_SEGMENT_WORDS * Constants.BYTES_PER_WORD);
    }

    public ArenaAllocator(int firstSegmentSizeBytes) {
        this.nextSize = firstSegmentSizeBytes;
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

    /** Frees the native memory of every segment allocated by this allocator. */
    @Override
    public void close() {
        this.arena.close();
    }
}
