package uk.gov.hmcts.reform.deedpoll.domain.masters;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralWindow;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralWindows;
import uk.gov.hmcts.reform.deedpoll.domain.settings.BankHolidays;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettingsService;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ReferralWindowTimes;
import uk.gov.hmcts.reform.deedpoll.entity.enums.WindowSlot;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The Master's referral digest (plan D3, decisions D-6 and D-7): once a referral window closes, each Master with
 * referrals still assigned to them in it gets one email listing them. {@link MasterDigestTimer} runs it every
 * {@code deedpoll.masters.digest-interval}.
 *
 * <p>Each run looks back {@link #LOOKBACK} for windows that have closed, on working days only, and asks
 * {@link MasterEmailTransactions#enqueueDigests} for each, oldest first. A window is taken once, by whichever pod
 * commits its run row; one older than the look-back is never sent, so a long outage does not flood the Masters with
 * stale digests. A window with nothing in it enqueues nothing. The window times and the bank holidays are read on
 * every run, so a Delivery Manager's change applies to the next.
 *
 * <p><strong>Outside any caller transaction.</strong> Each window is its own commit, and the drain that follows
 * sends outside them all.
 */
@Slf4j
@Service
public class MasterDigests {

    /** How far back a closed window is still sent. */
    public static final Duration LOOKBACK = Duration.ofHours(24);

    private final MasterEmailTransactions transactions;
    private final MasterEmailDelivery delivery;
    private final ProcessSettingsService settings;
    private final BankHolidays bankHolidays;

    public MasterDigests(MasterEmailTransactions transactions, MasterEmailDelivery delivery,
                         ProcessSettingsService settings, BankHolidays bankHolidays) {
        this.transactions = transactions;
        this.delivery = delivery;
        this.settings = settings;
        this.bankHolidays = bankHolidays;
    }

    public int run() {
        return run(Instant.now());
    }

    /**
     * One run, as at {@code now}.
     *
     * @return how many digests were enqueued to send, not counting any written {@code Skipped}
     * @throws IllegalStateException inside a transaction, where a window's run row would commit with the caller
     */
    public int run(Instant now) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The Master digest job must run outside a transaction");
        }
        int pending = 0;
        int skipped = 0;
        for (ReferralWindow window : closedWindows(now, settings.referralWindows(), bankHolidays::isWorkingDay)) {
            Optional<MasterEmailTransactions.DigestEnqueued> enqueued = transactions.enqueueDigests(window);
            if (enqueued.isEmpty()) {
                continue;
            }
            MasterEmailTransactions.DigestEnqueued done = enqueued.get();
            if (done.state() == MasterEmailState.Pending) {
                pending += done.emails();
            } else {
                skipped += done.emails();
            }
            log.info("Digest run for {}: {} Masters, {} referrals with no Masters-list entry not listed",
                     MasterEmailPersonalisation.digestPeriod(window), done.emails(), done.unassigned());
        }
        if (skipped > 0) {
            log.info("{} Master digests recorded as Skipped: template {} is not provisioned", skipped,
                     MasterEmailKind.DIGEST.templateKey());
        }
        if (pending > 0) {
            delivery.drain();
        }
        return pending;
    }

    /**
     * The windows that closed in the {@link #LOOKBACK} before {@code now}, up to and including {@code now}, oldest
     * first. Pure.
     */
    static List<ReferralWindow> closedWindows(Instant now, ReferralWindowTimes times,
                                              Predicate<LocalDate> isWorkingDay) {
        Instant from = now.minus(LOOKBACK);
        LocalDate first = from.atZone(ReferralWindowTimes.ZONE).toLocalDate();
        LocalDate last = now.atZone(ReferralWindowTimes.ZONE).toLocalDate();
        List<ReferralWindow> closed = new ArrayList<>();
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            if (!isWorkingDay.test(day)) {
                continue;
            }
            for (WindowSlot slot : WindowSlot.values()) {
                ReferralWindow window = new ReferralWindow(day, slot);
                Instant closes = ReferralWindows.closesAt(window, times);
                if (closes.isAfter(from) && !closes.isAfter(now)) {
                    closed.add(window);
                }
            }
        }
        return closed;
    }
}
