package io.jenkins.plugins.pipeline.cache.s3;

import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.BlockingOutputStreamAsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static io.jenkins.plugins.pipeline.cache.s3.CacheItemRepository.CREATION;
import static io.jenkins.plugins.pipeline.cache.s3.CacheItemRepository.LAST_ACCESS;

/**
 * {@link OutputStream} that streams data directly to an S3 object using the AWS SDK's
 * multipart-enabled async client. The SDK handles buffering, part splitting, and
 * parallel part uploads automatically.
 *
 * <p>Data written to this stream is uploaded concurrently as multipart parts by the
 * underlying {@link S3AsyncClient} (which must have multipart enabled). Small objects
 * (below the client's multipart threshold) are uploaded as a single PUT request.</p>
 */
public class S3OutputStream extends OutputStream {

    /**
     * Default multipart part size (10 MB). This is configured on the {@link S3AsyncClient}
     * via {@code MultipartConfiguration.minimumPartSizeInBytes}.
     */
    public static final long DEFAULT_PART_SIZE = 1024L * 1024 * 10;

    private final CompletableFuture<PutObjectResponse> uploadFuture;
    private final OutputStream delegate;
    private boolean open = true;

    /**
     * Creates a new output stream that uploads data to S3.
     *
     * @param s3Async a multipart-enabled {@link S3AsyncClient}
     * @param bucket  name of the bucket
     * @param key     key of the object within the bucket
     */
    public S3OutputStream(S3AsyncClient s3Async, String bucket, String key) {
        BlockingOutputStreamAsyncRequestBody body = AsyncRequestBody.forBlockingOutputStream(null);

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .metadata(createMetadata())
                .build();

        this.uploadFuture = s3Async.putObject(request, body);
        this.delegate = body.outputStream();
    }

    @Override
    public void write(int b) throws IOException {
        delegate.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        delegate.write(b, off, len);
    }

    @Override
    public synchronized void close() throws IOException {
        if (!open) {
            return;
        }
        open = false;
        delegate.close();
        try {
            uploadFuture.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException("Upload to S3 failed", cause);
        }
    }

    private static Map<String, String> createMetadata() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put(CREATION, Long.toString(System.currentTimeMillis()));
        metadata.put(LAST_ACCESS, Long.toString(System.currentTimeMillis()));
        return metadata;
    }

}
