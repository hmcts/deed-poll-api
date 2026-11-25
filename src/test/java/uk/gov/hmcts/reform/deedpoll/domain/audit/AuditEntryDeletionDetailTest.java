package uk.gov.hmcts.reform.deedpoll.domain.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionNote;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionReason;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The deletion detail keys are typed: each refuses a value that is not one of its own, without echoing it. */
class AuditEntryDeletionDetailTest {

    private static final String NOTE = "Duplicate of an application made the same day";

    private static AuditEntry entry() {
        return AuditEntry.of(AuditAction.CASE_DELETION_REQUESTED, AuditActor.system(), AuditEntities.CASE, "case-1",
                             "case-1");
    }

    @Test
    @DisplayName("withDeletion carries the reason, the parsed note and the request id")
    void carriesTheDeletion() {
        UUID requestId = UUID.randomUUID();
        AuditEntry entry = entry().withDeletion(DeletionReason.DUPLICATE, DeletionNote.parse(NOTE), requestId);

        assertThat(entry.detail())
            .containsEntry("deletionReason", "DUPLICATE")
            .containsEntry("deletionNote", NOTE)
            .containsEntry("deletionRequestId", requestId.toString())
            .hasSize(3);
        assertThat(entry.toString()).doesNotContain(NOTE).contains("deletionNote");
        assertThat(AuditAction.CASE_DELETION_REQUESTED.isRead()).isFalse();
    }

    @Test
    @DisplayName("refuses a reason that is not one of the two, a request id that is not a canonical UUID, "
        + "and a note that the request would not have admitted")
    void refusesUntypedValues() {
        assertRefused("deletionReason", "VOID");
        assertRefused("deletionReason", "duplicate");
        assertRefused("deletionRequestId", "not-a-uuid");
        assertRefused("deletionRequestId", UUID.randomUUID().toString().toUpperCase());
        assertRefused("deletionNote", "short");
        assertRefused("deletionNote", " " + NOTE);
        assertRefused("deletionNote", NOTE + "\n" + NOTE);
    }

    @Test
    @DisplayName("the other keys are unchanged, and an unlisted key is still refused")
    void othersUnchanged() {
        assertThat(entry().withDetail("outcome", "anything").detail()).containsEntry("outcome", "anything");
        assertThatThrownBy(() -> entry().withDetail("deletionTarget", "case-2"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertRefused(String key, String value) {
        assertThatThrownBy(() -> entry().withDetail(key, value))
            .isInstanceOf(IllegalArgumentException.class)
            .satisfies(error -> assertThat(error.getMessage()).doesNotContain(value));
    }
}
