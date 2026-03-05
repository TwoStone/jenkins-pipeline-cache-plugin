package io.jenkins.plugins.pipeline.cache;

import io.jenkins.plugins.pipeline.cache.s3.CacheItemRepository;
import io.jenkins.plugins.pipeline.cache.s3.CacheItem;
import io.jenkins.plugins.pipeline.cache.s3.S3OutputStream;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Integration benchmarks for {@link S3OutputStream} and {@link CacheItemRepository}.
 * <p>
 * These benchmarks measure real S3 throughput against a MinIO container and are
 * NOT included in normal test runs. Run them with:
 * <pre>
 *   mvn verify -Pbenchmarks
 * </pre>
 */
public class CacheBenchmark {

    private static final int KB = 1024;
    private static final int MB = 1024 * KB;

    @ClassRule
    public static MinioContainer minio = new MinioContainer();

    @ClassRule
    public static MinioMcContainer mc = new MinioMcContainer(minio);

    private String bucket;
    private CacheItemRepository repository;

    @Before
    public void setUp() {
        bucket = UUID.randomUUID().toString();
        mc.createBucket(bucket);
        repository = new CacheItemRepository(
                minio.accessKey(),
                minio.secretKey(),
                "us-west-1",
                minio.getExternalAddress(),
                bucket);
    }

    // -------------------------------------------------------------------------
    // S3OutputStream benchmarks
    // -------------------------------------------------------------------------

    @Test
    public void benchmarkSmallUpload_1MB() throws Exception {
        byte[] payload = generateRandomBytes(1 * MB);
        String key = "bench-small-1mb-" + UUID.randomUUID();

        long nanos = timedRun(() -> {
            try (OutputStream out = repository.createObjectOutputStream(key)) {
                out.write(payload);
            }
        });

        reportResult("small-upload (1 MB, bulk write)", nanos, payload.length);
    }

    @Test
    public void benchmarkSmallUpload_5MB() throws Exception {
        byte[] payload = generateRandomBytes(5 * MB);
        String key = "bench-small-5mb-" + UUID.randomUUID();

        long nanos = timedRun(() -> {
            try (OutputStream out = repository.createObjectOutputStream(key)) {
                out.write(payload);
            }
        });

        reportResult("small-upload (5 MB, bulk write)", nanos, payload.length);
    }

    @Test
    public void benchmarkLargeUpload_50MB() throws Exception {
        byte[] payload = generateRandomBytes(50 * MB);
        String key = "bench-large-50mb-" + UUID.randomUUID();

        long nanos = timedRun(() -> {
            try (OutputStream out = repository.createObjectOutputStream(key)) {
                out.write(payload);
            }
        });

        reportResult("large-upload (50 MB, bulk write)", nanos, payload.length);
    }

    @Test
    public void benchmarkLargeUpload_100MB() throws Exception {
        byte[] payload = generateRandomBytes(100 * MB);
        String key = "bench-large-100mb-" + UUID.randomUUID();

        long nanos = timedRun(() -> {
            try (OutputStream out = repository.createObjectOutputStream(key)) {
                out.write(payload);
            }
        });

        reportResult("large-upload (100 MB, bulk write)", nanos, payload.length);
    }

    @Test
    public void benchmarkLargeUpload_250MB() throws Exception {
        int totalSize = 250 * MB;
        byte[] chunk = generateRandomBytes(MB);
        String key = "bench-large-250mb-" + UUID.randomUUID();

        long nanos = timedRun(() -> {
            try (OutputStream out = repository.createObjectOutputStream(key)) {
                for (int written = 0; written < totalSize; written += chunk.length) {
                    out.write(chunk, 0, Math.min(chunk.length, totalSize - written));
                }
            }
        });

        reportResult("large-upload (250 MB, 1 MB chunks)", nanos, totalSize);
    }

