package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The referral windows as the queue and the send see them (plan F1): the window open now, if any, the next to open,
 * the cap and how much of it is used, and each available Master's share.
 *
 * @param current the window open now, or null between windows and on a day that is not a working day
 * @param closesAt when {@code current} closes, or null with it
 * @param next the next window to open: {@code current}'s successor when one is open
 * @param opensAt when {@code next} opens
 * @param cap how many referrals may be sent in one window, across every Master ({@code windowCap()})
 * @param sent how many were sent in {@code current} (decision A-2: any state since), or 0 when none is open
 * @param masters every available Master, in the list's order, with their share of {@code sent}
 */
public record ReferralQueueWindow(ReferralWindow current, Instant closesAt, ReferralWindow next, Instant opensAt,
                                  int cap, long sent, List<SentToMaster> masters) {

    public ReferralQueueWindow {
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(opensAt, "opensAt");
        if ((current == null) != (closesAt == null)) {
            throw new IllegalArgumentException("A current window and its closing time are given together");
        }
        masters = List.copyOf(masters);
    }

    /**
     * One available Master and how many referrals were sent to them in the current window. The Masters-list entry's
     * id and name only: never its email or a linked subject.
     */
    public record SentToMaster(UUID id, String name, long sentInWindow) {
    }
}
