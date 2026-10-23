package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadPolicy;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The preview's limits are its own: they change nothing an upload, a download or staging decides.
 */
class PreviewBoundaryTest {

    private static final String PREVIEW = "uk.gov.hmcts.reform.deedpoll.domain.documents.preview..";

    @Test
    @DisplayName("upload acceptance, original downloads, stores and staging do not read the preview's policy")
    void nothingUpstreamDependsOnThePreview() {
        // Imported narrowly and locally: a whole-codebase graph exhausts the test JVM's heap.
        JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("uk.gov.hmcts.reform.deedpoll.domain.documents",
                            "uk.gov.hmcts.reform.deedpoll.domain.staging",
                            "uk.gov.hmcts.reform.deedpoll.domain.deeds",
                            "uk.gov.hmcts.reform.deedpoll.storage");

        noClasses().that().resideOutsideOfPackage(PREVIEW)
            .should().dependOnClassesThat().resideInAPackage(PREVIEW)
            .because("a preview limit must never become an upload, download or staging limit")
            .check(classes);
    }

    @Test
    @DisplayName("the upload allowlist and ceiling are as they were before previews")
    void uploadPolicyIsUnchanged() {
        assertThat(UploadPolicy.MAX_UPLOAD_BYTES).isEqualTo(25L * 1024 * 1024);
        assertThat(UploadPolicy.ALLOWED_UPLOAD_MIME_TYPES).isEqualTo(Set.of(
            "application/pdf", "image/jpeg", "image/png", "image/tiff",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
    }
}
