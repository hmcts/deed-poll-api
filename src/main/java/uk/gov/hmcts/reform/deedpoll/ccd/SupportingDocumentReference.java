package uk.gov.hmcts.reform.deedpoll.ccd;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One supporting document as the case's document event names it (plan 03a, task 4).
 *
 * <p>{@link #toString} never prints the hash token, which is transient (decision 8): a reference that reaches a log
 * line by accident must not carry it.
 *
 * @param id             the deed-poll document id, which is the collection item's id and never changes
 * @param cdamDocumentId the CDAM document id its URLs are built from
 * @param filename       the file's own name
 * @param hashToken      present only for the document being attached by this event
 */
public record SupportingDocumentReference(String id,
                                          UUID cdamDocumentId,
                                          String filename,
                                          Optional<String> hashToken) {

    public SupportingDocumentReference {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cdamDocumentId, "cdamDocumentId");
        Objects.requireNonNull(hashToken, "hashToken");
    }

    @Override
    public String toString() {
        return "SupportingDocumentReference[id=" + id + ", cdamDocumentId=" + cdamDocumentId + ", filename=" + filename
            + ", hashToken=" + (hashToken.isPresent() ? "<redacted>" : "<none>") + "]";
    }
}
