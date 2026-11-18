package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.api.KeyedRequestBody;

/**
 * The body of {@code POST /caseworker/cases/{id}/deletion-requests}: {@code reason}, {@code note} and
 * {@code expectedModifiedOn}, and nothing else. Keyed, as every write body here is, and read by
 * {@code DeletionBodies.command} only after the {@code DELETE_APPLICATION} check, so a caller without it is a 403
 * whatever they sent. The note is never logged.
 */
public final class CreateDeletionRequest extends KeyedRequestBody {

    /** Keys only: the note must never reach a log line. */
    @Override
    public String toString() {
        return "CreateDeletionRequest" + fields().keySet();
    }
}
