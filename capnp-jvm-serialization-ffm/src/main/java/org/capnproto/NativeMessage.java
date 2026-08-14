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

/**
 * A {@link MessageReader} whose segments live in native memory owned by an FFM
 * {@link Arena}. Closing the message frees (or unmaps) the
 * native memory deterministically; any access to the message after that throws
 * {@code IllegalStateException}.
 *
 * <pre>{@code
 * try (NativeMessage message = NativeSerialize.read(channel)) {
 *     Foo.Reader foo = message.getRoot(Foo.factory);
 *     // ...
 * } // native memory freed here
 * }</pre>
 *
 * <p>Messages read through a confined arena (the default) may only be accessed
 * on the thread that read them.
 */
public final class NativeMessage implements AutoCloseable {

    private final MessageReader reader;
    private final Arena arena;

    public NativeMessage(MessageReader reader, Arena arena) {
        this.reader = reader;
        this.arena = arena;
    }

    public MessageReader getReader() {
        return this.reader;
    }

    public <T> T getRoot(FromPointerReader<T> factory) {
        return this.reader.getRoot(factory);
    }

    /** Frees the native memory holding this message's segments. */
    @Override
    public void close() {
        this.arena.close();
    }
}
