package uk.gov.hmcts.reform.deedpoll.entity.enums;

/**
 * What kind of deed poll a case is.
 *
 * <p>Ported from the {@code DeedPollType} enum in {@code reference/deed-poll.prisma}. The member NAMES
 * are the values the {@code "DeedPollType"} Postgres enum stores, so they are spelled exactly as the
 * reference spells them rather than in Java's usual upper case -- renaming one stops it round
 * tripping. The ORDER is the reference's too: each member's position is the Dynamics 365
 * option-set integer commented beside it, and those integers are what the Dataverse exports
 * hold and what a future re-import would expect.
 */
public enum DeedPollType {
    Adult, // 1
    AdultTransgender, // 2
    Minor, // 3
    MinorTransgender, // 4
    DeedOfRelinquishment, // 5
    Unknown // 6
}