    @Test
    public void benchmarkLargeUpload_500MB() throws Exception {
        int totalSize = 500 * MB;
        byte[] chunk = generateRandomBytes(MB);
        String key = "bench-large-500mb-" + UUID.randomUUID();

        long nanos = timedRun(() -> {
            try (OutputStream out = repository.createObjectOutputStream(key)) {
                for (int written = 0; written < totalSize; written += chunk.length) {
                    out.write(chunk, 0, Math.min(chunk.length, totalSize - written));
                }
            }
        });

        reportResult("large-upload (500 MB, 1 MB chunks)", nanos, totalSize);
    }

    @Test
    public void benchmarkLargeUpload_1GB() throws Exception {
        long totalSize = 1024L * MB;
        byte[] chunk = generateRandomBytes(MB);
        String key = "bench-large-1gb-" + UUID.randomUUID();

        long nanos = timedRun(() -> {
            try (OutputStream out = repository.createObjectOutputStream(key)) {
                for (long written = 0; written < totalSize; written += chunk.length) {
                    out.write(chunk, 0, (int) Math.min(chunk.length, totalSize - written));
                }
            }
        });

        reportResult("large-upload (1 GB, 1 MB chunks)", nanos, totalSize);
    }

    @Test
    public void benchmarkLargeRoundtrip_500MB() throws Exception {
        int totalSize = 500 * MB;
        byte[] chunk = generateRandomBytes(MB);
        String key = "bench-roundtrip-500mb-" + UUID.randomUUID();

        // Upload
        long uploadNanos = timedRun(() -> {
            try (OutputStream out = repository.createObjectOutputStream(key)) {
                for (int written = 0; written < totalSize; written += chunk.length) {
                    out.write(chunk, 0, Math.min(chunk.length, totalSize - written));
                }
            }
        });
        reportResult("large-roundtrip-upload (500 MB)", uploadNanos, totalSize);

        // Download
        long downloadNanos = timedRun(() -> {
            try (var in = repository.getS3Object(key)) {
                byte[] buf = new byte[MB];
                while (in.read(buf) != -1) {
                    // drain
                }
            }
        });
        reportResult("large-roundtrip-download (500 MB)", downloadNanos, totalSize);
    }

    @Test
    public void benchmarkSingleByteWrite_1MB() throws Exception {
        byte[] payload = generateRandomBytes(1 * MB);
        String key = "bench-singlebyte-1mb-" + UUID.randomUUID();

        long nanos = timedRun(() -> {
            try (OutputStream out = repository.createObjectOutputStream(key)) {
                for (byte b : payload) {
                    out.write(b);
                }
            }
        });

        reportResult("single-byte-write (1 MB)", nanos, payload.length);
    }

    @Test
    public void benchmarkChunkedWrite_50MB() throws Exception {
        // Write 50 MB in 64 KB chunks — simulates typical buffered I/O from tar streams
        int totalSize = 50 * MB;
        int chunkSize = 64 * KB;
        byte[] chunk = generateRandomBytes(chunkSize);
        String key = "bench-chunked-50mb-" + UUID.randomUUID();

        long nanos = timedRun(() -> {
            try (OutputStream out = repository.createObjectOutputStream(key)) {
                int remaining = totalSize;
                while (remaining > 0) {
                    int len = Math.min(chunkSize, remaining);
                    out.write(chunk, 0, len);
                    remaining -= len;
                }
            }
        });

        reportResult("chunked-write (50 MB, 64 KB chunks)", nanos, totalSize);
    }

    @Test
    public void benchmarkVaryingPartSizes() throws Exception {
        int payloadSize = 30 * MB;
        byte[] payload = generateRandomBytes(payloadSize);
        long[] partSizes = {5L * MB, 10L * MB, 20L * MB};

        for (long partSize : partSizes) {
            String key = "bench-partsize-" + (partSize / MB) + "mb-" + UUID.randomUUID();

            long nanos = timedRun(() -> {
                try (S3OutputStream out = new S3OutputStream(
                        createS3AsyncClient(partSize), bucket, key)) {
                    out.write(payload);
                }
            });

            reportResult("part-size-" + (partSize / MB) + "MB (30 MB payload)", nanos, payloadSize);
        }
    }

