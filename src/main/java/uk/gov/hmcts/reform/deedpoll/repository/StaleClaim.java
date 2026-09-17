package uk.gov.hmcts.reform.deedpoll.repository;

import java.util.UUID;

/**
 * A claimed referral the sweep is about to try to release, reduced to the three facts releasing it needs.
 *
 * <p><strong>A projection rather than the {@code Referral} entity, and that is load-bearing.</strong> The
 * sweep releases each candidate with a conditional {@code UPDATE} that runs outside the persistence context
 * and then clears it, so entities loaded before it would be detached by the time the loop reached them --
 * and {@code referral.getDeedPoll().getId()} on a detached lazy proxy throws rather than answering. Selecting
 * the three values up front means nothing the loop reads can be invalidated by the statement that runs in the
 * middle of it.
 *
 * <p>{@link #deedPollId} is here because the lapse note is written on the case, not on the referral -- there
 * is no timeline of its own -- and {@link #claimedBy} because the note names the judge whose claim lapsed,
 * which is a value the release itself erases.
 *
 * @param id         the referral
 * @param deedPollId the case it belongs to, which is where its note goes
 * @param claimedBy  the judge holding it, read before the release nulls the column
 */
public record StaleClaim(UUID id, String deedPollId, String claimedBy) {
}
