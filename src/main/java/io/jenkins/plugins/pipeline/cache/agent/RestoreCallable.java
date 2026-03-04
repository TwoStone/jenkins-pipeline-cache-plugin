package io.jenkins.plugins.pipeline.cache.agent;

import hudson.FilePath;
import hudson.remoting.VirtualChannel;
import io.jenkins.plugins.pipeline.cache.CacheConfiguration;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.File;
import java.io.IOException;

import static java.lang.String.format;

/**
 * Extracts an existing tar archive from S3 to a given {@link FilePath}.
 */
public class RestoreCallable extends AbstractMasterToAgentS3Callable {
    private final String key;
    private final String[] restoreKeys;

    public RestoreCallable(CacheConfiguration config, String key, String... restoreKeys) {
        super(config);
        this.key = key;
        this.restoreKeys = restoreKeys;
    }

    @Override
    public Result invoke(File path, VirtualChannel channel) throws IOException, InterruptedException {
        // make sure that the restore path not exists yet or is a directory
        if (path.exists() && !path.isDirectory()) {
            return new ResultBuilder()
                    .withInfo("Cache not restored (path is not a directory)")
                    .build();
        }

        String key = cacheItemRepository().findRestoreKey(this.key, restoreKeys);

        // make sure that the cache exists
        if (key == null) {
            return new ResultBuilder()
                    .withInfo("Cache not restored (no such key found)")
                    .build();
        }

        // do restore
        long startNanoTime = System.nanoTime();

        try (ResponseInputStream<GetObjectResponse> s3Object = cacheItemRepository().getS3Object(key)) {
            new FilePath(path).untarFrom(s3Object, FilePath.TarCompression.NONE);
        }

        // update last access timestamp
        cacheItemRepository().updateLastAccess(key);

        return new ResultBuilder()
                .withInfo(format("Cache restored successfully (%s)", key))
                .withInfo(performanceString(key, startNanoTime))
                .build();
    }


}
