package io.jenkins.plugins.pipeline.cache;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.jenkinsci.Symbol;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import io.jenkins.plugins.pipeline.cache.s3.CacheItem;
import io.jenkins.plugins.pipeline.cache.s3.CacheItemRepository;

/**
 * Removes periodically last recently used items from the cache.
 */
@Extension
@Restricted(NoExternalUse.class)
@Symbol("cacheCleanupLRUJob")
public class CacheCleanupTask extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(CacheCleanupTask.class.getName());

    public CacheCleanupTask() {
        super("Cleanup cache items");
    }

    @Override
    protected void execute(TaskListener listener) {
        CacheConfiguration config = CacheConfiguration.get();

        // make sure threshold is active
        if (config.getThreshold() <= 0) {
            return;
        }

        // setup
        try (CacheItemRepository repo = new CacheItemRepository(
                config.getUsername(),
                config.getPassword().getPlainText(),
                config.getRegion(),
                config.getEndpoint(),
                config.getBucket()
        )) {
            long thresholdSize = config.getThreshold() * 1024 * 1024;

            // Single listing pass: collect all items and compute total size
            List<CacheItem> allItems = repo.findAll().toList();
            long totalSize = allItems.stream().mapToLong(CacheItem::getContentLength).sum();

            // make sure threshold is exceeded
            if (thresholdSize < totalSize) {
                // calculate how much data must be removed so that the threshold is not exceeded anymore
                AtomicLong bytesToRemove = new AtomicLong(totalSize - thresholdSize);

                // collect last recently used items until threshold is not exceeded anymore
                Stream<String> keysToDelete = allItems.stream()
                        .sorted(Comparator.comparing(CacheItem::getLastAccess))
                        .filter(item -> {
                            if (bytesToRemove.get() <= 0) {
                                return false;
                            }
                            bytesToRemove.addAndGet(-item.getContentLength());
                            return true;
                        })
                        .map(CacheItem::getKey);

                // remove them
                int count = repo.delete(keysToDelete);

                LOGGER.info(String.format("removed %s item(s)", count));
            }
        }
    }

    @Override
    public long getRecurrencePeriod() {
        return HOUR;
    }

    @Override
    public long getInitialDelay() {
        return HOUR;
    }
}
