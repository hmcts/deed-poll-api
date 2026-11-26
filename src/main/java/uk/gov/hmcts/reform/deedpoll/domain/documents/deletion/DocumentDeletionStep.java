package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One remote step of a document deletion ({@code deedpoll_document_deletion_step}, V51).
 *
 * @param locator the CCD case reference, CDAM document id or storage key to remove; null only once {@code Done}
 * @param outcome an allowlisted code for the last answer, if any
 */
public record DocumentDeletionStep(UUID id, UUID requestId, DocumentDeletionStepKind kind, String locator,
                                   DocumentDeletionStepState state, String outcome, int attempts,
                                   LocalDateTime completedAt) {

    /** Never the locator: a storage key or document id in a log is a way back to the bytes. */
    @Override
    public String toString() {
        return "DocumentDeletionStep[id=" + id + ", kind=" + kind + ", state=" + state + "]";
    }
}
