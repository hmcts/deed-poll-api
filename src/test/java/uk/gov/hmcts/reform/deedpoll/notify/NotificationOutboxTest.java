package uk.gov.hmcts.reform.deedpoll.notify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.entity.Notification;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NotificationState;
import uk.gov.hmcts.reform.deedpoll.repository.NotificationRepository;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The outbox's state machine and its claiming loop, with no database, no HTTP and no Notify.
 *
 * <p>Container-free because CI excludes the {@code requires-docker} classes, so a rule stated only in a
 * container-backed test is a rule CI does not check -- and every rule here is about what the outbox does
 * with a verdict rather than about SQL. The SQL half is
 * {@code NotificationRepositoryTest}: that the claim is exclusive, that the unique index refuses a
 * duplicate, and that {@code jsonb} round trips are claims only a real Postgres can settle.
 *
 * <p>{@code settle} is asserted directly rather than through {@link NotificationOutbox#drain(int)},
 * because it takes its {@code now} instead of reading the clock -- so the backoff arithmetic is
 * assertable exactly rather than within a tolerance.
 */
class NotificationOutboxTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 12, 0);

    private NotificationRepository notifications;

    private NotificationOutbox outbox;

    private NotificationSender sender;

    @BeforeEach
    void setUp() {
        notifications = mock(NotificationRepository.class);
        sender = mock(NotificationSender.class);
        outbox = new NotificationOutbox(notifications, Optional.of(sender));
        when(notifications.save(any(Notification.class))).thenAnswer(call -> call.getArgument(0));
    }

    private Notification claimed(int attempts) {
        Notification notification = new Notification();
        notification.setId(UUID.randomUUID());
        notification.setDeedPollId("a-case");
        notification.setTemplate("APPLICATION_REJECTED");
        notification.setRecipient("applicant@example.com");
        notification.setState(NotificationState.Pending);
        // As the claim leaves it: the attempt is already counted, and "nextAttemptAt" holds the lease.
        notification.setAttempts(attempts);
        notification.setNextAttemptAt(NotificationBackoff.leaseUntil(NOW));
        notification.setCreatedBy("A Caseworker");
        return notification;
    }

    @Nested
    @DisplayName("settling one outcome")
    class Settling {

        @Test
        @DisplayName("an accepted send is Sent, records the provider id and clears an earlier failure")
        void anAcceptedSendIsSent() {
            Notification row = claimed(3);
            // A row that failed twice and succeeded on the third attempt. Its stored reason is the second
            // attempt's, and leaving it there would make a sent email read as a failed one on the Email tab.
            row.setFailureReason("TEMPORARY: 429 rate limited");
            UUID providerId = UUID.randomUUID();

            boolean accepted = outbox.settle(row, SendOutcome.sent(providerId), NOW);

            assertThat(accepted).isTrue();
            assertThat(row.getState()).isEqualTo(NotificationState.Sent);
            assertThat(row.getProviderNotificationId()).isEqualTo(providerId);
            assertThat(row.getFailureReason()).isNull();
            assertThat(row.getModifiedBy())
                .as("no person drained, so the service account wrote it")
                .isEqualTo(CurrentUser.SERVICE_ACCOUNT_NAME);
            assertThat(row.getModifiedOn()).isEqualTo(NOW);
            verify(notifications).save(row);
        }

        @Test
        @DisplayName("an accepted send with no provider id is still Sent")
        void anAcceptedSendWithNoProviderIdIsStillSent() {
            Notification row = claimed(1);

            // Notify not naming the notification does not mean it did not take it, and treating that as a
            // failure would send a second rejection letter for a delivered one.
            assertThat(outbox.settle(row, SendOutcome.sent(null), NOW)).isTrue();
            assertThat(row.getState()).isEqualTo(NotificationState.Sent);
            assertThat(row.getProviderNotificationId()).isNull();
        }

        @Test
        @DisplayName("a temporary failure stays Pending and backs off, keeping the reason")
        void temporaryFailureStaysPendingAndBacksOff() {
            Notification row = claimed(2);

            boolean accepted =
                outbox.settle(row, SendOutcome.failed(NotificationFailure.TEMPORARY, "429 rate limited"), NOW);

            assertThat(accepted).isFalse();
            assertThat(row.getState())
                .as("still owed, so the next drain tries it again")
                .isEqualTo(NotificationState.Pending);
            // The real backoff, replacing the lease the claim put there. Second attempt, so two minutes.
            assertThat(row.getNextAttemptAt()).isEqualTo(NOW.plusMinutes(2));
            // Kept on a row that is still waiting, so "waiting, and here is why the last attempt did not
            // work" is answerable by the Email tab rather than only by the logs.
            assertThat(row.getFailureReason()).isEqualTo("TEMPORARY: 429 rate limited");
            assertThat(row.getProviderNotificationId()).isNull();
            verify(notifications).save(row);
        }

        @ParameterizedTest
        @EnumSource(value = NotificationFailure.class, names = {"PERMANENT", "TECHNICAL"})
        @DisplayName("a permanent or unclassified failure is terminal and is not backed off")
        void anUnretryableFailureIsTerminal(NotificationFailure failure) {
            Notification row = claimed(1);
            LocalDateTime lease = row.getNextAttemptAt();

            assertThat(outbox.settle(row, SendOutcome.failed(failure, "400 bad template"), NOW)).isFalse();

            // PERMANENT because resending sends the same wrong request; TECHNICAL because nobody read what
            // the status means, and a retry might duplicate a delivery that did happen.
            assertThat(row.getState()).isEqualTo(NotificationState.Failed);
            assertThat(row.getFailureReason()).isEqualTo(failure + ": 400 bad template");
            assertThat(row.getNextAttemptAt())
                .as("not backed off: a terminal row is never claimable again, so nothing reads this")
                .isEqualTo(lease);
        }

        @Test
        @DisplayName("a retryable failure that has run out of attempts is failed, and says so")
        void retryableFailureThatRanOutIsFailed() {
            Notification row = claimed(NotificationBackoff.MAX_ATTEMPTS);

            assertThat(outbox.settle(row, SendOutcome.failed(NotificationFailure.TEMPORARY, "500"), NOW))
                .isFalse();

            assertThat(row.getState()).isEqualTo(NotificationState.Failed);
            // The three ways to reach Failed are deliberately distinguishable on the row: a caseworker
            // reading the Email tab needs to tell "Notify refused this" from "we stopped asking".
            assertThat(row.getFailureReason())
                .isEqualTo("Gave up after " + NotificationBackoff.MAX_ATTEMPTS + " attempts: TEMPORARY: 500");
        }
    }

    @Nested
    @DisplayName("draining")
    class Draining {

        @Test
        @DisplayName("claims each candidate and sends only the ones it won")
        void claimsEachCandidateAndSendsOnlyTheOnesItWon() {
            Notification won = claimed(1);
            UUID lost = UUID.randomUUID();
            when(notifications.findClaimable(eq(NotificationState.Pending), any(), any(Pageable.class)))
                .thenReturn(List.of(won.getId(), lost));
            when(notifications.claimNextDue(eq(won.getId()), any(), any(), eq(CurrentUser.SERVICE_ACCOUNT_NAME)))
                .thenReturn(1);
            // The row another replica's drain took between the candidate list and the claim. Answering 0 is
            // what stops this drain sending a second copy of an email that has already gone.
            when(notifications.claimNextDue(eq(lost), any(), any(), eq(CurrentUser.SERVICE_ACCOUNT_NAME)))
                .thenReturn(0);
            when(notifications.findById(won.getId())).thenReturn(Optional.of(won));
            when(sender.send(won)).thenReturn(SendOutcome.sent(UUID.randomUUID()));

            assertThat(outbox.drain(5)).isEqualTo(1);

            verify(sender).send(won);
            // Never loaded, let alone sent: the conditional claim is the gate, and a drain that read the row
            // first would be one statement further from that being true.
            verify(notifications, never()).findById(lost);
            assertThat(won.getState()).isEqualTo(NotificationState.Sent);
        }

        @Test
        @DisplayName("judges every row in the batch against one instant")
        void judgesEveryRowInTheBatchAgainstOneInstant() {
            Notification first = claimed(1);
            Notification second = claimed(1);
            when(notifications.findClaimable(eq(NotificationState.Pending), any(), any(Pageable.class)))
                .thenReturn(List.of(first.getId(), second.getId()));
            when(notifications.claimNextDue(any(), any(), any(), any())).thenReturn(1);
            when(notifications.findById(first.getId())).thenReturn(Optional.of(first));
            when(notifications.findById(second.getId())).thenReturn(Optional.of(second));
            when(sender.send(any())).thenReturn(SendOutcome.sent(null));

            assertThat(outbox.drain(5)).isEqualTo(2);

            ArgumentCaptor<LocalDateTime> instants = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<LocalDateTime> leases = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(notifications, times(2))
                .claimNextDue(any(), instants.capture(), leases.capture(), eq(CurrentUser.SERVICE_ACCOUNT_NAME));

            // One instant across the batch, for the reason the referral sweep passes its cutoff down rather
            // than recomputing it: a freshly computed instant per row could claim a row against a dueness it
            // did not qualify under. The value cannot be asserted -- the drain reads AuditClock, and there is
            // no injectable clock in this service -- but that there is only one of it can be.
            assertThat(new HashSet<>(instants.getAllValues())).hasSize(1);
            LocalDateTime now = instants.getAllValues().getFirst();
            assertThat(leases.getAllValues())
                .as("and one lease, computed from it")
                .containsExactly(NotificationBackoff.leaseUntil(now), NotificationBackoff.leaseUntil(now));
        }

        @Test
        @DisplayName("counts a sender that throws as a technical failure rather than losing the batch")
        void countsASenderThatThrowsAsATechnicalFailure() {
            Notification throwing = claimed(1);
            Notification after = claimed(1);
            when(notifications.findClaimable(eq(NotificationState.Pending), any(), any(Pageable.class)))
                .thenReturn(List.of(throwing.getId(), after.getId()));
            when(notifications.claimNextDue(any(), any(), any(), any())).thenReturn(1);
            when(notifications.findById(throwing.getId())).thenReturn(Optional.of(throwing));
            when(notifications.findById(after.getId())).thenReturn(Optional.of(after));
            when(sender.send(throwing)).thenThrow(new IllegalStateException("no key configured"));
            when(sender.send(after)).thenReturn(SendOutcome.sent(null));

            assertThat(outbox.drain(5)).isEqualTo(1);

            // A sender should not throw -- the taxonomy is the vocabulary for a failure -- but one row that
            // does must not roll back the batch, and the row has already had its attempt counted.
            assertThat(throwing.getState()).isEqualTo(NotificationState.Failed);
            assertThat(throwing.getFailureReason())
                .isEqualTo("TECHNICAL: IllegalStateException: no key configured");
            assertThat(after.getState())
                .as("the rest of the batch is unaffected")
                .isEqualTo(NotificationState.Sent);
        }

        @Test
        @DisplayName("skips a row that vanished between being claimed and being read")
        void skipsARowThatVanished() {
            UUID vanished = UUID.randomUUID();
            when(notifications.findClaimable(eq(NotificationState.Pending), any(), any(Pageable.class)))
                .thenReturn(List.of(vanished));
            when(notifications.claimNextDue(any(), any(), any(), any())).thenReturn(1);
            // Its case was deleted, and V11's cascade took it. Should not happen inside one transaction, but
            // the alternative to handling it is a NoSuchElementException that loses the rest of the batch.
            when(notifications.findById(vanished)).thenReturn(Optional.empty());

            assertThat(outbox.drain(5)).isZero();
            verifyNoInteractions(sender);
        }

        @Test
        @DisplayName("does nothing at all when there is no sender, leaving every row owed")
        void doesNothingWhenThereIsNoSender() {
            NotificationRepository untouched = mock(NotificationRepository.class);
            NotificationOutbox unwired = new NotificationOutbox(untouched, Optional.empty());

            assertThat(unwired.drain(5)).isZero();

            // The state of this service until the send lands, and the right behaviour rather than a gap: the
            // alternatives are failing rows that could have been sent, or claiming them and discarding them.
            // Nothing is even asked for, so a drain on an unprovisioned service costs one branch.
            verifyNoInteractions(untouched);
        }

        @Test
        @DisplayName("does nothing when asked for no rows")
        void doesNothingWhenAskedForNoRows() {
            assertThat(outbox.drain(0)).isZero();
            assertThat(outbox.drain(-1)).isZero();
            verify(notifications, never()).findClaimable(any(), any(), any(Pageable.class));
            verifyNoInteractions(sender);
        }

        @Test
        @DisplayName("sends nothing when nothing is due")
        void sendsNothingWhenNothingIsDue() {
            when(notifications.findClaimable(eq(NotificationState.Pending), any(), any(Pageable.class)))
                .thenReturn(List.of());

            assertThat(outbox.drain(5)).isZero();
            verifyNoInteractions(sender);
        }

        @Test
        @DisplayName("takes no more rows than the default when asked for none in particular")
        void takesNoMoreRowsThanTheDefault() {
            when(notifications.findClaimable(eq(NotificationState.Pending), any(), any(Pageable.class)))
                .thenReturn(List.of());

            outbox.drain();

            // A drain runs inside somebody's HTTP request, and each row is a call to Notify, so the limit is
            // what stops one caseworker paying for a whole backlog.
            verify(notifications).findClaimable(eq(NotificationState.Pending), any(),
                                                eq(PageRequest.of(0, NotificationOutbox.DEFAULT_LIMIT)));
        }
    }
}
