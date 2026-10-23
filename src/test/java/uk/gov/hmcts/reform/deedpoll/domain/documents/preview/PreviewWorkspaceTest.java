package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PreviewWorkspaceTest {

    @TempDir
    Path root;

    private final ConversionCapacity capacity = new ConversionCapacity(1, 1, Duration.ofSeconds(5));

    @Test
    @DisplayName("closing deletes the directory and gives the slot back, once however often it is closed")
    void closeDeletesThenReleases() throws IOException {
        PreviewWorkspace workspace = PreviewWorkspace.create(root, capacity.acquireWorkspace());
        Files.write(workspace.newFile(".source"), new byte[] {1});
        assertThat(capacity.availableWorkspaces()).isZero();

        workspace.close();
        workspace.close();

        assertThat(listed(root)).isZero();
        assertThat(capacity.availableWorkspaces()).isEqualTo(1);
    }

    @Test
    @DisplayName("a directory that could not be deleted keeps its slot, and a later close that deletes it releases it")
    void undeletedKeepsItsSlot() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        PreviewWorkspace workspace = PreviewWorkspace.create(root, capacity.acquireWorkspace());
        Path file = workspace.newFile(".source");
        Files.write(file, new byte[] {1});
        Path directory = file.getParent();
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"));
        assumeTrue(!Files.isWritable(directory), "running with privileges that ignore permissions");
        try {
            workspace.close();
            assertThat(Files.exists(directory)).isTrue();
            assertThat(capacity.availableWorkspaces()).isZero();
        } finally {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }

        workspace.close();
        assertThat(listed(root)).isZero();
        assertThat(capacity.availableWorkspaces()).isEqualTo(1);
    }

    private static long listed(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.count();
        }
    }
}
