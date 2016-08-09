package com.indeed.imhotep.fs;

import com.indeed.util.io.Files;
import org.apache.commons.lang.RandomStringUtils;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;

/**
 * @author kenh
 */

public class LocalFileCacheTest {
    @Rule
    public RemoteCachingFileSystemTestContext testContext = new RemoteCachingFileSystemTestContext();

    private static String generateFileData(final RemoteCachingPath path, int size) {
        return RandomStringUtils.random(size - 1, 0, 0, true, true, null, new Random(path.hashCode()));
    }

    static class DiskUsageCounter extends SimpleFileVisitor<Path> {
        private long totalSize = 0;

        @Override
        public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
            totalSize += java.nio.file.Files.size(file);
            return super.visitFile(file, attrs);
        }

        public long getTotalSize() {
            return totalSize;
        }
    }

    private static long getCacheUsage(final Path cacheDir) throws IOException {
        final DiskUsageCounter diskUsageCounter = new DiskUsageCounter();
        java.nio.file.Files.walkFileTree(cacheDir, diskUsageCounter);
        return diskUsageCounter.getTotalSize();
    }

    private static String readPath(final Path cachePath) {
        return Files.readTextFile(cachePath.toString())[0];
    }

    static class RandomFileLoader implements LocalFileCache.CacheFileLoader {
        private final int fileSize;

        RandomFileLoader(final int fileSize) {
            this.fileSize = fileSize;
        }

        @Override
        public LocalFileCache.FileCacheEntry load(final RemoteCachingPath src, final Path dest) throws IOException {
            final String payload = generateFileData(src, fileSize);
            java.nio.file.Files.createDirectories(dest.getParent());
            Files.writeToTextFileOrDie(new String[]{payload}, dest.toString());
            return new LocalFileCache.FileCacheEntry(dest, (int) java.nio.file.Files.size(dest));
        }
    }

    @Test
    public void testCacheOpenAndEvict() throws IOException, ExecutionException {
        final int fileSize = 128;
        final int maxEntries = 8;
        final int maxCapacity = maxEntries * fileSize;

        final RemoteCachingFileSystem fs = testContext.getFs();
        final RemoteCachingPath rootPath = RemoteCachingPath.getRoot(fs);
        final Path cacheBasePath = testContext.getCacheDir().toPath();

        final LocalFileCache localFileCache = new LocalFileCache(cacheBasePath, maxCapacity, new RandomFileLoader(fileSize));

        localFileCache.initialize(fs);

        Assert.assertEquals(0, getCacheUsage(cacheBasePath));

        // fill up the cache, and have some entries evicted along the way
        for (int i = 1; i <= (maxEntries * 10); i++) {
            final RemoteCachingPath file = rootPath.resolve("cachedOnly").resolve("cacheOnly." + (i % (maxEntries * 2)) + ".file");
            Assert.assertEquals(generateFileData(file, fileSize), readPath(localFileCache.cache(file)));
            Assert.assertTrue(getCacheUsage(cacheBasePath) <= maxCapacity);
        }

        final List<LocalFileCache.ScopedCacheFile> scopedCacheFiles = new ArrayList<>();

        // open some files
        for (int i = 1; i <= (maxEntries * 2); i++) {
            final RemoteCachingPath file = rootPath.resolve("opened").resolve("opened." + i + ".file");
            final LocalFileCache.ScopedCacheFile openedFile = localFileCache.getForOpen(file);
            Assert.assertEquals(generateFileData(file, fileSize), readPath(openedFile.getCachePath()));
            scopedCacheFiles.add(openedFile);
        }

        // ensure all opened files are in the cache
        for (final LocalFileCache.ScopedCacheFile scopedCacheFile : scopedCacheFiles) {
            Assert.assertTrue(java.nio.file.Files.exists(scopedCacheFile.getCachePath()));
        }

        // the total usage is above threshold because we have both cached and opened files
        Assert.assertTrue(getCacheUsage(cacheBasePath) > maxCapacity);

        // close all opened files and ensure that the cache directory space goes back below the threshold
        for (final LocalFileCache.ScopedCacheFile scopedCacheFile : scopedCacheFiles) {
            scopedCacheFile.close();
        }

        // now that all opened files are closed, the cache directory usage should be below the threshold
        Assert.assertTrue(getCacheUsage(cacheBasePath) <= maxCapacity);
    }

    @Test
    public void testCacheRecovery() throws IOException, ExecutionException {
        final int fileSize = 128;
        final int maxEntries = 8;
        final int maxCapacity = maxEntries * fileSize;

        final RemoteCachingFileSystem fs = testContext.getFs();
        final RemoteCachingPath rootPath = RemoteCachingPath.getRoot(fs);
        final Path cacheBasePath = testContext.getCacheDir().toPath();

        {
            final LocalFileCache localFileCache = new LocalFileCache(cacheBasePath, maxCapacity, new RandomFileLoader(fileSize));

            localFileCache.initialize(fs);

            Assert.assertEquals(0, getCacheUsage(cacheBasePath));

            // fill up the cache, and have some entries evicted along the way
            for (int i = 1; i <= (maxEntries * 10); i++) {
                final RemoteCachingPath file = rootPath.resolve("cachedOnly").resolve("cacheOnly." + (i % (maxEntries * 2)) + ".file");
                Assert.assertEquals(generateFileData(file, fileSize), readPath(localFileCache.cache(file)));
                Assert.assertTrue(getCacheUsage(cacheBasePath) <= maxCapacity);
            }

            final List<LocalFileCache.ScopedCacheFile> scopedCacheFiles = new ArrayList<>();

            // open some files
            for (int i = 1; i <= (maxEntries * 2); i++) {
                final RemoteCachingPath file = rootPath.resolve("opened").resolve("opened." + i + ".file");
                final LocalFileCache.ScopedCacheFile openedFile = localFileCache.getForOpen(file);
                Assert.assertEquals(generateFileData(file, fileSize), readPath(openedFile.getCachePath()));
                scopedCacheFiles.add(openedFile);
            }

            // ensure all opened files are in the cache
            for (final LocalFileCache.ScopedCacheFile scopedCacheFile : scopedCacheFiles) {
                Assert.assertTrue(java.nio.file.Files.exists(scopedCacheFile.getCachePath()));
            }
            // do not close it
        }

        {
            // reinitialize the cache
            final LocalFileCache localFileCache = new LocalFileCache(cacheBasePath, maxCapacity, new RandomFileLoader(fileSize));

            localFileCache.initialize(fs);

            // all files from previous opened/closed files should be treated as closed
            // so the cache usage should be below the threshold
            Assert.assertTrue(getCacheUsage(cacheBasePath) <= maxCapacity);

            final List<LocalFileCache.ScopedCacheFile> scopedCacheFiles = new ArrayList<>();

            // open some files
            for (int i = 1; i <= (maxEntries * 2); i++) {
                final RemoteCachingPath file = rootPath.resolve("opened").resolve("opened." + i + ".file");
                final LocalFileCache.ScopedCacheFile openedFile = localFileCache.getForOpen(file);
                Assert.assertEquals(generateFileData(file, fileSize), readPath(openedFile.getCachePath()));
                scopedCacheFiles.add(openedFile);
            }

            // fill up the cache, and have some entries evicted along the way
            // we want to test if opened files are evicted
            for (int i = 1; i <= (maxEntries * 10); i++) {
                final RemoteCachingPath file = rootPath.resolve("cachedOnly").resolve("cacheOnly." + (i % (maxEntries * 2)) + ".file");
                Assert.assertEquals(generateFileData(file, fileSize), readPath(localFileCache.cache(file)));
            }

            // ensure all opened files are in the cache
            for (final LocalFileCache.ScopedCacheFile scopedCacheFile : scopedCacheFiles) {
                Assert.assertTrue(java.nio.file.Files.exists(scopedCacheFile.getCachePath()));
            }

            // close all opened files and ensure that the cache directory space goes back below the threshold
            for (final LocalFileCache.ScopedCacheFile scopedCacheFile : scopedCacheFiles) {
                scopedCacheFile.close();
            }

            // now that all opened files are closed, the cache directory usage should be below the threshold
            Assert.assertTrue(getCacheUsage(cacheBasePath) <= maxCapacity);
        }
    }
}