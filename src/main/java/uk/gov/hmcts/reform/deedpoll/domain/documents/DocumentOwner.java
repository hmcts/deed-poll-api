package uk.gov.hmcts.reform.deedpoll.domain.documents;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

import java.util.Objects;
import java.util.Optional;

/**
 * The record a document is being stored for, as {@link DocumentStore} is told it.
 *
 * <p>The filesystem store ignores it. The CDAM store needs it, because CDAM files a document against a CCD case
 * and refuses one for a record that has none yet (plan 03a decision 1) -- which is why the case reference is
 * optional here rather than assumed: a record is linked to CCD only once the sweep or a repair has bound it.
 *
 * @param deedPollId the owning record's id, never null
 * @param ccdCaseReference the record's CCD case, when it has been bound
 */
public record DocumentOwner(String deedPollId, Optional<String> ccdCaseReference) {

    public DocumentOwner {
        Objects.requireNonNull(deedPollId, "deedPollId");
        Objects.requireNonNull(ccdCaseReference, "ccdCaseReference");
    }

    /** The owner as the record says it is now: its id, and its CCD binding if it has one. */
    public static DocumentOwner of(DeedPoll record) {
        return new DocumentOwner(record.getId(), Optional.ofNullable(record.getCcdCaseReference()));
    }
}