    // -------------------------------------------------------------------------
    // CacheItemRepository benchmarks
    // -------------------------------------------------------------------------

    @Test
    public void benchmarkUploadAndDownload() throws Exception {
        int payloadSize = 10 * MB;
        byte[] payload = generateRandomBytes(payloadSize);
        String key = "bench-roundtrip-" + UUID.randomUUID();

        // Upload
        long uploadNanos = timedRun(() -> {
            try (OutputStream out = repository.createObjectOutputStream(key)) {
                out.write(payload);
            }
        });
        reportResult("roundtrip-upload (10 MB)", uploadNanos, payloadSize);

        // Download
        long downloadNanos = timedRun(() -> {
            try (ResponseInputStream<GetObjectResponse> in = repository.getS3Object(key)) {
                in.readAllBytes();
            }
        });
        reportResult("roundtrip-download (10 MB)", downloadNanos, payloadSize);
    }

    @Test
    public void benchmarkExists() throws Exception {
        // Upload a small object
        String existingKey = "bench-exists-" + UUID.randomUUID();
        uploadPayload(existingKey, 1 * KB);

        String missingKey = "bench-missing-" + UUID.randomUUID();

        // Warm up
        repository.exists(existingKey);
        repository.exists(missingKey);

        // Benchmark existing key
        int iterations = 50;
        long existsNanos = timedRunN(iterations, () -> repository.exists(existingKey));
        reportLatency("exists (key found)", existsNanos, iterations);

        // Benchmark missing key
        long missingNanos = timedRunN(iterations, () -> repository.exists(missingKey));
        reportLatency("exists (key not found)", missingNanos, iterations);
    }

    @Test
    public void benchmarkFindRestoreKeyExact() throws Exception {
        String key = "bench-restore-exact-" + UUID.randomUUID();
        uploadPayload(key, 1 * KB);

        // Warm up
        repository.findRestoreKey(key);

        int iterations = 20;
        long nanos = timedRunN(iterations, () -> repository.findRestoreKey(key));
        reportLatency("findRestoreKey (exact match)", nanos, iterations);
    }

    @Test
    public void benchmarkFindRestoreKeyPrefix() throws Exception {
        // Create 10 items with a shared prefix
        String prefix = "bench-prefix-" + UUID.randomUUID() + "/";
        for (int i = 0; i < 10; i++) {
            uploadPayload(prefix + "item-" + i, 1 * KB);
        }

        // Warm up
        repository.findRestoreKey(null, prefix);

        int iterations = 10;
        long nanos = timedRunN(iterations, () -> repository.findRestoreKey(null, prefix));
        reportLatency("findRestoreKey (prefix, 10 items)", nanos, iterations);
    }

    @Test
    public void benchmarkGetTotalCacheSize() throws Exception {
        int[] itemCounts = {10, 50, 100};

        for (int count : itemCounts) {
            // Create a fresh bucket for each test
            String b = UUID.randomUUID().toString();
            mc.createBucket(b);
            CacheItemRepository repo = new CacheItemRepository(
                    minio.accessKey(), minio.secretKey(),
                    "us-west-1", minio.getExternalAddress(), b);

            // Populate
            for (int i = 0; i < count; i++) {
                try (OutputStream out = repo.createObjectOutputStream("size-item-" + i)) {
                    out.write(generateRandomBytes(1 * KB));
                }
            }

            // Warm up
            repo.getTotalCacheSize();

            int iterations = 10;
            long nanos = timedRunN(iterations, () -> repo.getTotalCacheSize());
            reportLatency("getTotalCacheSize (" + count + " items)", nanos, iterations);
        }
    }

