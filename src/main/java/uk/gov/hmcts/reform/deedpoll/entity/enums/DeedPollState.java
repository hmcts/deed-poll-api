package uk.gov.hmcts.reform.deedpoll.entity.enums;

/**
 * How far through enrolment a case has reached.
 *
 * <p>Ported from the {@code DeedPollState} enum in {@code reference/deed-poll.prisma}. The member NAMES
 * are the values the {@code "DeedPollState"} Postgres enum stores, so they are spelled exactly as the
 * reference spells them rather than in Java's usual upper case -- renaming one stops it round
 * tripping. The ORDER is the reference's too: each member's position is the Dynamics 365
 * option-set integer commented beside it, and those integers are what the Dataverse exports
 * hold and what a future re-import would expect.
 */
public enum DeedPollState {
    Received, // 1
    ReadyForMaster, // 2
    ReferredToMaster, // 3
    Enroled, // 4
    Returned, // 5
    AwaitingFurtherDocs, // 6
    AwaitingSignature, // 7
    AwaitingEnrolment // 8
}
