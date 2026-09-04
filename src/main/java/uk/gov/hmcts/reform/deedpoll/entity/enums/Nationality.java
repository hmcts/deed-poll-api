package uk.gov.hmcts.reform.deedpoll.entity.enums;

/**
 * The citizenship held, for the British Nationality Act assessment.
 *
 * <p>Ported from the {@code Nationality} enum in {@code reference/deed-poll.prisma}. The member NAMES
 * are the values the {@code "Nationality"} Postgres enum stores, so they are spelled exactly as the
 * reference spells them rather than in Java's usual upper case -- renaming one stops it round
 * tripping. The ORDER is the reference's too: each member's position is the Dynamics 365
 * option-set integer commented beside it, and those integers are what the Dataverse exports
 * hold and what a future re-import would expect.
 */
public enum Nationality {
    BritishCitizen, // 1
    BritishDependantTerritoriesCitizen, // 2
    CommonwealthCitizen, // 3
    Unknown // 4
}
