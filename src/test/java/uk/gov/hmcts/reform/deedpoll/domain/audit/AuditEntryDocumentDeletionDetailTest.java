package uk.gov.hmcts.reform.deedpoll.domain.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionReason;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The document deletion detail keys are typed by their own rule, separate from application deletion's: neither
 * accepts the other's values by accident, and neither echoes a refused value.
 */
class AuditEntryDocumentDeletionDetailTest {

    private static AuditEntry entry() {
        return AuditEntry.of(AuditAction.DOCUMENT_DELETED, AuditActor.system(), AuditEntities.DOCUMENT, "doc-1",
                             "case-1");
    }

    @Test
    @DisplayName("withDocumentDeletion carries the reason exactly and the request id, and toString hides the reason")
    void carriesTheDeletion() {
        UUID requestId = UUID.randomUUID();
        AuditEntry entry = entry().withDocumentDeletion(DocumentDeletionReason.parse(" Duplicate "), requestId);

        assertThat(entry.detail())
            .containsEntry("documentDeletionReason", "Duplicate")
            .containsEntry("documentDeletionRequestId", requestId.toString())
            .hasSize(2);
        assertThat(entry.toString()).doesNotContain("Duplicate").contains("documentDeletionReason");
        assertThat(AuditAction.DOCUMENT_DELETION_REQUESTED.isRead()).isFalse();
        assertThat(AuditAction.DOCUMENT_DELETED.isRead()).isFalse();
    }

    @Test
    @DisplayName("refuses a reason the request would not have admitted and a request id that is not canonical")
    void refusesUntypedValues() {
        assertRefused("documentDeletionReason", "");
        assertRefused("documentDeletionReason", " Duplicate");
        assertRefused("documentDeletionReason", "Two\nlines");
        assertRefused("documentDeletionReason", "Hidden\u202Etext");
        assertRefused("documentDeletionReason", "a".repeat(501));
        assertRefused("documentDeletionRequestId", "not-a-uuid");
        assertRefused("documentDeletionRequestId", UUID.randomUUID().toString().toUpperCase());
    }

    @Test
    @DisplayName("application deletion's note keeps its own rule: a one-word document reason is not a note")
    void applicationNoteNotRelaxed() {
        assertThat(entry().withDetail("documentDeletionReason", "Duplicate").detail()).hasSize(1);
        assertRefused("deletionNote", "Duplicate");
        assertRefused("deletionReason", "Duplicate");
    }

    private static void assertRefused(String key, String value) {
        assertThatThrownBy(() -> entry().withDetail(key, value))
            .isInstanceOf(IllegalArgumentException.class)
            .satisfies(error -> assertThat(error.getMessage()).doesNotContain(value.isEmpty() ? "\u0000" : value));
    }
}
