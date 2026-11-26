package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The admitted body as one comparable value: each field changes it, and nothing runs into the next field. */
class DocumentDeletionFingerprintTest {

    private static final DocumentDeletionReason REASON = DocumentDeletionReason.parse("Uploaded in error");

    @Test
    @DisplayName("the same body gives the same lower-case SHA-256, and each field changes it")
    void eachFieldCounts() {
        String fingerprint = DocumentDeletionFingerprint.of("doc-1", 3, REASON);

        assertThat(fingerprint).matches("^[0-9a-f]{64}$")
            .isEqualTo(DocumentDeletionFingerprint.of("doc-1", 3, DocumentDeletionReason.parse(" Uploaded in error")));
        assertThat(DocumentDeletionFingerprint.of("doc-2", 3, REASON)).isNotEqualTo(fingerprint);
        assertThat(DocumentDeletionFingerprint.of("doc-1", 4, REASON)).isNotEqualTo(fingerprint);
        assertThat(DocumentDeletionFingerprint.of("doc-1", 3, DocumentDeletionReason.parse("Uploaded in error.")))
            .isNotEqualTo(fingerprint);
    }

    @Test
    @DisplayName("a document id cannot carry a line break into the next field")
    void noFieldRunsOn() {
        assertThat(DocumentDeletionFingerprint.of("doc-1", 13, REASON))
            .isNotEqualTo(DocumentDeletionFingerprint.of("doc-11", 3, REASON));
        assertThatThrownBy(() -> DocumentDeletionFingerprint.of("doc\n1", 3, REASON))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
