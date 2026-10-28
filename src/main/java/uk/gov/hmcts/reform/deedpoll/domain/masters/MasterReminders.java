package uk.gov.hmcts.reform.deedpoll.domain.masters;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gov.hmcts.reform.deedpoll.domain.settings.BankHolidays;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettingsService;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ReferralWindowTimes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The Master's reminder (plan D3, decision D-4): every {@code reminderInterval()} from a referral's assignment, its
 * Master is due a reminder, and the reminders due in a run go as one email per Master listing every referral still
 * waiting for their direction. {@link MasterReminderTimer} runs it every
 * {@code deedpoll.masters.reminder-interval-check}.
 *
 * <p><strong>Only on working days between {@link #FIRST} and {@link #LAST} {@code Europe/London}.</strong> This
 * plan's own choice, not one of Steve's: a reminder that falls due outside those hours waits for the next run inside
 * them, and is claimed then. A referral owed two by then gets one email, as it is claimed at its current count.
 * Reminders stop when the referral is directed or unassigned, and assignment and withdrawal reset the count with
 * the clock. The interval and the bank holidays are read on every run.
 *
 * <p><strong>Outside any caller transaction.</strong> The claims and the emails are one commit, and the drain that
 * follows sends outside it.
 */
@Slf4j
@Service
public class MasterReminders {

    /** The first local time a reminder is sent. */
    public static final LocalTime FIRST = LocalTime.of(7, 0);

    /** Reminders are sent before this local time, never at or after it. */
    public static final LocalTime LAST = LocalTime.of(18, 0);

    private final MasterEmailTransactions transactions;
    private final MasterEmailDelivery delivery;
    private final ProcessSettingsService settings;
    private final BankHolidays bankHolidays;

    /** This pod's, so two pods' runs in the same minute never share a period key. */
    private final UUID runId = UUID.randomUUID();

    public MasterReminders(MasterEmailTransactions transactions, MasterEmailDelivery delivery,
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
     * @return how many reminders were enqueued to send, not counting any written {@code Skipped}
     * @throws IllegalStateException inside a transaction, where the claims would commit with the caller
     */
    public int run(Instant now) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The Master reminder job must run outside a transaction");
        }
        if (!inSendingHours(now, bankHolidays::isWorkingDay)) {
            return 0;
        }
        MasterEmailTransactions.RemindersEnqueued done = transactions.enqueueReminders(
            LocalDateTime.ofInstant(now, ZoneOffset.UTC), settings.reminderInterval(), periodKey(now, runId));
        if (done.emails() == 0) {
            return 0;
        }
        log.info("Reminder run: {} reminders claimed for {} Masters", done.reminders(), done.emails());
        if (done.state() != MasterEmailState.Pending) {
            log.info("{} Master reminders recorded as Skipped: template {} is not provisioned", done.emails(),
                     MasterEmailKind.REMINDER.templateKey());
            return 0;
        }
        delivery.drain();
        return done.emails();
    }

    /**
     * A working day, from {@link #FIRST} up to but not including {@link #LAST}, in London. Pure.
     */
    static boolean inSendingHours(Instant now, Predicate<LocalDate> isWorkingDay) {
        ZonedDateTime local = now.atZone(ReferralWindowTimes.ZONE);
        LocalTime time = local.toLocalTime();
        return isWorkingDay.test(local.toLocalDate()) && !time.isBefore(FIRST) && time.isBefore(LAST);
    }

    /**
     * The run's start to the minute and the pod's run id: {@code 2026-10-28T10:15:00Z:{uuid}}.
     */
    static String periodKey(Instant now, UUID runId) {
        return now.truncatedTo(ChronoUnit.MINUTES) + ":" + runId;
    }
}
