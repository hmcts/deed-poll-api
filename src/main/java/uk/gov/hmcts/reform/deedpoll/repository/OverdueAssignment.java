package uk.gov.hmcts.reform.deedpoll.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * An assigned referral the processing-window sweep is about to try to return to the office, reduced to the facts
 * releasing it and noting it need.
 *
 * <p><strong>A projection rather than the {@code Referral} entity, and that is load-bearing.</strong> The
 * sweep releases each candidate with a conditional {@code UPDATE} that runs outside the persistence context
 * and then clears it, so entities loaded before it would be detached by the time the loop reached them --
 * and {@code referral.getDeedPoll().getId()} on a detached lazy proxy throws rather than answering. Selecting
 * the values up front means nothing the loop reads can be invalidated by the statement that runs in the
 * middle of it.
 *
 * <p>{@link #deedPollId} is here because the note is written on the case, not on the referral -- there is no
 * timeline of its own -- {@link #claimedBy} because the note names the Master who gave no direction, which is a
 * value the release itself erases, and {@link #createdOn} and {@link #reasons} because the note identifies the
 * referral the way every other referral note does.
 *
 * @param id         the referral
 * @param deedPollId the case it belongs to, which is where its note goes
 * @param claimedBy  the Master it is assigned to, read before the release nulls the column
 * @param createdOn  when it was raised
 * @param reasons    its {@code ReferralReason} names, empty on a backfilled referral
 */
public record OverdueAssignment(UUID id, String deedPollId, String claimedBy, LocalDateTime createdOn,
                                List<String> reasons) {
}
