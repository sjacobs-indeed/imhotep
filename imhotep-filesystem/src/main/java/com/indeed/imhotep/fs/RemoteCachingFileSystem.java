package com.indeed.imhotep.fs;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

/**
 * @author kenh
 */

class RemoteCachingFileSystem extends FileSystem {
    private final RemoteCachingFileSystemProvider provider;
    private final SqarRemoteFileStore fileStore;
    private final LocalFileCache fileCache;

    RemoteCachingFileSystem(final RemoteCachingFileSystemProvider provider, final Map<String, ?> configuration) throws IOException {
        this.provider = provider;
        final RemoteFileStore backingFileStore = RemoteFileStoreType.fromName((String) configuration.get("imhotep.fs.store.type"))
                .getBuilder().build(configuration);
        try {
            fileStore = new SqarRemoteFileStore(backingFileStore, configuration);
        } catch (final SQLException | ClassNotFoundException e) {
            throw new IllegalStateException("Failed to initialize SqarRemoteFileStore", e);
        }

        try {
            fileCache = new LocalFileCache(
                    Paths.get(new URI((String) configuration.get("imhotep.fs.cache.root-uri"))),
                    Long.parseLong((String) configuration.get("imhotep.fs.cache.size.megabytes")) * 1024 * 1024,
                    new LocalFileCache.CacheFileLoader() {
                        @Override
                        public LocalFileCache.FileCacheEntry load(final RemoteCachingPath src, final Path dest) throws IOException {
                            fileStore.downloadFile(src, dest);
                            return new LocalFileCache.FileCacheEntry(
                                    dest,
                                    (int) Files.size(dest)
                            );
                        }
                    }
            );
        } catch (final URISyntaxException e) {
            throw new IllegalArgumentException("Could not parse cache root URI", e);
        }
        fileCache.initialize(this);
    }

    Path getCachePath(final RemoteCachingPath path) throws ExecutionException {
        return fileCache.cache(path);
    }

    @Override
    public FileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isOpen() {
        return true;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getSeparator() {
        return RemoteCachingPath.PATH_SEPARATOR_STR;
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        return Collections.singletonList((Path) new RemoteCachingPath(this, RemoteCachingPath.PATH_SEPARATOR_STR));
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return Collections.<FileStore>singletonList(fileStore.getBackingFileStore());
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return ImmutableSet.of("imhotep");
    }

    @Nullable
    ImhotepFileAttributes getFileAttributes(final RemoteCachingPath path) throws IOException {
        final RemoteFileStore.RemoteFileAttributes remoteAttributes = fileStore.getRemoteAttributes(path);
        if (remoteAttributes == null) {
            return null;
        }

        return new ImhotepFileAttributes(remoteAttributes.getSize(), remoteAttributes.isDirectory());
    }

    @Override
    public Path getPath(final String first, final String... more) {
        return new RemoteCachingPath(this, Joiner.on(RemoteCachingPath.PATH_SEPARATOR_STR).join(Lists.asList(first, more)));
    }

    Iterable<RemoteCachingPath> listDir(final RemoteCachingPath path) throws IOException {
        return FluentIterable.from(fileStore.listDir(path))
                .transform(new Function<RemoteFileStore.RemoteFileAttributes, RemoteCachingPath>() {
                    @Override
                    public RemoteCachingPath apply(final RemoteFileStore.RemoteFileAttributes remoteFileAttributes) {
                        return remoteFileAttributes.getPath();
                    }
                });
    }

    SeekableByteChannel newByteChannel(final RemoteCachingPath path) throws IOException {
        final LocalFileCache.ScopedCacheFile scopedCacheFile;
        try {
            scopedCacheFile = fileCache.getForOpen(path);
        } catch (final ExecutionException e) {
            throw new IOException("Failed to access cache file for " + path, e);
        }
        return new CloseHookedSeekableByteChannel(
                Files.newByteChannel(scopedCacheFile.getCachePath()),
                scopedCacheFile);
    }

    private class CloseHookedSeekableByteChannel implements SeekableByteChannel {
        private final SeekableByteChannel wrapped;
        private final LocalFileCache.ScopedCacheFile scopedCacheFile;

        CloseHookedSeekableByteChannel(final SeekableByteChannel wrapped, final LocalFileCache.ScopedCacheFile scopedCacheFile) {
            this.wrapped = wrapped;
            this.scopedCacheFile = scopedCacheFile;
        }

        @Override
        public int read(final ByteBuffer dst) throws IOException {
            return wrapped.read(dst);
        }

        @Override
        public int write(final ByteBuffer src) throws IOException {
            return wrapped.write(src);
        }

        @Override
        public long position() throws IOException {
            return wrapped.position();
        }

        @Override
        public SeekableByteChannel position(final long newPosition) throws IOException {
            return wrapped.position(newPosition);
        }

        @Override
        public long size() throws IOException {
            return wrapped.size();
        }

        @Override
        public SeekableByteChannel truncate(final long size) throws IOException {
            return wrapped.truncate(size);
        }

        @Override
        public boolean isOpen() {
            return wrapped.isOpen();
        }

        @Override
        public void close() throws IOException {
            scopedCacheFile.close();
            wrapped.close();
        }
    }

    @Override
    public PathMatcher getPathMatcher(final String syntaxAndPattern) {
        final int idx = syntaxAndPattern.indexOf(':');
        if ((idx == -1) && (idx >= (syntaxAndPattern.length() - 1))) {
            throw new IllegalArgumentException("Unexpected syntax and pattern format: " + syntaxAndPattern);
        }
        final String syntax = syntaxAndPattern.substring(0, idx);
        if ("regex".equals(syntax)) {
            final String pattern = syntaxAndPattern.substring(idx + 1);
            final Pattern compiledPattern = Pattern.compile(pattern);
            return new PathMatcher() {
                @Override
                public boolean matches(final Path path) {
                    return compiledPattern.matcher(path.toString()).matches();
                }
            };
        } else {
            throw new UnsupportedOperationException("Unsupported syntax " + syntax);
        }
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException();
    }

    @Override
    public WatchService newWatchService() throws IOException {
        throw new UnsupportedOperationException();
    }
}
