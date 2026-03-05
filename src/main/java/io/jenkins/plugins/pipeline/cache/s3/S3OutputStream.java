package io.jenkins.plugins.pipeline.cache.s3;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.jenkins.plugins.pipeline.cache.s3.CacheItemRepository.CREATION;
import static io.jenkins.plugins.pipeline.cache.s3.CacheItemRepository.LAST_ACCESS;

/**
 * {@link OutputStream} which allows writing an object to S3 directly. If the content size is not greater than 10 MB then it is uploaded at
 * once, otherwise in chunks (default: 10 MB).
 */
public class S3OutputStream extends OutputStream {

    /**
     * Buffer size (default: 10 MB, minimum: 5 MB).
     */
    public static final int BUFFER_SIZE = 1024 * 1024 * 10;

    /**
     * S3 client which is used to upload the content.
     */
    private final S3Client s3;

    /**
     * Name of the bucket where the object is stored.
     */
    private final String bucket;

    /**
     * Key which will be assigned to the object.
     */
    private final String key;

    /**
     * The internal buffer where data is stored.
     */
    protected byte[] buf;

    /**
     * The number of valid bytes in the buffer. This value is always
     * in the range {@code 0} through {@code buf.length}; elements
     * {@code buf[0]} through {@code buf[count-1]} contain valid
     * byte data.
     */
    protected int count;

    /**
     * Holds the part IDs in case of a multipart upload.
     */
    protected List<CompletedPart> completedParts = new ArrayList<>();

    /**
     * true indicates that the stream is still open, otherwise false.
     */
    protected boolean open = true;

    /**
     * Holds the result of the initial upload in case of a multipart upload.
     */
    private CreateMultipartUploadResponse multipartUpload;

    /**
     * Creates a new buffered output stream to write data to S3.
     *
     * @param s3     the S3 client
     * @param bucket name of the bucket
     * @param key    key of the object within the bucket
     */
    public S3OutputStream(S3Client s3, String bucket, String key) {
        this(s3, bucket, key, BUFFER_SIZE);
    }

    /**
     * Creates a new buffered output stream to write data to S3.
     *
     * @param s3     the S3 client
     * @param bucket name of the bucket
     * @param key    key of the object within the bucket
     * @param size   size of the buffer
     */
    public S3OutputStream(S3Client s3, String bucket, String key, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size <= 0");
        }
        this.s3 = s3;
        this.bucket = bucket;
        this.key = key;
        this.buf = new byte[size];
    }

    /**
     * Writes the specified byte to this buffered output stream.
     *
     * @param b the byte to be written.
     */
    public synchronized void write(int b) {
        if (count >= buf.length) {
            flushAndReset();
        }
        buf[count++] = (byte) b;
    }

    /**
     * Writes <code>len</code> bytes from the specified byte array
     * starting at offset <code>off</code> to this buffered output stream.
     *
     * @param b   the data.
     * @param off the start offset in the data.
     * @param len the number of bytes to write.
     */
    public synchronized void write(byte[] b, int off, int len) {
        while (count + len > buf.length) {
            int size = buf.length - count;
            System.arraycopy(b, off, buf, count, size);
            off += size;
            len -= size;
            count += size;
            flushAndReset();
        }
        System.arraycopy(b, off, buf, count, len);
        count += len;
    }

    private void flushAndReset() {
        if (count <= 0) {
            return;
        }

        if (multipartUpload == null) {
            // initialize partial upload
            multipartUpload = s3.createMultipartUpload(builder ->
                    builder.bucket(bucket)
                            .key(key)
                            .metadata(createMetadata())
            );
        }

        // upload part
        int partNumber = completedParts.size() + 1;
        int currentCount = count;
        UploadPartResponse uploadPartResponse = s3.uploadPart(builder -> {
            builder.bucket(bucket)
                    .key(key)
                    .uploadId(multipartUpload.uploadId())
                    .partNumber(partNumber)
                    .contentLength((long) currentCount);
        }, RequestBody.fromInputStream(new ByteArrayInputStream(buf, 0, currentCount), currentCount));

        // store part ID (required for the final step)
        completedParts.add(CompletedPart.builder()
                .partNumber(partNumber)
                .eTag(uploadPartResponse.eTag())
                .build());

        // reset count
        count = 0;
    }

    @Override
    public synchronized void close() {
        if (!open) {
            return;
        }
        open = false;

        // complete partial upload
        if (multipartUpload != null) {
            flushAndReset();
            s3.completeMultipartUpload(builder ->
                    builder.bucket(bucket)
                            .key(key)
                            .uploadId(multipartUpload.uploadId())
                            .multipartUpload(mb -> mb.parts(completedParts).build()));
        }

        // or upload content at once (content <= buffer size)
        else {
            s3.putObject(builder ->
                    builder.bucket(bucket)
                            .key(key)
                            .metadata(createMetadata()),
                    RequestBody.fromInputStream(new ByteArrayInputStream(buf, 0, count), count));
        }
    }

    private Map<String, String> createMetadata() {
        Map<String, String> metadata = new HashMap<>();

        metadata.put(CREATION, Long.toString(System.currentTimeMillis()));
        metadata.put(LAST_ACCESS, Long.toString(System.currentTimeMillis()));

        return metadata;
    }

}
