package uk.gov.hmcts.reform.deedpoll.entity.enums;

/**
 * How the application reached the office.
 *
 * <p>Ported from the {@code PostCounter} enum in {@code reference/deed-poll.prisma}. The member NAMES
 * are the values the {@code "PostCounter"} Postgres enum stores, so they are spelled exactly as the
 * reference spells them rather than in Java's usual upper case -- renaming one stops it round
 * tripping. The ORDER is the reference's too: each member's position is the Dynamics 365
 * option-set integer commented beside it, and those integers are what the Dataverse exports
 * hold and what a future re-import would expect.
 */
public enum PostCounter {
    Counter, // 1
    Online, // 2
    Post, // 3
    Unknown // 4
}
