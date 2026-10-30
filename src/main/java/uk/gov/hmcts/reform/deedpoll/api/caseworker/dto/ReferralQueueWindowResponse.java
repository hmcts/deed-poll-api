package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralQueueWindow;
import uk.gov.hmcts.reform.deedpoll.entity.enums.WindowSlot;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * The referral windows as the queue shows them (plan F1): whether one is open, which opens next, the cap across all
 * Masters and how much of it the open window has used, and each available Master's share. Times are UTC, as every
 * timestamp this service publishes is; the windows themselves are Europe/London working days.
 */
public record ReferralQueueWindowResponse(
    @Schema(nullable = true, description = "The window open now, in which referrals can be sent, or null between "
        + "windows and on a day that is not a working day.")
    OpenWindow current,
    @Schema(description = "The next window to open: the open one's successor when one is open.")
    NextWindow next,
    @Schema(description = "The most referrals that can be sent to Masters in one window, across all Masters.")
    int cap,
    @Schema(description = "How many referrals were sent in the open window, whatever has happened to them since, or "
        + "0 when none is open.")
    long sent,
    @Schema(description = "Every available Master, by name, with how many of the referrals sent in the open window "
        + "they hold now: a referral reassigned since keeps its window and counts under its new Master.")
    List<MasterInWindow> masters) {

    /** The window open now, and when it closes. */
    public record OpenWindow(LocalDate date, WindowSlot slot, LocalDateTime closesAt) {
    }

    /** The next window to open, and when it opens. */
    public record NextWindow(LocalDate date, WindowSlot slot, LocalDateTime opensAt) {
    }

    /** One available Master, as their list entry's id and name, never their email or account. */
    public record MasterInWindow(String id, String name, long sentInWindow) {
    }

    public static ReferralQueueWindowResponse from(ReferralQueueWindow window) {
        return new ReferralQueueWindowResponse(
            window.current() == null ? null
                : new OpenWindow(window.current().date(), window.current().slot(), utc(window.closesAt())),
            new NextWindow(window.next().date(), window.next().slot(), utc(window.opensAt())),
            window.cap(),
            window.sent(),
            window.masters().stream()
                .map(master -> new MasterInWindow(master.id().toString(), master.name(), master.sentInWindow()))
                .toList());
    }

    private static LocalDateTime utc(Instant at) {
        return LocalDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
