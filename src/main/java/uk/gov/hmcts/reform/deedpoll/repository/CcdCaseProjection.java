package uk.gov.hmcts.reform.deedpoll.repository;

/**
 * What a CCD case shows of the deed-poll record it is linked to: the link and the label, and nothing else.
 *
 * <p><strong>A projection rather than the {@code DeedPoll} entity, and that is load-bearing</strong> (plan 02,
 * Task 6). The SDK's {@code CaseView} hands whatever it is given to a Jackson mapper and then to CCD, so an entity
 * reaching it would be one getter away from publishing a hundred columns, and one lazy association away from a
 * query run inside CCD's read. Selecting the two values in the query means no entity is ever loaded to answer it.
 *
 * @param deedPollId       the record's UUID -- the link (decision 8)
 * @param displayReference the {@code KBDP…} reference, for humans only
 */
public record CcdCaseProjection(String deedPollId, String displayReference) {
}
