package io.jenkins.plugins.pipeline.cache;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import io.jenkins.plugins.pipeline.cache.s3.CacheItemRepository;
import io.jenkins.plugins.pipeline.cache.s3.S3OutputStream;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Integration benchmarks for AWS SDK v1 version of the plugin.
 * This class is designed to compile against the pre-migration codebase (commit 08e18e4).
 * <p>
 * Run with: mvn test -Dtest=CacheBenchmarkV1
 */
public class CacheBenchmarkV1 {

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
    // Helpers (v1-specific)
    // -------------------------------------------------------------------------

    private static byte[] computeMd5(byte[] data) {
        try {
            return MessageDigest.getInstance("MD5").digest(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Dummy MD5 for streaming uploads where we don't have all data upfront. */
    private static final byte[] DUMMY_MD5 = new byte[16];

    private AmazonS3 createS3Client() {
        return AmazonS3ClientBuilder.standard()
                .withPathStyleAccessEnabled(true)
                .withCredentials(new AWSStaticCredentialsProvider(
                        new BasicAWSCredentials(minio.accessKey(), minio.secretKey())))
                .withEndpointConfiguration(
                        new AwsClientBuilder.EndpointConfiguration(
                                minio.getExternalAddress(), "us-west-1"))
                .build();
    }

    // -------------------------------------------------------------------------
    // S3OutputStream benchmarks
    // -------------------------------------------------------------------------

    @Test
    public void benchmarkSmallUpload_1MB() throws Exception {
        byte[] payload = generateRandomBytes(1 * MB);
        byte[] md5 = computeMd5(payload);
        String key = "bench-small-1mb-" + UUID.randomUUID();

        long nanos = timedRun(() -> {
            try (OutputStream out = repository.createObjectOutputStream(key, md5)) {
                out.write(payload);
            }
        });

        reportResult("small-upload (1 MB, bulk write)", nanos, payload.length);
    }

    @Test
    public void benchmarkSmallUpload_5MB() throws Exception {
        byte[] payload = generateRandomBytes(5 * MB);
        byte[] md5 = computeMd5(payload);
        String key = "bench-small-5mb-" + UUID.randomUUID();

        long nanos = timedRun(() -> {
            try (OutputStream out = repository.createObjectOutputStream(key, md5)) {
                out.write(payload);
            }
        });

        reportResult("small-upload (5 MB, bulk write)", nanos, payload.length);
    }

    @Test
    public void benchmarkLargeUpload_50MB() throws Exception {
        byte[] payload = generateRandomBytes(50 * MB);
        byte[] md5 = computeMd5(payload);
        String key = "bench-large-50mb-" + UUID.randomUUID();

        long nanos = timedRun(() -> {
            try (OutputStream out = repository.createObjectOutputStream(key, md5)) {
                out.write(payload);
            }
        });

        reportResult("large-upload (50 MB, bulk write)", nanos, payload.length);
    }

    @Test
    public void benchmarkLargeUpload_100MB() throws Exception {
        byte[] payload = generateRandomBytes(100 * MB);
        byte[] md5 = computeMd5(payload);
        String key = "bench-large-100mb-" + UUID.randomUUID();

        long nanos = timedRun(() -> {
            try (OutputStream out = repository.createObjectOutputStream(key, md5)) {
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
            try (OutputStream out = repository.createObjectOutputStream(key, DUMMY_MD5)) {
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
            try (OutputStream out = repository.createObjectOutputStream(key, DUMMY_MD5)) {
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
            try (OutputStream out = repository.createObjectOutputStream(key, DUMMY_MD5)) {
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
            try (OutputStream out = repository.createObjectOutputStream(key, DUMMY_MD5)) {
                for (int written = 0; written < totalSize; written += chunk.length) {
                    out.write(chunk, 0, Math.min(chunk.length, totalSize - written));
                }
            }
        });
        reportResult("large-roundtrip-upload (500 MB)", uploadNanos, totalSize);

        // Download (v1: getS3Object returns com.amazonaws.services.s3.model.S3Object)
        long downloadNanos = timedRun(() -> {
            try (com.amazonaws.services.s3.model.S3Object s3Obj = repository.getS3Object(key);
                 InputStream in = s3Obj.getObjectContent()) {
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
        byte[] md5 = computeMd5(payload);
        String key = "bench-singlebyte-1mb-" + UUID.randomUUID();

        long nanos = timedRun(() -> {
            try (OutputStream out = repository.createObjectOutputStream(key, md5)) {
                for (byte b : payload) {
                    out.write(b);
                }
            }
        });

        reportResult("single-byte-write (1 MB)", nanos, payload.length);
    }

    @Test
    public void benchmarkChunkedWrite_50MB() throws Exception {
        int totalSize = 50 * MB;
        int chunkSize = 64 * KB;
        byte[] chunk = generateRandomBytes(chunkSize);
        String key = "bench-chunked-50mb-" + UUID.randomUUID();

        long nanos = timedRun(() -> {
            try (OutputStream out = repository.createObjectOutputStream(key, DUMMY_MD5)) {
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
    public void benchmarkVaryingBufferSizes() throws Exception {
        int payloadSize = 30 * MB;
        byte[] payload = generateRandomBytes(payloadSize);
        String md5Base64 = Base64.getEncoder().encodeToString(computeMd5(payload));
        int[] bufferSizes = {5 * MB, 10 * MB, 20 * MB};

        for (int bufferSize : bufferSizes) {
            String key = "bench-bufsize-" + (bufferSize / MB) + "mb-" + UUID.randomUUID();

            long nanos = timedRun(() -> {
                // v1: S3OutputStream(AmazonS3, bucket, key, md5String, bufSize)
                try (S3OutputStream out = new S3OutputStream(
                        createS3Client(), bucket, key, md5Base64, bufferSize)) {
                    out.write(payload);
                }
            });

            reportResult("buffer-size-" + (bufferSize / MB) + "MB (30 MB payload)", nanos, payloadSize);
        }
    }

    // -------------------------------------------------------------------------
    // CacheItemRepository benchmarks
    // -------------------------------------------------------------------------

    @Test
    public void benchmarkUploadAndDownload() throws Exception {
        int payloadSize = 10 * MB;
        byte[] payload = generateRandomBytes(payloadSize);
        byte[] md5 = computeMd5(payload);
        String key = "bench-roundtrip-" + UUID.randomUUID();

        // Upload
        long uploadNanos = timedRun(() -> {
            try (OutputStream out = repository.createObjectOutputStream(key, md5)) {
                out.write(payload);
            }
        });
        reportResult("roundtrip-upload (10 MB)", uploadNanos, payloadSize);

        // Download (v1: S3Object)
        long downloadNanos = timedRun(() -> {
            try (com.amazonaws.services.s3.model.S3Object s3Obj = repository.getS3Object(key);
                 InputStream in = s3Obj.getObjectContent()) {
                in.readAllBytes();
            }
        });
        reportResult("roundtrip-download (10 MB)", downloadNanos, payloadSize);
    }

    @Test
    public void benchmarkExists() throws Exception {
        String existingKey = "bench-exists-" + UUID.randomUUID();
        uploadPayload(existingKey, 1 * KB);

        String missingKey = "bench-missing-" + UUID.randomUUID();

        // Warm up
        repository.exists(existingKey);
        repository.exists(missingKey);

        int iterations = 50;
        long existsNanos = timedRunN(iterations, () -> repository.exists(existingKey));
        reportLatency("exists (key found)", existsNanos, iterations);

        long missingNanos = timedRunN(iterations, () -> repository.exists(missingKey));
        reportLatency("exists (key not found)", missingNanos, iterations);
    }

    @Test
    public void benchmarkFindRestoreKeyExact() throws Exception {
        String key = "bench-restore-exact-" + UUID.randomUUID();
        uploadPayload(key, 1 * KB);

        repository.findRestoreKey(key);

        int iterations = 20;
        long nanos = timedRunN(iterations, () -> repository.findRestoreKey(key));
        reportLatency("findRestoreKey (exact match)", nanos, iterations);
    }

    @Test
    public void benchmarkFindRestoreKeyPrefix() throws Exception {
        String prefix = "bench-prefix-" + UUID.randomUUID() + "/";
        for (int i = 0; i < 10; i++) {
            uploadPayload(prefix + "item-" + i, 1 * KB);
        }

        repository.findRestoreKey(null, prefix);

        int iterations = 10;
        long nanos = timedRunN(iterations, () -> repository.findRestoreKey(null, prefix));
        reportLatency("findRestoreKey (prefix, 10 items)", nanos, iterations);
    }

    @Test
    public void benchmarkGetTotalCacheSize() throws Exception {
        int[] itemCounts = {10, 50, 100};

        for (int count : itemCounts) {
            String b = UUID.randomUUID().toString();
            mc.createBucket(b);
            CacheItemRepository repo = new CacheItemRepository(
                    minio.accessKey(), minio.secretKey(),
                    "us-west-1", minio.getExternalAddress(), b);

            for (int i = 0; i < count; i++) {
                byte[] data = generateRandomBytes(1 * KB);
                byte[] md5 = computeMd5(data);
                try (OutputStream out = repo.createObjectOutputStream("size-item-" + i, md5)) {
                    out.write(data);
                }
            }

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

        long nanos = timedRun(() -> repository.updateLastAccess(key));
        reportLatency("updateLastAccess (with copy)", nanos, 1);

        long skipNanos = timedRun(() -> repository.updateLastAccess(key));
        reportLatency("updateLastAccess (skipped/threshold)", skipNanos, 1);
    }

    @Test
    public void benchmarkFindAll() throws Exception {
        for (int i = 0; i < 50; i++) {
            uploadPayload("findall-item-" + i, 1 * KB);
        }

        repository.findAll().count();

        int iterations = 10;
        long nanos = timedRunN(iterations, () -> repository.findAll().count());
        reportLatency("findAll (50 items)", nanos, iterations);
    }

    @Test
    public void benchmarkDelete() throws Exception {
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
        byte[] md5 = computeMd5(data);
        try (OutputStream out = repository.createObjectOutputStream(key, md5)) {
            out.write(data);
        }
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
