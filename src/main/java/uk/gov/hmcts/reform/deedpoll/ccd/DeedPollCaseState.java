package uk.gov.hmcts.reform.deedpoll.ccd;

import uk.gov.hmcts.ccd.sdk.api.CCD;

/**
 * The CCD case's states, which are technical and say nothing about the application.
 *
 * <p>The relational record is authoritative (plan 02, decision 1), so the case's progress lives in
 * {@code deedpoll_deedpoll} and is never mirrored here. {@link #Registered} echoes none of the relational case
 * states on purpose: a CCD state named after one would be read as that state, and would be wrong the moment the
 * record moved on. The name is an implementation choice agreed 2026-09-27, not an onboarded identifier.
 */
public enum DeedPollCaseState {

    @CCD(label = "Registered")
    Registered,

    /**
     * Retain and Dispose's terminal state ({@code RetainAndDisposePolicy.DISPOSAL_STATE_ID}, whose spelling is the
     * SDK's): an application deletion has asked CCD to dispose of the case (plan 20261118-dm-application-deletion).
     * Only {@code MarkForDisposal} enters it, and nothing leaves it.
     */
    @CCD(label = "Pending disposal")
    PendingDisposal
}
