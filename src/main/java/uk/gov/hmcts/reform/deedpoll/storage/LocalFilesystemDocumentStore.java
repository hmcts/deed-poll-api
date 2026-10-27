package uk.gov.hmcts.reform.deedpoll.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentStore;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentToStore;
import uk.gov.hmcts.reform.deedpoll.domain.documents.StorageReceipt;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * The one implementation of {@link DocumentStore} in this plan: blobs on local disk, for development.
 *
 * <p><strong>For a developer machine, and nothing else.</strong> Local disk does not survive AKS -- pods are
 * ephemeral and replicas do not share a volume -- so a deployed environment running this would lose every
 * uploaded file on the next restart and serve 404s from whichever replica did not receive the upload. Real
 * storage is CDAM, in plan 03, which adds an implementation beside this one.
 *
 * <p>The root path mirrors the reference's convention: a directory named by configuration, gitignored,
 * defaulting to {@code data/documents} under the working directory so that a checkout runs without being
 * configured first.
 *
 * <p>The default store: chosen when {@code deedpoll.documents.store} is {@code filesystem} or unset, and absent when
 * it is {@code cdam}. {@link DocumentStoreProperties} refuses any other value at startup.
 */
@Component
@ConditionalOnProperty(name = DocumentStoreProperties.STORE_PROPERTY, havingValue = DocumentStoreProperties.FILESYSTEM,
    matchIfMissing = true)
public class LocalFilesystemDocumentStore implements DocumentStore {

    private final Path root;

    public LocalFilesystemDocumentStore(@Value("${deedpoll.documents.local-store-root:data/documents}") String root) {
        this.root = Paths.get(root).toAbsolutePath().normalize();
    }

    /**
     * Writes the bytes under a fresh random key.
     *
     * <p>The key is a UUID rather than the file's own name: two applicants uploading {@code birth.pdf} must
     * not collide, and a caller-supplied name in a path is how a traversal gets in. The original name is
     * kept in the metadata row instead, which is what the download's {@code Content-Disposition} uses. For the
     * same reason the filename, type and owner the seam carries are ignored: a directory has no use for them.
     */
    @Override
    public StorageReceipt save(DocumentToStore document) {
        String key = UUID.randomUUID().toString();
        try {
            Files.createDirectories(root);
            Files.write(pathForKey(key), document.content());
        } catch (IOException failed) {
            // Unchecked, because a caller cannot do anything useful about a full disk and the interface
            // should not make every consumer of a future CDAM store declare an IOException it cannot throw.
            throw new UncheckedIOException("Could not store document " + key, failed);
        }
        return new StorageReceipt(key);
    }

    @Override
    public InputStream open(String storageKey) {
        Path path = pathForKey(storageKey);
        if (!Files.isRegularFile(path)) {
            throw new NotFoundError("Stored file not found");
        }
        try {
            return Files.newInputStream(path);
        } catch (IOException failed) {
            throw new UncheckedIOException("Could not read document " + storageKey, failed);
        }
    }

    @Override
    public void delete(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(pathForKey(storageKey));
        } catch (IOException failed) {
            throw new UncheckedIOException("Could not delete document " + storageKey, failed);
        }
    }

    /**
     * Resolves a key to an absolute path, refusing anything that escapes the storage root.
     *
     * <p>Keys are generated here, so a traversing one should be impossible -- but a key is stored in a
     * database column, and a column is exactly the sort of thing that gets seeded, migrated or edited. The
     * guard costs one comparison and makes {@code ../../etc/passwd} a 404 rather than a read.
     */
    private Path pathForKey(String storageKey) {
        Path path = root.resolve(storageKey).normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new NotFoundError("Invalid storage key");
        }
        return path;
    }
}
