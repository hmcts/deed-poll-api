package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralWindow;
import uk.gov.hmcts.reform.deedpoll.entity.enums.WindowSlot;

import java.time.LocalDate;

/** One referral window: a working day in Europe/London and its morning (AM) or afternoon (PM). */
public record ReferralWindowResponse(LocalDate date, WindowSlot slot) {

    static ReferralWindowResponse from(ReferralWindow window) {
        return new ReferralWindowResponse(window.date(), window.slot());
    }
}
