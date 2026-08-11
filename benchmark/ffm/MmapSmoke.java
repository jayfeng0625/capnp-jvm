import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

// Verifies, on stock JDK 25, the three FFM properties the capnp-jvm design
// rationale leans on:
//   1. mmap -> MemorySegment via FileChannel.map(..., Arena)   (zero-copy file read)
//   2. >2 GB single mapping with long indexing                  (impossible with ByteBuffer)
//   3. MAP_SHARED visibility across two independent mappings    (the shared-memory IPC mechanism)
//   4. deterministic unmap at Arena.close()                     (no GC-dependent Cleaner)
//
// Run:  java --enable-native-access=ALL-UNNAMED MmapSmoke /path/to/scratch/file
public class MmapSmoke {
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args.length > 0 ? args[0] : "mmap-smoke.bin");
        long size = 3L * 1024 * 1024 * 1024;           // 3 GB, sparse
        long probe = 2_500_000_000L;                   // an offset ByteBuffer cannot index
        long magic = 0xCA9B9A0BEEF5EEDL;

        try (FileChannel ch1 = FileChannel.open(file, StandardOpenOption.CREATE,
                 StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileChannel ch2 = FileChannel.open(file, StandardOpenOption.READ);
             Arena arenaA = Arena.ofConfined();
             Arena arenaB = Arena.ofConfined()) {

            MemorySegment writer = ch1.map(FileChannel.MapMode.READ_WRITE, 0, size, arenaA);
            System.out.printf("mapped %d bytes (%.1f GB) -> MemorySegment, byteSize=%d%n",
                size, size / 1e9, writer.byteSize());

            writer.set(ValueLayout.JAVA_LONG_UNALIGNED, probe, magic);

            // Independent second mapping of the same file: MAP_SHARED means the
            // kernel gives both mappings the same physical pages -- this is the
            // same mechanism as shared-memory IPC between processes.
            MemorySegment reader = ch2.map(FileChannel.MapMode.READ_ONLY, 0, size, arenaB);
            long seen = reader.get(ValueLayout.JAVA_LONG_UNALIGNED, probe);
            System.out.printf("write via mapping A @%d, read via mapping B: %s%n",
                probe, (seen == magic ? "VISIBLE (shared pages)" : "MISMATCH!"));
            if (seen != magic) throw new AssertionError("shared mapping not visible");
        } // arenas close here: both mappings unmapped deterministically, no GC involved

        long diskUsed = Files.size(file);
        Files.delete(file);
        System.out.printf("arena closed -> unmapped deterministically; file logical size=%d, deleted%n", diskUsed);
        System.out.println("OK: mmap->segment, >2GB long indexing, MAP_SHARED visibility, deterministic unmap");
    }
}
