package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.RandomAccessStreamCache;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One preview's private scratch directory: the spooled source, the rendered PDF and PDFBox's own scratch files.
 *
 * <p>Created owner-only where the file system has POSIX permissions, named by nothing from the document, and
 * deleted whole by {@link #close} -- after a success once the response is written, and at once after a failure or a
 * cancellation. Every file PDFBox spills to lives here too, so nothing a preview wrote outlives it, and a preview
 * leaves no derivative behind for anybody else to find.
 */
final class PreviewWorkspace implements AutoCloseable {

    private static final int CHUNK = 64 * 1024;
    private static final FileAttribute<?>[] PRIVATE = privateDirectory();

    private final Path directory;
    private final AtomicInteger names = new AtomicInteger();

    private PreviewWorkspace(Path directory) {
        this.directory = directory;
    }

    /**
     * A fresh private directory under {@code root}.
     *
     * @throws PreviewError {@link PreviewFailure#UNAVAILABLE} if it cannot be created
     */
    static PreviewWorkspace create(Path root) {
        try {
            return new PreviewWorkspace(Files.createTempDirectory(root, "preview-", PRIVATE));
        } catch (IOException noScratch) {
            throw new PreviewError(PreviewFailure.UNAVAILABLE);
        }
    }

    /** A new file name in this directory; the file itself is not created. */
    Path newFile(String suffix) {
        return directory.resolve(names.incrementAndGet() + suffix);
    }

    /**
     * Copies {@code source} into a new file, stopping as soon as it passes {@code limit} bytes or the budget ends.
     *
     * @throws PreviewError {@link PreviewFailure#LIMIT_EXCEEDED} over the limit or the budget,
     *     {@link PreviewFailure#UNAVAILABLE} if the source could not be read to its end
     * @throws PreviewCancelledException if the caller went away
     */
    Path spool(InputStream source, long limit, PreviewBudget budget) {
        Path file = newFile(".source");
        byte[] chunk = new byte[CHUNK];
        long total = 0;
        try (OutputStream out = Files.newOutputStream(file)) {
            while (true) {
                budget.checkpoint();
                int read;
                try {
                    read = source.read(chunk);
                } catch (IOException unreadable) {
                    // A read failing half way is the store's, not the document's; its message may carry a path.
                    throw new PreviewError(PreviewFailure.UNAVAILABLE);
                }
                if (read < 0) {
                    break;
                }
                total += read;
                if (total > limit) {
                    throw new PreviewError(PreviewFailure.LIMIT_EXCEEDED);
                }
                out.write(chunk, 0, read);
            }
        } catch (IOException scratch) {
            throw new PreviewError(PreviewFailure.UNAVAILABLE);
        }
        budget.checkpoint();
        return file;
    }

    /**
     * A new file that refuses to grow past {@code limit} bytes, rather than writing a PDF that will be refused.
     */
    OutputStream boundedOutput(Path file, long limit) throws IOException {
        return new FilterOutputStream(Files.newOutputStream(file)) {
            private long written;

            @Override
            public void write(int value) throws IOException {
                grow(1);
                out.write(value);
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                grow(length);
                out.write(bytes, offset, length);
            }

            private void grow(int length) {
                written += length;
                if (written > limit) {
                    throw new PreviewError(PreviewFailure.LIMIT_EXCEEDED);
                }
            }
        };
    }

    /**
     * PDFBox's scratch, on disk in this directory and bounded, so a decompression-heavy document fills a file that
     * is refused rather than the heap.
     */
    RandomAccessStreamCache.StreamCacheCreateFunction streamCache(long maxBytes) {
        return MemoryUsageSetting.setupTempFileOnly(maxBytes).setTempDir(directory.toFile()).streamCache;
    }

    /** Deletes the directory and everything in it; safe to call more than once. */
    @Override
    public void close() {
        if (!Files.exists(directory)) {
            return;
        }
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException undeleted) {
            throw new UncheckedIOException("a preview's scratch directory could not be deleted", undeleted);
        }
    }

    private static FileAttribute<?>[] privateDirectory() {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------"))};
        }
        return new FileAttribute<?>[0];
    }
}
