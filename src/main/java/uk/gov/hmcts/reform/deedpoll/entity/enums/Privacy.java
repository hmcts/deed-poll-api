package uk.gov.hmcts.reform.deedpoll.entity.enums;

/**
 * Whether a case may be published, or is held privately.
 *
 * <p>Ported from the {@code Privacy} enum in {@code reference/deed-poll.prisma}. The member NAMES
 * are the values the {@code "Privacy"} Postgres enum stores, so they are spelled exactly as the
 * reference spells them rather than in Java's usual upper case -- renaming one stops it round
 * tripping. The ORDER is the reference's too: each member's position is the Dynamics 365
 * option-set integer commented beside it, and those integers are what the Dataverse exports
 * hold and what a future re-import would expect.
 */
public enum Privacy {
    Normal, // 1
    Private // 2
}
