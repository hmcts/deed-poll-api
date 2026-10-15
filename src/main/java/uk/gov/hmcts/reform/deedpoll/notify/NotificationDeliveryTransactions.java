package uk.gov.hmcts.reform.deedpoll.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.notes.CreateNoteInput;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteAuthor;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.Notification;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NotificationFailureVerdict;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NotificationState;
import uk.gov.hmcts.reform.deedpoll.repository.NotificationRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Short database steps. Called through the Spring proxy, never around network work. */
@Slf4j
@Service
public class NotificationDeliveryTransactions {
    private final NotificationRepository notifications;
    private final NoteService notes;

    public NotificationDeliveryTransactions(NotificationRepository notifications, NoteService notes) {
        this.notifications = notifications;
        this.notes = notes;
    }

    @Transactional(readOnly = true)
    public List<UUID> candidates(LocalDateTime cutoff, int limit) {
        return notifications.findClaimable(NotificationState.Pending, cutoff, PageRequest.of(0, limit));
    }

    @Transactional
    public Optional<NotificationClaim> claim(UUID id, LocalDateTime cutoff) {
        Notification row = notifications.lockForDelivery(id).orElse(null);
        LocalDateTime now = AuditClock.now();
        if (row == null || row.getState() != NotificationState.Pending || row.getNextAttemptAt().isAfter(cutoff)) {
            return Optional.empty();
        }
        if (row.getSendStartedAt() != null) {
            settle(row, SendOutcome.failed(NotificationFailure.TECHNICAL,
                "Delivery may have started; investigate before any resend"), now);
            return Optional.empty();
        }
        if (NotificationBackoff.exhausted(row.getAttempts())) {
            settle(row, SendOutcome.failed(NotificationFailure.TEMPORARY,
                "Pre-send claims expired without a completed attempt"), now);
            return Optional.empty();
        }
        UUID owner = UUID.randomUUID();
        if (notifications.claimNextDue(id, now, NotificationBackoff.leaseUntil(now),
                CurrentUser.SERVICE_ACCOUNT_NAME, owner, NotificationBackoff.MAX_ATTEMPTS) != 1) {
            return Optional.empty();
        }
        return notifications.findById(id).map(NotificationClaim::from);
    }

    /** A locked, committed compare-and-set grants send authority once for this generation. */
    @Transactional
    public boolean startSend(NotificationClaim claim) {
        Notification row = notifications.lockForDelivery(claim.id()).orElse(null);
        LocalDateTime now = AuditClock.now();
        if (!owns(row, claim, now) || row.getSendStartedAt() != null) {
            return false;
        }
        row.setSendStartedAt(now);
        notifications.saveAndFlush(row);
        return true;
    }

    /** The outcome and its one failure note either commit together or neither does. */
    @Transactional
    public boolean complete(NotificationClaim claim, SendOutcome outcome) {
        Notification row = notifications.lockForDelivery(claim.id()).orElse(null);
        LocalDateTime now = AuditClock.now();
        if (!owns(row, claim, now)) {
            // Keep enough evidence to find the provider receipt, without logging rendered correspondence.
            switch (outcome) {
                case SendOutcome.Sent sent -> log.error(
                    "Notification {} attempt {} settlement rejected; provider accepted {}, template version {}; "
                        + "investigate before any resend",
                    claim.id(), claim.generation(), sent.providerNotificationId(), sent.templateVersion());
                case SendOutcome.Failed failed -> log.error(
                    "Notification {} attempt {} settlement rejected; outcome {}; investigate before any resend",
                    claim.id(), claim.generation(), failed.failure());
            }
            return false;
        }
        return settle(row, outcome, now);
    }

    private static boolean owns(Notification row, NotificationClaim claim, LocalDateTime now) {
        return row != null && row.getState() == NotificationState.Pending
            && claim.owner().equals(row.getClaimOwner()) && claim.generation() == row.getAttempts()
            && row.getNextAttemptAt().isAfter(now);
    }

    boolean settle(Notification row, SendOutcome outcome, LocalDateTime now) {
        // Read before the switch writes to the row, because the note below is owed to the transition rather than to
        // the state: it is the moment nothing more is coming that a timeline records, and a row settled twice into
        // the same terminal state is one email, so it is one note.
        // Final so that the switch below cannot be read as re-reading it, which is the whole point of taking it here.
        final NotificationState before = row.getState();
        boolean accepted = false;
        switch (outcome) {
            case SendOutcome.Sent success -> {
                row.setState(NotificationState.Sent);
                row.setProviderNotificationId(success.providerNotificationId());
                if (success.templateVersion() != null) {
                    // Written only when Notify named one, so a Notify that answered without a version cannot
                    // blank a version the row already had. This is the column decision 14 exists for: the row was
                    // enqueued with no rendering, so the wording the applicant actually read is knowable only from
                    // the answer to the send.
                    row.setTemplateVersion(success.templateVersion());
                }
                row.setSubject(success.subject());
                row.setBody(success.body());
                // Cleared, not kept: a row that succeeded on its third attempt should not still read as
                // though the second attempt's failure were its verdict.
                row.setFailureReason(null);
                row.setFailureVerdict(null);
                accepted = true;
            }
            case SendOutcome.Failed failed -> {
                row.setFailureVerdict(verdict(failed.failure()));
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
                    log.error("Notification {} for case {} failed with an unclassified answer: {}",
                              row.getId(), row.getDeedPollId(), row.getFailureReason());
                } else {
                    log.warn("Notification {} for case {} failed ({}, attempt {}): {}",
                             row.getId(), row.getDeedPollId(), row.getState(), row.getAttempts(),
                             row.getFailureReason());
                }
            }
        }
        // Only a proved refusal permits another attempt. Terminal rows retain send-start evidence.
        if (row.getState() == NotificationState.Pending) {
            row.setSendStartedAt(null);
            row.setClaimOwner(null);
        }
        row.setModifiedBy(CurrentUser.SERVICE_ACCOUNT_NAME);
        row.setModifiedOn(now);
        notifications.save(row);
        if (before != NotificationState.Failed && row.getState() == NotificationState.Failed) {
            note(row);
        }
        return accepted;
    }

    private void note(Notification row) {
        notes.createNote(row.getDeedPollId(),
                         new CreateNoteInput(EmailNotes.couldNotBeSent(row.getTemplate())),
                         NoteAuthor.service(row.getCreatedBy()));
    }

    /** The verdict and what Notify said, which is what a human reading the Email tab needs. */
    private static String reason(SendOutcome.Failed failed) {
        return failed.failure() + ": " + failed.reason();
    }

    /**
     * The HTTP-aware taxonomy reduced to the enum V12 stores.
     *
     * <p>An exhaustive switch rather than {@code valueOf}: adding a runtime category must make this fail to
     * compile, because the database enum needs a migration before it can store another value.
     */
    private static NotificationFailureVerdict verdict(NotificationFailure failure) {
        return switch (failure) {
            case PERMANENT -> NotificationFailureVerdict.PERMANENT;
            case TEMPORARY -> NotificationFailureVerdict.TEMPORARY;
            case TECHNICAL -> NotificationFailureVerdict.TECHNICAL;
        };
    }
}
