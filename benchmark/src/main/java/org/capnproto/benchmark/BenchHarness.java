// In-JVM steady-state benchmark driver for A/B comparison of the runtime
// (ByteBuffer vs. FFM MemorySegment primitives). Not a unit test.
//
// Usage: BenchHarness <case> <mode> <measureIters> <reps> <warmupIters>
//   case: carsales | catrank | eval
//   mode: object | bytes | bytes-packed
//
// Emits one machine-readable line per run:
//   RESULT <case> <mode> <measureIters> medianNs=<> minNs=<> maxNs=<> nsPerIter=<>
package org.capnproto.benchmark;

import java.util.Arrays;

import org.capnproto.benchmark.CarSalesSchema.ParkingLot;
import org.capnproto.benchmark.CarSalesSchema.TotalValue;
import org.capnproto.benchmark.CatRankSchema.SearchResultList;
import org.capnproto.benchmark.EvalSchema.Expression;
import org.capnproto.benchmark.EvalSchema.EvaluationResult;

public class BenchHarness {

    interface Runner { void run(long iters) throws Exception; }

    static Runner make(String kase, String mode) {
        final Compression comp =
            mode.startsWith("bytes-packed") ? Compression.PACKED : Compression.UNCOMPRESSED;
        final boolean bytes = mode.startsWith("bytes");
        final boolean arena = mode.endsWith("-arena");
        switch (kase) {
            case "carsales": {
                final CarSales tc = new CarSales();
                return iters -> {
                    if (arena) {
                        if (bytes) tc.passByBytesArena(ParkingLot.factory, TotalValue.factory, comp, iters);
                        else       tc.passByObjectArena(ParkingLot.factory, TotalValue.factory, iters);
                    } else {
                        if (bytes) tc.passByBytes(ParkingLot.factory, TotalValue.factory, false, comp, iters);
                        else       tc.passByObject(ParkingLot.factory, TotalValue.factory, false, comp, iters);
                    }
                };
            }
            case "catrank": {
                final CatRank tc = new CatRank();
                return iters -> {
                    if (arena) {
                        if (bytes) tc.passByBytesArena(SearchResultList.factory, SearchResultList.factory, comp, iters);
                        else       tc.passByObjectArena(SearchResultList.factory, SearchResultList.factory, iters);
                    } else {
                        if (bytes) tc.passByBytes(SearchResultList.factory, SearchResultList.factory, false, comp, iters);
                        else       tc.passByObject(SearchResultList.factory, SearchResultList.factory, false, comp, iters);
                    }
                };
            }
            case "eval": {
                final Eval tc = new Eval();
                return iters -> {
                    if (arena) {
                        if (bytes) tc.passByBytesArena(Expression.factory, EvaluationResult.factory, comp, iters);
                        else       tc.passByObjectArena(Expression.factory, EvaluationResult.factory, iters);
                    } else {
                        if (bytes) tc.passByBytes(Expression.factory, EvaluationResult.factory, false, comp, iters);
                        else       tc.passByObject(Expression.factory, EvaluationResult.factory, false, comp, iters);
                    }
                };
            }
            default:
                throw new IllegalArgumentException("unknown case: " + kase);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("USAGE: BenchHarness <case> <mode> [measureIters] [reps] [warmupIters]");
            return;
        }
        String kase = args[0];
        String mode = args[1];
        long measureIters = args.length > 2 ? Long.parseLong(args[2]) : 50000;
        int reps         = args.length > 3 ? Integer.parseInt(args[3]) : 7;
        long warmupIters = args.length > 4 ? Long.parseLong(args[4]) : measureIters;

        Runner r = make(kase, mode);

        // Warm up (drive the JIT to steady state); two warmup batches.
        r.run(warmupIters);
        r.run(warmupIters);

        long[] times = new long[reps];
        for (int i = 0; i < reps; ++i) {
            long t0 = System.nanoTime();
            r.run(measureIters);
            long t1 = System.nanoTime();
            times[i] = t1 - t0;
        }
        long[] sorted = times.clone();
        Arrays.sort(sorted);
        long median = sorted[sorted.length / 2];
        long min = sorted[0];
        long max = sorted[sorted.length - 1];
        double nsPerIter = (double) median / measureIters;

        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < reps; ++i) {
            if (i > 0) raw.append(',');
            raw.append(times[i]);
        }
        System.out.printf(
            "RESULT %s %s %d medianNs=%d minNs=%d maxNs=%d nsPerIter=%.1f reps=[%s]%n",
            kase, mode, measureIters, median, min, max, nsPerIter, raw.toString());
    }
}
