package uk.gov.hmcts.reform.deedpoll.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentStorePropertiesTest {

    @Test
    @DisplayName("the filesystem store is the default")
    void filesystemIsTheDefault() {
        DocumentStoreProperties properties = new DocumentStoreProperties(null, null);

        assertThat(properties.store()).isEqualTo(DocumentStoreProperties.FILESYSTEM);
        assertThat(properties.isCdam()).isFalse();
        assertThat(properties.documentUrlBase()).isEqualTo("http://dm-store:8080");
    }

    @Test
    @DisplayName("cdam selects the CDAM store")
    void cdamSelectsCdam() {
        assertThat(new DocumentStoreProperties("cdam", "http://dm-store:8080").isCdam()).isTrue();
        assertThat(new DocumentStoreProperties("filesystem", "http://dm-store:8080").isCdam()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CDAM", "Filesystem", "s3", "", " cdam"})
    @DisplayName("anything but the two exact names is refused at startup, including a case the condition would match")
    void anythingElseIsRefused(String store) {
        assertThatThrownBy(() -> new DocumentStoreProperties(store, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(DocumentStoreProperties.STORE);
    }

    @Test
    @DisplayName("the document URL base loses a trailing slash, so a path can be appended")
    void trailingSlashIsStripped() {
        assertThat(new DocumentStoreProperties("cdam", "http://dm-store:8080/").documentUrlBase())
            .isEqualTo("http://dm-store:8080");
    }

    @Test
    @DisplayName("a record with no CCD case is not ready for a CDAM upload, in the published words")
    void caseNotReady() {
        assertThatThrownBy(() -> DocumentStorageRule.requireCaseReady(Optional.empty()))
            .isInstanceOf(ConflictError.class)
            .hasMessage(DocumentStorageRule.CASE_NOT_READY);
        assertThatThrownBy(() -> DocumentStorageRule.requireCaseReady(Optional.of(" ")))
            .isInstanceOf(ConflictError.class);
        assertThat(DocumentStorageRule.requireCaseReady(Optional.of("1234567890123456")))
            .isEqualTo("1234567890123456");
    }
}
