package uk.gov.hmcts.reform.deedpoll.ccd;

import uk.gov.hmcts.ccd.sdk.api.CCD;

/**
 * The CCD case's one state, which is technical and says nothing about the application.
 *
 * <p>The relational record is authoritative (plan 02, decision 1), so the case's progress lives in
 * {@code deedpoll_deedpoll} and is never mirrored here. {@link #Registered} echoes none of the relational case
 * states on purpose: a CCD state named after one would be read as that state, and would be wrong the moment the
 * record moved on. The name is an implementation choice agreed 2026-09-27, not an onboarded identifier.
 */
public enum DeedPollCaseState {

    @CCD(label = "Registered")
    Registered
}
