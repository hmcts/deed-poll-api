package uk.gov.hmcts.reform.deedpoll.api;

import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionRefusal;

/**
 * The 409 body of a refused deletion request: the fixed words, and the {@link DeletionRefusal} code a client branches
 * on. Neither names the applicant, echoes the note or says which document, email or attempt is unresolved.
 *
 * @param error the refusal's fixed words, safe to show
 * @param code which refusal it is
 */
public record DeletionError(String error, DeletionRefusal code) {

    public static DeletionError from(DeletionConflictError refusal) {
        return new DeletionError(refusal.getMessage(), refusal.refusal());
    }
}
