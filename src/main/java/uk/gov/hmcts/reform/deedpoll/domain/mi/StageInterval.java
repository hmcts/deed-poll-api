package uk.gov.hmcts.reform.deedpoll.domain.mi;

/**
 * K2's milestone-to-milestone intervals, each bucketed by the period its end falls in.
 *
 * <p>There is deliberately no interval for Awaiting further documents: {@code awaitingFurtherDocsInfo} records when
 * documents were asked for, and nothing records when they arrived.
 */
public enum StageInterval {
    /**
     * {@code dateReceived} to {@code dateReadyForMaster}.
     */
    RECEIVED_TO_READY_FOR_MASTER,
    /**
     * {@code dateReadyForMaster} to {@code dateReferred}.
     */
    READY_TO_REFERRED,
    /**
     * {@code dateReferred} to the latest non-null {@code decisionDate} of the case's referrals, or the case's own
     * {@code masterDateOfDecision} when no referral of it is decided.
     */
    REFERRED_TO_DECISION,
    /**
     * {@code signatureRequestedDate} to {@code signaturerecieveddate}.
     */
    SIGNATURE_REQUESTED_TO_RECEIVED,
    /**
     * {@code signaturerecieveddate} to {@code dateEnroled}.
     */
    SIGNATURE_RECEIVED_TO_ENROLED
}
