package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import java.util.Map;
import java.util.UUID;

/**
 * The current referral window, the per-Master cap, and how many referrals each Master holds in that window: what a
 * caseworker's form needs to say "3 of 10 for this morning" (plan D2). A Master with none is absent from
 * {@link #assigned}.
 */
public record ReferralWindowLoad(ReferralWindow window, int cap, Map<UUID, Long> assigned) {

    public ReferralWindowLoad {
        assigned = Map.copyOf(assigned);
    }

    /** How many referrals one Master holds in the window. */
    public long assignedTo(UUID masterId) {
        return assigned.getOrDefault(masterId, 0L);
    }
}
