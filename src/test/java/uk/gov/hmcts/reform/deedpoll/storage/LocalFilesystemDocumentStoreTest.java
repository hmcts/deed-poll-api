package uk.gov.hmcts.reform.deedpoll.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentOwner;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentStore;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentToStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers {@code LocalFilesystemDocumentStore}, the one {@link DocumentStore} implementation in this plan.
 *
 * <p><strong>Not a port.</strong> {@code reference/ts/documents/file-storage.ts} is deliberately not ported --
 * real storage is CDAM, in plan 03 -- and it has no tests of its own, so these are written rather than
 * translated. They pin the three properties a store has to have for the service above it to be correct:
 *
 * <ol>
 *   <li>Whatever went in comes back out, byte for byte. A store that truncated a PDF would pass every test in
 *       {@code DocumentServiceTest} that only checks the metadata row.</li>
 *   <li>An unknown key is a 404, not an exception a route layer would answer 500 for.</li>
 *   <li>A key that resolves outside the storage root is refused. Keys are generated here, so one should be
 *       impossible -- but a key lives in a database column, and columns get seeded and migrated.</li>
 * </ol>
 *
 * <p>No Spring context and no database: the store takes its root as a constructor argument precisely so it can
 * be built directly.
 */
class LocalFilesystemDocumentStoreTest {

    private static final byte[] CONTENT = "a scanned certificate".getBytes(StandardCharsets.UTF_8);

    private static final DocumentOwner OWNER =
        new DocumentOwner("6f1c5f2e-0000-4000-8000-00000000000a", Optional.empty());

    @TempDir
    private Path root;

    private DocumentStore store;

    @BeforeEach
    void createStore() {
        store = new LocalFilesystemDocumentStore(root.resolve("documents").toString());
    }

    @Test
    @DisplayName("returns the bytes it was given, unchanged")
    void returnsTheBytesItWasGiven() throws IOException {
        String key = save(CONTENT);

        try (InputStream stream = store.open(key)) {
            assertThat(stream.readAllBytes()).isEqualTo(CONTENT);
        }
    }

    @Test
    @DisplayName("creates its root directory rather than requiring one")
    void createsItsRootDirectory() {
        // The default root is a relative path under the working directory, so a fresh checkout has none. A store
        // that required the directory to exist would fail on a developer's first upload.
        assertThat(root.resolve("documents")).doesNotExist();

        save(CONTENT);

        assertThat(root.resolve("documents")).isDirectory();
    }

    @Test
    @DisplayName("gives each file its own key, so two uploads of one filename do not collide")
    void givesEachFileItsOwnKey() {
        assertThat(save(CONTENT)).isNotEqualTo(save(CONTENT));
    }

    @Test
    @DisplayName("404s for a key it does not hold")
    void refusesAKeyItDoesNotHold() {
        assertThatThrownBy(() -> store.open("6f1c5f2e-0000-4000-8000-000000000000"))
            .isInstanceOf(NotFoundError.class);
    }

    @Test
    @DisplayName("deletes a stored blob")
    void deletesAStoredBlob() {
        String key = save(CONTENT);

        store.delete(key);

        assertThatThrownBy(() -> store.open(key)).isInstanceOf(NotFoundError.class);
    }

    @Test
    @DisplayName("delete is idempotent, and a no-op for a null or blank key")
    void deleteIsIdempotent() {
        // DocumentService.removeDocument calls delete unconditionally, including for a metadata-only row whose
        // storageKey is null. If that threw, deleting such a row would log a cleanup failure that had not
        // happened.
        String key = save(CONTENT);
        store.delete(key);

        assertThatCode(() -> store.delete(key)).doesNotThrowAnyException();
        assertThatCode(() -> store.delete(null)).doesNotThrowAnyException();
        assertThatCode(() -> store.delete("  ")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses a key that resolves outside the storage root")
    void refusesAKeyOutsideTheStorageRoot() throws IOException {
        Path outside = root.resolve("secret.txt");
        Files.write(outside, CONTENT);

        assertThatThrownBy(() -> store.open("../secret.txt")).isInstanceOf(NotFoundError.class);
        assertThatThrownBy(() -> store.delete("../secret.txt")).isInstanceOf(NotFoundError.class);
        // Refused, not read, and refusing did not delete it either.
        assertThat(outside).exists();
    }

    @Test
    @DisplayName("refuses a key naming the storage root itself")
    void refusesAKeyNamingTheRootItself() {
        // An empty key resolves to the root, which is a directory. Without the guard, open would report "not
        // found" for the right reason by accident and delete would attempt to remove the whole store.
        assertThatThrownBy(() -> store.open("")).isInstanceOf(NotFoundError.class);
    }

    @Test
    @DisplayName("keeps blobs separate from each other")
    void keepsBlobsSeparate() throws IOException {
        byte[] other = "a different document".getBytes(StandardCharsets.UTF_8);
        String first = save(CONTENT);
        String second = save(other);

        try (InputStream firstStream = store.open(first); InputStream secondStream = store.open(second)) {
            assertThat(firstStream.readAllBytes()).isEqualTo(CONTENT);
            assertThat(secondStream.readAllBytes()).isEqualTo(other);
        }
    }

    @Test
    @DisplayName("ignores the filename, type and owner it is told")
    void ignoresTheContextItIsTold() throws IOException {
        DocumentToStore bare = new DocumentToStore(CONTENT, null, null, new DocumentOwner("a-case", Optional.empty()));

        String key = store.save(bare).storageKey();

        assertThat(key).doesNotContain("a-case");
        try (InputStream stream = store.open(key)) {
            assertThat(stream.readAllBytes()).isEqualTo(CONTENT);
        }
    }

    /** Saves bytes for an owner this store has no use for, and answers the key. */
    private String save(byte[] content) {
        return store.save(new DocumentToStore(content, "certificate.pdf", "application/pdf", OWNER)).storageKey();
    }
}
