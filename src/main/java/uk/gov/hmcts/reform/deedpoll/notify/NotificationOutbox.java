package uk.gov.hmcts.reform.deedpoll.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.Notification;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NotificationState;
import uk.gov.hmcts.reform.deedpoll.repository.NotificationRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Empties the notification outbox: takes the rows that are owed and due, hands each to Notify, and
 * records what happened.
 *
 * <p><strong>{@link #drain(int)} is a mutating read, on purpose, and it is the same decision
 * {@code ReferralService.reclaimStaleClaims()} is. Do not "fix" it.</strong> This service has no
 * scheduling -- no {@code @EnableScheduling}, no Quartz, no db-scheduler -- and that is deliberate
 * rather than an omission, so there is nothing to notice an owed email on a timer. Rather than
 * introduce one, rows are enqueued in the transaction that causes them and drained as a side effect of
 * caseworker traffic. Three things follow, each a decision:
 *
 * <ul>
 *   <li><strong>It is safe here because the whole feature is the admin system.</strong> Every send is a
 *       caseworker action, so the queue is only ever emptied by the people who fill it. A
 *       citizen-facing send would make a sweep-based outbox the wrong shape, because nothing would
 *       guarantee anyone ever visited.</li>
 *   <li><strong>It is race-safe without a lock.</strong> Each row is taken by one conditional
 *       {@code UPDATE} that re-asserts the row is {@code Pending} and due and moves its lease, so two
 *       replicas draining at the same instant send a given email once between them.
 *       {@code NotificationRepository.claimNextDue} is where that argument lives.</li>
 *   <li><strong>A send may lag the caseworker's click by one read</strong>, which in practice is
 *       immediate: the operation that enqueues also drains. The delay only appears on a row a previous
 *       attempt left backed off.</li>
 * </ul>
 *
 * <p><strong>The drain runs in its own transaction, never the caller's.</strong> {@code REQUIRES_NEW}
 * for two reasons that are both about the caller rather than about the drain: a caseworker read that is
 * {@code readOnly} can still drain without being widened into a write transaction, and a failure
 * settling one row cannot roll back the work of whatever read happened to trigger it. The cost is that
 * a drain holds its claimed rows' locks until it commits, so a concurrent drain blocks on a shared row
 * rather than skipping it -- acceptable while the limit is a handful of rows, and it is still correct:
 * the blocked statement re-evaluates its predicate once the lock is released and takes nothing.
 *
 * <p><strong>Notify is reached only through {@link NotificationSender}, and there may be no
 * implementation.</strong> Until the send lands there is no bean of that type, so a drain claims nothing
 * and every row stays {@code Pending} -- the right answer for a service with no Notify credentials,
 * because the alternatives are failing rows that could have been sent or claiming them and throwing
 * them away.
 */
@Service
public class NotificationOutbox {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationOutbox.class);

    /**
     * How many rows one drain takes.
     *
     * <p>Small, because a drain runs inside somebody's HTTP request: the caller is waiting, and each row
     * is a call to Notify. A backlog is emptied by the next few reads rather than by one caseworker
     * paying for all of it.
     */
    public static final int DEFAULT_LIMIT = 5;

    private final NotificationRepository notifications;

    /**
     * Held as an {@link Optional} rather than resolved to a field that may be null, because "there is no
     * sender yet" is a state this class is designed for rather than a misconfiguration to guard against.
     */
    private final Optional<NotificationSender> sender;

    public NotificationOutbox(NotificationRepository notifications, Optional<NotificationSender> sender) {
        this.notifications = notifications;
        this.sender = sender;
    }

    /**
     * Drains up to {@link #DEFAULT_LIMIT} rows.
     *
     * @return how many notifications Notify accepted
     */
    public int drain() {
        return drain(DEFAULT_LIMIT);
    }

    /**
     * Takes up to {@code limit} owed and due notifications, sends each, and records the outcome.
     *
     * <p>Each row is independent: one that fails does not stop the rest, and one that throws is recorded
     * as {@link NotificationFailure#TECHNICAL} rather than being allowed to roll the batch back.
     *
     * <p>Every row in the batch is judged against one {@code now}, the same instant the candidates were
     * found with -- a freshly computed one per row could claim a row against a dueness it did not
     * qualify under, which is the trap {@code ReferralService.reclaimStaleClaims} avoids the same way.
     *
     * @param limit how many rows to take at most; zero or fewer does nothing
     * @return how many notifications Notify accepted, which is zero when there is no sender
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int drain(int limit) {
        if (limit <= 0 || sender.isEmpty()) {
            return 0;
        }
        NotificationSender active = sender.get();
        LocalDateTime now = AuditClock.now();
        LocalDateTime leaseUntil = NotificationBackoff.leaseUntil(now);

        int sent = 0;
        for (UUID id : notifications.findClaimable(NotificationState.Pending, now, PageRequest.of(0, limit))) {
            if (notifications.claimNextDue(id, now, leaseUntil, CurrentUser.SERVICE_ACCOUNT_NAME) == 0) {
                // Another replica's drain took it, or a previous attempt's lease has not expired. Not a
                // failure and not worth a log line: this is the mechanism working.
                continue;
            }
            // Re-read after the claim rather than before it, so attempts is the incremented value the
            // backoff is computed from. claimNextDue clears the session, so this is a fresh row.
            Optional<Notification> claimed = notifications.findById(id);
            if (claimed.isEmpty()) {
                LOG.warn("Notification {} vanished between being claimed and being read", id);
                continue;
            }
            Notification row = claimed.get();
            if (settle(row, attempt(active, row), now)) {
                sent++;
            }
        }
        return sent;
    }

    /**
     * One attempt, with a throwing sender translated rather than propagated.
     *
     * <p>A {@link NotificationSender} is not supposed to throw -- the taxonomy is the vocabulary for a
     * failure -- but one row that does must not roll back a batch, and the row has already had its
     * attempt counted, so the honest record is a technical failure naming what came out.
     */
    private SendOutcome attempt(NotificationSender active, Notification row) {
        try {
            return active.send(row);
        } catch (RuntimeException exception) {
            LOG.error("Notification {} for case {} threw rather than reporting an outcome",
                      row.getId(), row.getDeedPollId(), exception);
            return SendOutcome.failed(NotificationFailure.TECHNICAL,
                                      exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    /**
     * Writes one outcome onto one row -- the whole state machine, in one place.
     *
     * <p>Package-private and taking {@code now} rather than reading the clock, so the transitions and the
     * backoff arithmetic are assertable exactly, container-free, against a fixed instant.
     *
     * <p>The three ways a row reaches {@code Failed} are deliberately not collapsed:
     * {@link NotificationFailure#PERMANENT} is a request Notify will refuse identically forever,
     * {@link NotificationFailure#TECHNICAL} is an answer nobody enumerated and so is logged at error, and
     * a retryable failure that has run out of attempts is a row that was worth trying and stopped being.
     * A caseworker reading the Email tab needs to tell them apart, so {@code failureReason} says which.
     *
     * @param row     the claimed row, with its attempt already counted
     * @param outcome what came back
     * @param now     the instant to stamp and to measure the backoff from
     * @return true if Notify accepted the send
     */
    boolean settle(Notification row, SendOutcome outcome, LocalDateTime now) {
        boolean accepted = false;
        switch (outcome) {
            case SendOutcome.Sent success -> {
                row.setState(NotificationState.Sent);
                row.setProviderNotificationId(success.providerNotificationId());
                // Cleared, not kept: a row that succeeded on its third attempt should not still read as
                // though the second attempt's failure were its verdict.
                row.setFailureReason(null);
                accepted = true;
            }
            case SendOutcome.Failed failed -> {
                boolean exhausted = NotificationBackoff.exhausted(row.getAttempts());
                if (failed.failure().retryable() && !exhausted) {
                    row.setNextAttemptAt(NotificationBackoff.nextAttemptAt(now, row.getAttempts()));
                    row.setFailureReason(reason(failed));
                } else {
                    row.setState(NotificationState.Failed);
                    row.setFailureReason(exhausted && failed.failure().retryable()
                                             ? "Gave up after " + row.getAttempts() + " attempts: " + reason(failed)
                                             : reason(failed));
                }
                if (failed.failure() == NotificationFailure.TECHNICAL) {
                    LOG.error("Notification {} for case {} failed with an unclassified answer: {}",
                              row.getId(), row.getDeedPollId(), row.getFailureReason());
                } else {
                    LOG.warn("Notification {} for case {} failed ({}, attempt {}): {}",
                             row.getId(), row.getDeedPollId(), row.getState(), row.getAttempts(),
                             row.getFailureReason());
                }
            }
        }
        row.setModifiedBy(CurrentUser.SERVICE_ACCOUNT_NAME);
        row.setModifiedOn(now);
        notifications.save(row);
        return accepted;
    }

    /** The verdict and what Notify said, which is what a human reading the Email tab needs. */
    private static String reason(SendOutcome.Failed failed) {
        return failed.failure() + ": " + failed.reason();
    }
}
