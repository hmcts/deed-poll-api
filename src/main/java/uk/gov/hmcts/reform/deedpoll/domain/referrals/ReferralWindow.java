package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import uk.gov.hmcts.reform.deedpoll.entity.enums.WindowSlot;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One referral window: a working day in {@code Europe/London} and its morning or afternoon. What a send counts
 * towards for the window's cap, and what {@code V43}'s {@code "windowDate"}/{@code "windowSlot"} store.
 */
public record ReferralWindow(LocalDate date, WindowSlot slot) {

    public ReferralWindow {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(slot, "slot");
    }
}
