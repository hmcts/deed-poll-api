package uk.gov.hmcts.reform.deedpoll.notify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.notes.CreateNoteInput;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteAuthor;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteForm;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;
import uk.gov.hmcts.reform.deedpoll.entity.Notification;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NotificationFailureVerdict;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NotificationState;
import uk.gov.hmcts.reform.deedpoll.repository.NotificationRepository;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The outbox's state machine and its claiming loop, with no database, no HTTP and no Notify.
 *
 * <p>Container-free because CI excludes the {@code requires-docker} classes, so a rule stated only in a
 * container-backed test is a rule CI does not check -- and every rule here is about what the outbox does
 * with a verdict rather than about SQL -- including which verdicts put a note on the case's timeline, which is a
 * rule about the transition and not about the database it is written to. The SQL half is
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

    private NotificationDeliveryTransactions outbox;

    private NotificationSender sender;

    private NoteService notes;

    @BeforeEach
    void setUp() {
        notifications = mock(NotificationRepository.class);
        sender = mock(NotificationSender.class);
        notes = mock(NoteService.class);
        outbox = new NotificationDeliveryTransactions(notifications, notes);
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
            row.setFailureVerdict(NotificationFailureVerdict.TEMPORARY);
            UUID providerId = UUID.randomUUID();

            boolean accepted = outbox.settle(row, SendOutcome.sent(
                providerId,
                7,
                "Your application has been rejected",
                "Dear Alex,\n\nYour application was refused."), NOW);

            assertThat(accepted).isTrue();
            assertThat(row.getState()).isEqualTo(NotificationState.Sent);
            assertThat(row.getProviderNotificationId()).isEqualTo(providerId);
            assertThat(row.getTemplateVersion()).isEqualTo(7);
            assertThat(row.getSubject()).isEqualTo("Your application has been rejected");
            assertThat(row.getBody()).isEqualTo("Dear Alex,\n\nYour application was refused.");
            assertThat(row.getFailureReason()).isNull();
            assertThat(row.getFailureVerdict()).isNull();
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
            assertThat(row.getFailureVerdict()).isEqualTo(NotificationFailureVerdict.TEMPORARY);
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
            assertThat(row.getFailureVerdict()).isEqualTo(switch (failure) {
                case PERMANENT -> NotificationFailureVerdict.PERMANENT;
                case TECHNICAL -> NotificationFailureVerdict.TECHNICAL;
                case TEMPORARY -> throw new IllegalStateException("excluded by @EnumSource");
            });
            assertThat(row.getSubject()).isNull();
            assertThat(row.getBody()).isNull();
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
            assertThat(row.getFailureVerdict()).isEqualTo(NotificationFailureVerdict.TEMPORARY);
        }
    }

    @Nested
    @DisplayName("the case note for a terminal failure")
    class CaseNote {

        @ParameterizedTest
        @EnumSource(value = NotificationFailure.class, names = {"PERMANENT", "TECHNICAL"})
        @DisplayName("writes one note for a failure that is not retryable, naming the email and the tab")
        void writesOneNoteForAnUnretryableFailure(NotificationFailure failure) {
            Notification row = claimed(1);

            outbox.settle(row, SendOutcome.failed(failure, "400 bad template"), NOW);

            // Scoped to the row's own case, and attributed to the caseworker who sent it rather than to the service
            // account that drained it -- so the grid shows their send and its outcome under one name. The composer
            // is the same one the enqueue's note came from, so the pair reads as one story.
            verify(notes).createNote(
                "a-case",
                new CreateNoteInput(EmailNotes.couldNotBeSent(EmailTemplate.APPLICATION_REJECTED)),
                new NoteAuthor("A Caseworker"));
            verify(notes, times(1)).createNote(anyString(), any(), any());
        }

        @Test
        @DisplayName("writes one when a retryable failure has run out of attempts")
        void writesOneWhenARetryableFailureIsExhausted() {
            // The route keying on failureVerdict == PERMANENT would miss, and the more insidious of the two: it
            // looked recoverable, then was not, and nothing is coming back to it.
            Notification row = claimed(NotificationBackoff.MAX_ATTEMPTS);

            outbox.settle(row, SendOutcome.failed(NotificationFailure.TEMPORARY, "500"), NOW);

            assertThat(row.getState()).isEqualTo(NotificationState.Failed);
            assertThat(row.getFailureVerdict()).isEqualTo(NotificationFailureVerdict.TEMPORARY);
            verify(notes).createNote(
                "a-case",
                new CreateNoteInput(EmailNotes.couldNotBeSent(EmailTemplate.APPLICATION_REJECTED)),
                new NoteAuthor("A Caseworker"));
        }

        @Test
        @DisplayName("writes none for a transient failure, because the next drain may well send it")
        void writesNoNoteForATransientFailure() {
            // A note per retry would bury the timeline in attempts that succeeded a minute later, and notes are
            // never revised -- so a case that went out on its second attempt would read for ever as one that failed.
            Notification row = claimed(2);

            outbox.settle(row, SendOutcome.failed(NotificationFailure.TEMPORARY, "429 rate limited"), NOW);

            assertThat(row.getState()).isEqualTo(NotificationState.Pending);
            verifyNoInteractions(notes);
        }

        @Test
        @DisplayName("writes none for an accepted send, because the enqueue already wrote that one")
        void writesNoNoteForAnAcceptedSend() {
            outbox.settle(claimed(1), SendOutcome.sent(UUID.randomUUID()), NOW);

            // CaseEmailService.send wrote the "sent" note in the caseworker's own transaction. A second one here
            // would say the same thing twice, once per drain that happened to take the row.
            verifyNoInteractions(notes);
        }

        @Test
        @DisplayName("writes none for a row that was already Failed, because the note is owed to the transition")
        void writesNoNoteForARowThatWasAlreadyFailed() {
            // Unreachable through drain -- claimNextDue re-asserts Pending, so a Failed row is never handed to
            // settle -- and asserted anyway, because it is what the word "transition" means here. settle is
            // package-private and this is the shape a second settling would have.
            Notification row = claimed(1);
            row.setState(NotificationState.Failed);

            outbox.settle(row, SendOutcome.failed(NotificationFailure.PERMANENT, "400 bad template"), NOW);

            verifyNoInteractions(notes);
        }

        @Test
        @DisplayName("says nothing about why, and stays inside the notes column's bound")
        void saysNothingAboutTheProvidersReason() {
            // The reason is unbounded text from outside this service, so quoting it would put the length bound
            // beyond proof. The outcome and note commit together for this notification.
            // The tab holds the reason and the verdict; that is what the pointer is
            // for. EmailNotesTest pins the arithmetic across the whole enum; this pins it at the call site.
            Notification row = claimed(1);
            String verbose = "Notify said: " + "detail ".repeat(1_000);

            outbox.settle(row, SendOutcome.failed(NotificationFailure.PERMANENT, verbose), NOW);

            ArgumentCaptor<CreateNoteInput> written = ArgumentCaptor.forClass(CreateNoteInput.class);
            verify(notes).createNote(anyString(), written.capture(), any());
            assertThat(written.getValue().note())
                .doesNotContain("Notify said")
                .doesNotContain("detail")
                .doesNotContain(row.getRecipient())
                .hasSizeLessThanOrEqualTo(NoteForm.MAX_NOTE_LENGTH);
            // And the row still carries all of it, so nothing is lost by the note not saying it.
            assertThat(row.getFailureReason()).contains(verbose);
        }

    }
}