    @Test
    public void benchmarkUpdateLastAccess() throws Exception {
        String key = "bench-lastaccess-" + UUID.randomUUID();
        uploadPayload(key, 1 * KB);

        // First call will update (access time is 0, so threshold is exceeded)
        long nanos = timedRun(() -> repository.updateLastAccess(key));
        reportLatency("updateLastAccess (with copy)", nanos, 1);

        // Second call should skip (within 5-min threshold)
        long skipNanos = timedRun(() -> repository.updateLastAccess(key));
        reportLatency("updateLastAccess (skipped/threshold)", skipNanos, 1);
    }

    @Test
    public void benchmarkFindAll() throws Exception {
        // Populate with 50 items
        for (int i = 0; i < 50; i++) {
            uploadPayload("findall-item-" + i, 1 * KB);
        }

        // Warm up
        repository.findAll().count();

        int iterations = 10;
        long nanos = timedRunN(iterations, () -> repository.findAll().count());
        reportLatency("findAll (50 items)", nanos, iterations);
    }

    @Test
    public void benchmarkDelete() throws Exception {
        // Create 20 items to delete
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String key = "bench-delete-" + i;
            uploadPayload(key, 1 * KB);
            keys.add(key);
        }

        long nanos = timedRun(() -> repository.delete(keys.stream()));
        reportLatency("delete (20 items)", nanos, 1);
    }

    @Test
    public void benchmarkGetContentLength() throws Exception {
        String key = "bench-contentlen-" + UUID.randomUUID();
        uploadPayload(key, 5 * MB);

        // Warm up
        repository.getContentLength(key);

        int iterations = 50;
        long nanos = timedRunN(iterations, () -> repository.getContentLength(key));
        reportLatency("getContentLength", nanos, iterations);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void uploadPayload(String key, int sizeInBytes) throws Exception {
        byte[] data = generateRandomBytes(sizeInBytes);
        try (OutputStream out = repository.createObjectOutputStream(key)) {
            out.write(data);
        }
    }

    private software.amazon.awssdk.services.s3.S3Client createS3Client() {
        return software.amazon.awssdk.services.s3.S3Client.builder()
                .forcePathStyle(true)
                .region(software.amazon.awssdk.regions.Region.of("us-west-1"))
                .endpointOverride(java.net.URI.create(minio.getExternalAddress()))
                .credentialsProvider(
                        software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                                software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(
                                        minio.accessKey(), minio.secretKey())))
                .build();
    }

    private S3AsyncClient createS3AsyncClient(long partSize) {
        return S3AsyncClient.builder()
                .multipartEnabled(true)
                .multipartConfiguration(cfg -> cfg
                        .minimumPartSizeInBytes(partSize)
                        .thresholdInBytes(partSize))
                .forcePathStyle(true)
                .region(Region.of("us-west-1"))
                .endpointOverride(URI.create(minio.getExternalAddress()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(minio.accessKey(), minio.secretKey())))
                .build();
    }

    private static byte[] generateRandomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random(42).nextBytes(bytes);
        return bytes;
    }

    private static long timedRun(ThrowingRunnable runnable) throws Exception {
        long start = System.nanoTime();
        runnable.run();
        return System.nanoTime() - start;
    }

    private static long timedRunN(int iterations, ThrowingRunnable runnable) throws Exception {
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            runnable.run();
        }
        return System.nanoTime() - start;
    }

    private static void reportResult(String name, long nanos, long bytes) {
        double ms = nanos / 1_000_000.0;
        double seconds = nanos / 1_000_000_000.0;
        double mbPerSec = (bytes / (double) MB) / seconds;
        System.out.printf("[BENCHMARK] %-45s %8.1f ms  %6.1f MB/s%n", name, ms, mbPerSec);
    }

    private static void reportLatency(String name, long totalNanos, int iterations) {
        double avgMs = (totalNanos / 1_000_000.0) / iterations;
        double totalMs = totalNanos / 1_000_000.0;
        if (iterations == 1) {
            System.out.printf("[BENCHMARK] %-45s %8.1f ms%n", name, totalMs);
        } else {
            System.out.printf("[BENCHMARK] %-45s %8.1f ms avg (%d iterations, %.1f ms total)%n",
                    name, avgMs, iterations, totalMs);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
