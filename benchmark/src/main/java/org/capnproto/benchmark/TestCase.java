// Copyright (c) 2013-2014 Sandstorm Development Group, Inc. and contributors
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
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileDescriptor;

import org.capnproto.ArenaAllocator;
import org.capnproto.MemorySegmentInputStream;
import org.capnproto.MemorySegmentOutputStream;
import org.capnproto.FfmBufferedInputStream;
import org.capnproto.FfmBufferedOutputStream;
import org.capnproto.StructFactory;
import org.capnproto.MessageBuilder;
import org.capnproto.MessageReader;

public abstract class TestCase<RequestFactory extends
                          StructFactory<RequestBuilder, RequestReader>,
                          RequestBuilder extends org.capnproto.StructBuilder,
                          RequestReader extends org.capnproto.StructReader,
                          ResponseFactory extends StructFactory<ResponseBuilder, ResponseReader>,
                          ResponseBuilder extends org.capnproto.StructBuilder,
                          ResponseReader extends org.capnproto.StructReader, Expectation> {
    public abstract Expectation setupRequest(Common.FastRand rng, RequestBuilder request);
    public abstract void handleRequest(RequestReader request, ResponseBuilder response);
    public abstract boolean checkResponse(ResponseReader response, Expectation expected);

    static final int SCRATCH_SIZE = 128 * 1024;
    ByteBuffer requestScratchSpace = ByteBuffer.allocate(SCRATCH_SIZE * 8);
    ByteBuffer responseScratchSpace = ByteBuffer.allocate(SCRATCH_SIZE * 8);

    public MessageBuilder newMessageBuilder(boolean useScratchSpace, ByteBuffer scratchSpace) {
        if (useScratchSpace) {
            return new MessageBuilder(scratchSpace);
        } else {
            return new MessageBuilder();
        }
    }

    public void passByObject(RequestFactory requestFactory, ResponseFactory responseFactory,
                             boolean reuse, Compression compression, long iters) {

        Common.FastRand rng = new Common.FastRand();

        for (int i = 0; i < iters; ++i) {
            MessageBuilder requestMessage = newMessageBuilder(reuse, requestScratchSpace);
            MessageBuilder responseMessage = newMessageBuilder(reuse, responseScratchSpace);
            RequestBuilder request = requestMessage.initRoot(requestFactory);
            Expectation expected = this.setupRequest(rng, request);
            ResponseBuilder response = responseMessage.initRoot(responseFactory);
            this.handleRequest(requestFactory.asReader(request), response);
            if (!this.checkResponse(responseFactory.asReader(response), expected)) {
                System.out.println("mismatch!");
            }
            if (reuse) {
                requestMessage.clearFirstSegment();
                responseMessage.clearFirstSegment();
            }
        }
    }

    // Like passByObject with no-reuse, but message memory lives off-heap in
    // FFM arenas that are freed deterministically at the end of each iteration.
    public void passByObjectArena(RequestFactory requestFactory, ResponseFactory responseFactory,
                                  long iters) {
        Common.FastRand rng = new Common.FastRand();

        for (int i = 0; i < iters; ++i) {
            try (ArenaAllocator requestAllocator = new ArenaAllocator();
                 ArenaAllocator responseAllocator = new ArenaAllocator()) {
                MessageBuilder requestMessage = new MessageBuilder(requestAllocator);
                MessageBuilder responseMessage = new MessageBuilder(responseAllocator);
                RequestBuilder request = requestMessage.initRoot(requestFactory);
                Expectation expected = this.setupRequest(rng, request);
                ResponseBuilder response = responseMessage.initRoot(responseFactory);
                this.handleRequest(requestFactory.asReader(request), response);
                if (!this.checkResponse(responseFactory.asReader(response), expected)) {
                    System.out.println("mismatch!");
                }
            }
        }
    }

    // Like passByBytes with no-reuse, but native end to end: builder message
    // memory, the serialized bytes, and the read-side messages all live in FFM
    // arena memory. The per-iteration arena is freed deterministically at the
    // end of each iteration; the serialized-bytes scratch is allocated once.
    public void passByBytesArena(RequestFactory requestFactory, ResponseFactory responseFactory,
                                 Compression compression, long iters) throws IOException {
        FfmCompression ffmCompression = FfmCompression.of(compression);

        try (Arena scratchArena = Arena.ofConfined()) {
            MemorySegment requestBytes = scratchArena.allocate(SCRATCH_SIZE * 8, 8);
            MemorySegment responseBytes = scratchArena.allocate(SCRATCH_SIZE * 8, 8);
            Common.FastRand rng = new Common.FastRand();

            for (int i = 0; i < iters; ++i) {
                try (Arena arena = Arena.ofConfined()) {
                    MessageBuilder requestMessage = new MessageBuilder(new ArenaAllocator(arena));
                    MessageBuilder responseMessage = new MessageBuilder(new ArenaAllocator(arena));
                    RequestBuilder request = requestMessage.initRoot(requestFactory);
                    Expectation expected = this.setupRequest(rng, request);
                    ResponseBuilder response = responseMessage.initRoot(responseFactory);

                    {
                        MemorySegmentOutputStream writer =
                            new MemorySegmentOutputStream(requestBytes);
                        ffmCompression.writeBuffered(writer, requestMessage);
                    }

                    {
                        MessageReader messageReader = ffmCompression.newBufferedReader(
                            new MemorySegmentInputStream(requestBytes), arena);
                        this.handleRequest(messageReader.getRoot(requestFactory), response);
                    }

                    {
                        MemorySegmentOutputStream writer =
                            new MemorySegmentOutputStream(responseBytes);
                        ffmCompression.writeBuffered(writer, responseMessage);
                    }

                    {
                        MessageReader messageReader = ffmCompression.newBufferedReader(
                            new MemorySegmentInputStream(responseBytes), arena);
                        if (!this.checkResponse(messageReader.getRoot(responseFactory), expected)) {
                            throw new Error("incorrect response");
                        }
                    }
                }
            }
        }
    }

    public void passByBytes(RequestFactory requestFactory, ResponseFactory responseFactory,
                            boolean reuse, Compression compression, long iters) throws IOException {

        ByteBuffer requestBytes = ByteBuffer.allocate(SCRATCH_SIZE * 8);
        ByteBuffer responseBytes = ByteBuffer.allocate(SCRATCH_SIZE * 8);
        Common.FastRand rng = new Common.FastRand();

        for (int i = 0; i < iters; ++i) {
            MessageBuilder requestMessage = newMessageBuilder(reuse, requestScratchSpace);
            MessageBuilder responseMessage = newMessageBuilder(reuse, responseScratchSpace);
            RequestBuilder request = requestMessage.initRoot(requestFactory);
            Expectation expected = this.setupRequest(rng, request);
            ResponseBuilder response = responseMessage.initRoot(responseFactory);

            {
                org.capnproto.ArrayOutputStream writer = new org.capnproto.ArrayOutputStream(requestBytes);
                compression.writeBuffered(writer, requestMessage);
            }

            {
                org.capnproto.MessageReader messageReader =
                    compression.newBufferedReader(new org.capnproto.ArrayInputStream(requestBytes));
                this.handleRequest(messageReader.getRoot(requestFactory), response);
            }

            {
                org.capnproto.ArrayOutputStream writer = new org.capnproto.ArrayOutputStream(responseBytes);
                compression.writeBuffered(writer, responseMessage);
            }

            {
                org.capnproto.MessageReader messageReader =
                    compression.newBufferedReader(new org.capnproto.ArrayInputStream(responseBytes));
                if (!this.checkResponse(messageReader.getRoot(responseFactory), expected)) {
                    throw new Error("incorrect response");
                }
            }

            if (reuse) {
                requestMessage.clearFirstSegment();
                responseMessage.clearFirstSegment();
            }
        }
    }

    public void syncServer(RequestFactory requestFactory, ResponseFactory responseFactory,
                           boolean reuse, Compression compression, long iters) throws IOException {
        org.capnproto.BufferedOutputStreamWrapper outBuffered =
            new org.capnproto.BufferedOutputStreamWrapper((new FileOutputStream(FileDescriptor.out)).getChannel());
        org.capnproto.BufferedInputStreamWrapper inBuffered =
            new org.capnproto.BufferedInputStreamWrapper((new FileInputStream(FileDescriptor.in)).getChannel());

        for (int ii = 0; ii < iters; ++ii) {
            MessageBuilder responseMessage = newMessageBuilder(reuse, responseScratchSpace);
            {
                ResponseBuilder response = responseMessage.initRoot(responseFactory);
                MessageReader messageReader = compression.newBufferedReader(inBuffered);
                RequestReader request = messageReader.getRoot(requestFactory);
                this.handleRequest(request, response);
            }
            compression.writeBuffered(outBuffered, responseMessage);
            if (reuse) {
                responseMessage.clearFirstSegment();
            }
        }
    }

    public void syncClient(RequestFactory requestFactory, ResponseFactory responseFactory,
                           boolean reuse, Compression compression, long iters) throws IOException {
        Common.FastRand rng = new Common.FastRand();
        org.capnproto.BufferedOutputStreamWrapper outBuffered =
            new org.capnproto.BufferedOutputStreamWrapper((new FileOutputStream(FileDescriptor.out)).getChannel());
        org.capnproto.BufferedInputStreamWrapper inBuffered =
            new org.capnproto.BufferedInputStreamWrapper((new FileInputStream(FileDescriptor.in)).getChannel());

        for (int ii = 0; ii < iters; ++ii) {
            MessageBuilder requestMessage = newMessageBuilder(reuse, requestScratchSpace);
            RequestBuilder request = requestMessage.initRoot(requestFactory);
            Expectation expected = this.setupRequest(rng, request);

            compression.writeBuffered(outBuffered, requestMessage);
            MessageReader messageReader = compression.newBufferedReader(inBuffered);
            ResponseReader response = messageReader.getRoot(responseFactory);
            if (!this.checkResponse(response, expected)) {
                throw new Error("incorrect response");
            }
            if (reuse) {
                requestMessage.clearFirstSegment();
            }
        }
    }

    // Like syncServer, but message memory lives off-heap in an FFM arena freed
    // at the end of each iteration, and the pipe is buffered in native memory.
    public void syncServerArena(RequestFactory requestFactory, ResponseFactory responseFactory,
                                Compression compression, long iters) throws IOException {
        FfmCompression ffmCompression = FfmCompression.of(compression);
        FfmBufferedOutputStream outBuffered =
            new FfmBufferedOutputStream((new FileOutputStream(FileDescriptor.out)).getChannel());
        FfmBufferedInputStream inBuffered =
            new FfmBufferedInputStream((new FileInputStream(FileDescriptor.in)).getChannel());

        for (int ii = 0; ii < iters; ++ii) {
            try (Arena arena = Arena.ofConfined()) {
                MessageBuilder responseMessage = new MessageBuilder(new ArenaAllocator(arena));
                {
                    ResponseBuilder response = responseMessage.initRoot(responseFactory);
                    MessageReader messageReader = ffmCompression.newBufferedReader(inBuffered, arena);
                    RequestReader request = messageReader.getRoot(requestFactory);
                    this.handleRequest(request, response);
                }
                ffmCompression.writeBuffered(outBuffered, responseMessage);
            }
        }
    }

    // Like syncClient, but message memory lives off-heap in an FFM arena freed
    // at the end of each iteration, and the pipe is buffered in native memory.
    public void syncClientArena(RequestFactory requestFactory, ResponseFactory responseFactory,
                                Compression compression, long iters) throws IOException {
        FfmCompression ffmCompression = FfmCompression.of(compression);
        Common.FastRand rng = new Common.FastRand();
        FfmBufferedOutputStream outBuffered =
            new FfmBufferedOutputStream((new FileOutputStream(FileDescriptor.out)).getChannel());
        FfmBufferedInputStream inBuffered =
            new FfmBufferedInputStream((new FileInputStream(FileDescriptor.in)).getChannel());

        for (int ii = 0; ii < iters; ++ii) {
            try (Arena arena = Arena.ofConfined()) {
                MessageBuilder requestMessage = new MessageBuilder(new ArenaAllocator(arena));
                RequestBuilder request = requestMessage.initRoot(requestFactory);
                Expectation expected = this.setupRequest(rng, request);

                ffmCompression.writeBuffered(outBuffered, requestMessage);
                MessageReader messageReader = ffmCompression.newBufferedReader(inBuffered, arena);
                ResponseReader response = messageReader.getRoot(responseFactory);
                if (!this.checkResponse(response, expected)) {
                    throw new Error("incorrect response");
                }
            }
        }
    }

    public void execute(String[] args, RequestFactory requestFactory, ResponseFactory responseFactory) {

        if (args.length != 4) {
            System.out.println("USAGE: TestCase MODE REUSE COMPRESSION ITERATION_COUNT");
            return;
        }

        String mode = args[0];
        boolean reuse = false;
        boolean arena = false;
        if (args[1].equals("reuse")) {
            reuse = true;
        } else if (args[1].equals("no-reuse")) {
            reuse = false;
        } else if (args[1].equals("arena")) {
            arena = true;
        } else {
            throw new Error("REUSE must be 'reuse', 'no-reuse', or 'arena'.");
        }
        Compression compression = null;
        if (args[2].equals("packed")) {
            compression = Compression.PACKED;
        } else if (args[2].equals("none")) {
            compression = Compression.UNCOMPRESSED;
        } else {
            throw new Error("unrecognized compression: " + args[2]);
        }
        long iters = Long.parseLong(args[3]);

        try {
            if (mode.equals("object")) {
                if (arena) {
                    passByObjectArena(requestFactory, responseFactory, iters);
                } else {
                    passByObject(requestFactory, responseFactory, reuse, compression, iters);
                }
            } else if (mode.equals("bytes")) {
                if (arena) {
                    passByBytesArena(requestFactory, responseFactory, compression, iters);
                } else {
                    passByBytes(requestFactory, responseFactory, reuse, compression, iters);
                }
            } else if (mode.equals("client")) {
                if (arena) {
                    syncClientArena(requestFactory, responseFactory, compression, iters);
                } else {
                    syncClient(requestFactory, responseFactory, reuse, compression, iters);
                }
            } else if (mode.equals("server")) {
                if (arena) {
                    syncServerArena(requestFactory, responseFactory, compression, iters);
                } else {
                    syncServer(requestFactory, responseFactory, reuse, compression, iters);
                }
            } else {
                System.out.println("unrecognized mode: " + mode);
            }
        } catch (IOException e) {
            System.err.println("IOException: " + e);
        }
    }
}
