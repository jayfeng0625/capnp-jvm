// JMX-instrumented driver: measures per-iteration heap allocation and GC
// activity (and direct-buffer pool usage) so we can quantify whether the FFM
// runtime adds allocation/copies vs. the ByteBuffer runtime. Not a unit test.
//
// Usage: BenchHarnessJmx <case> <mode> [measureIters] [warmupIters]
package org.capnproto.benchmark;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

import org.capnproto.benchmark.CarSalesSchema.ParkingLot;
import org.capnproto.benchmark.CarSalesSchema.TotalValue;
import org.capnproto.benchmark.CatRankSchema.SearchResultList;
import org.capnproto.benchmark.EvalSchema.Expression;
import org.capnproto.benchmark.EvalSchema.EvaluationResult;

public class BenchHarnessJmx {

    interface Runner { void run(long iters) throws Exception; }

    static Runner make(String kase, String mode) {
        final Compression comp =
            mode.equals("bytes-packed") ? Compression.PACKED : Compression.UNCOMPRESSED;
        final boolean bytes = mode.startsWith("bytes");
        switch (kase) {
            case "carsales": {
                final CarSales tc = new CarSales();
                return iters -> { if (bytes) tc.passByBytes(ParkingLot.factory, TotalValue.factory, false, comp, iters);
                                  else       tc.passByObject(ParkingLot.factory, TotalValue.factory, false, comp, iters); };
            }
            case "catrank": {
                final CatRank tc = new CatRank();
                return iters -> { if (bytes) tc.passByBytes(SearchResultList.factory, SearchResultList.factory, false, comp, iters);
                                  else       tc.passByObject(SearchResultList.factory, SearchResultList.factory, false, comp, iters); };
            }
            case "eval": {
                final Eval tc = new Eval();
                return iters -> { if (bytes) tc.passByBytes(Expression.factory, EvaluationResult.factory, false, comp, iters);
                                  else       tc.passByObject(Expression.factory, EvaluationResult.factory, false, comp, iters); };
            }
            default: throw new IllegalArgumentException("unknown case: " + kase);
        }
    }

    static long directPoolUsed() {
        for (BufferPoolMXBean b : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if (b.getName().equals("direct")) return b.getMemoryUsed();
        }
        return -1;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.out.println("USAGE: BenchHarnessJmx <case> <mode> [measureIters] [warmupIters]"); return; }
        String kase = args[0], mode = args[1];
        long measureIters = args.length > 2 ? Long.parseLong(args[2]) : 20000;
        long warmupIters  = args.length > 3 ? Long.parseLong(args[3]) : measureIters;

        com.sun.management.ThreadMXBean tmx =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
        long tid = Thread.currentThread().getId();

        Runner r = make(kase, mode);
        r.run(warmupIters);
        r.run(warmupIters);

        long gcCount0 = 0, gcTime0 = 0;
        for (GarbageCollectorMXBean g : gcs) { gcCount0 += g.getCollectionCount(); gcTime0 += g.getCollectionTime(); }
        long alloc0 = tmx.getThreadAllocatedBytes(tid);
        long direct0 = directPoolUsed();
        long t0 = System.nanoTime();

        r.run(measureIters);

        long t1 = System.nanoTime();
        long alloc1 = tmx.getThreadAllocatedBytes(tid);
        long direct1 = directPoolUsed();
        long gcCount1 = 0, gcTime1 = 0;
        for (GarbageCollectorMXBean g : gcs) { gcCount1 += g.getCollectionCount(); gcTime1 += g.getCollectionTime(); }

        double nsPerIter = (double)(t1 - t0) / measureIters;
        double bytesPerIter = (double)(alloc1 - alloc0) / measureIters;

        System.out.printf(
            "JMX %s %s iters=%d nsPerIter=%.1f heapBytesPerIter=%.1f gcCount=%d gcTimeMs=%d directPoolDeltaBytes=%d%n",
            kase, mode, measureIters, nsPerIter, bytesPerIter,
            (gcCount1 - gcCount0), (gcTime1 - gcTime0), (direct1 - direct0));
    }
}
