package uk.gov.hmcts.reform.deedpoll.entity.enums;

/**
 * The British Nationality Act section the citizenship falls under.
 *
 * <p>Ported from the {@code NationalitySection} enum in {@code reference/deed-poll.prisma}. The member NAMES
 * are the values the {@code "NationalitySection"} Postgres enum stores, so they are spelled exactly as the
 * reference spells them rather than in Java's usual upper case -- renaming one stops it round
 * tripping. The ORDER is the reference's too: each member's position is the Dynamics 365
 * option-set integer commented beside it, and those integers are what the Dataverse exports
 * hold and what a future re-import would expect.
 */
public enum NationalitySection {
    Section1, // 1
    Section11, // 2
    Section37, // 3
    Section6, // 4
    Unknown, // 5
    NoneOfTheAbove // 6
}
