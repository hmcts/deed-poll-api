package uk.gov.hmcts.reform.deedpoll.notify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Notification;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NotificationFailureVerdict;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NotificationState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollNoteRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.NotificationRepository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Real commits, two workers and simulated process death at each delivery boundary. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NotificationDeliveryCrashTest extends AbstractPostgresContainerTest {
    @Autowired private NotificationOutbox outbox;
    @Autowired private NotificationDeliveryTransactions transactions;
    @Autowired private NotificationRepository notifications;
    @Autowired private DeedPollRepository cases;
    @Autowired private DeedPollNoteRepository notes;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    @MockitoBean private NotificationSender sender;
    @MockitoSpyBean private NoteService noteService;

    @BeforeEach
    @AfterEach
    void clear() {
        reset(sender, noteService);
        notifications.deleteAll();
        cases.deleteAll();
    }

    static java.util.stream.Stream<EmailTemplate> documentFreeTemplates() {
        return java.util.Arrays.stream(EmailTemplate.values()).filter(t -> t.documentKeys().isEmpty());
    }

    @ParameterizedTest
    @MethodSource("documentFreeTemplates")
    void everyDocumentFreeTemplateUsesTheCommittedDeliveryProtocol(EmailTemplate template) {
        Notification row = owed();
        row.setTemplate(template.name());
        notifications.saveAndFlush(row);
        StubNotify stub = new StubNotify();
        stub.start();
        try {
            var reader = org.mockito.Mockito.mock(
                uk.gov.hmcts.reform.deedpoll.storage.CorrespondenceDocumentReader.class);
            var realSender = new NotifyEmailSender(stub.client(), stub.properties(),
                                                   new DocumentUploads(reader, stub.properties()));
            assertThat(new NotificationOutbox(transactions, Optional.of(realSender)).drain()).isEqualTo(1);
            assertThat(stub.emailRequests()).hasSize(1);
            assertThat(stored(row).getState()).isEqualTo(NotificationState.Sent);
            assertThat(stored(row).getAttempts()).isEqualTo(1);
            assertThat(stored(row).getSendStartedAt()).isNotNull();
            assertThat(stored(row).getPersonalisation()).isEqualTo(row.getPersonalisation());
            verifyNoInteractions(reader);
        } finally {
            stub.stop();
        }
    }

    @Test
    void knownTemporaryRefusalClearsSendAuthorityAndUsesBackoffBeforeAnotherAttempt() {
        final Notification row = owed();
        NotificationClaim first = claim(row);
        assertThat(transactions.startSend(first)).isTrue();
        assertThat(transactions.complete(first, SendOutcome.failed(NotificationFailure.TEMPORARY, "429"))).isFalse();
        assertThat(stored(row).getSendStartedAt()).isNull();
        assertThat(stored(row).getClaimOwner()).isNull();
        assertThat(transactions.claim(row.getId(), AuditClock.now())).isEmpty();
        assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(row.getDeedPollId())).isEmpty();
        expire(row);
        NotificationClaim second = claim(row);
        assertThat(second.generation()).isEqualTo(2);
        assertThat(transactions.startSend(first)).isFalse();
        assertThat(transactions.startSend(second)).isTrue();
        assertThat(transactions.complete(second, SendOutcome.sent(null))).isTrue();
    }

    @Test
    void deathAfterClaimOrDocumentReadAllowsOnlyTheNewOwnerToSendAndSettle() {
        final Notification row = owed();
        NotificationClaim old = claim(row);
        // A prepared file belongs to this attempt; death here has not authorized an email.
        AtomicInteger deliveries = new AtomicInteger();
        final PreparedNotification.Ready prepared = new PreparedNotification.Ready(() -> {
            deliveries.incrementAndGet();
            return SendOutcome.sent(null);
        });
        expire(row);
        NotificationClaim replacement = claim(row);
        assertThat(replacement.owner()).isNotEqualTo(old.owner());
        assertThat(replacement.generation()).isEqualTo(2);
        assertThat(transactions.startSend(old)).isFalse();
        assertThat(transactions.complete(old, SendOutcome.sent(null))).isFalse();
        assertThat(transactions.startSend(replacement)).isTrue();
        assertThat(transactions.startSend(replacement)).isFalse();
        assertThat(transactions.complete(replacement, prepared.delivery().send())).isTrue();
        assertThat(deliveries).hasValue(1);
        assertThat(stored(row).getState()).isEqualTo(NotificationState.Sent);
    }

    @Test
    void expiredOwnerCannotSendOrSettleEvenBeforeAnotherWorkerClaims() {
        final Notification row = owed();
        NotificationClaim claim = claim(row);
        expire(row);
        assertThat(transactions.startSend(claim)).isFalse();
        assertThat(transactions.complete(claim, SendOutcome.sent(null))).isFalse();
        assertThat(stored(row).getState()).isEqualTo(NotificationState.Pending);
    }

    @Test
    void concurrentWorkersCannotBothAcquireSendAuthority() throws Exception {
        NotificationClaim claim = claim(owed());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var task = (java.util.concurrent.Callable<Boolean>) () -> {
                ready.countDown();
                assertThat(go.await(10, TimeUnit.SECONDS)).isTrue();
                return transactions.startSend(claim);
            };
            var first = pool.submit(task);
            var second = pool.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS) ^ second.get(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void repeatedPreSendDeathsStopAtTheAttemptCapWithOneNote() {
        final Notification row = owed();
        for (int attempt = 1; attempt <= NotificationBackoff.MAX_ATTEMPTS; attempt++) {
            assertThat(claim(row).generation()).isEqualTo(attempt);
            expire(row);
        }
        assertThat(transactions.claim(row.getId(), AuditClock.now())).isEmpty();
        assertThat(transactions.claim(row.getId(), AuditClock.now())).isEmpty();
        assertThat(stored(row).getAttempts()).isEqualTo(NotificationBackoff.MAX_ATTEMPTS);
        assertThat(stored(row).getFailureVerdict()).isEqualTo(NotificationFailureVerdict.TEMPORARY);
        assertThat(stored(row).getState()).isEqualTo(NotificationState.Failed);
        assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(row.getDeedPollId())).hasSize(1);
        verifyNoInteractions(sender);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deathAfterSendStartOrProviderAcceptanceNeverResends(boolean accepted) {
        final Notification row = owed();
        NotificationClaim claim = claim(row);
        assertThat(transactions.startSend(claim)).isTrue();
        AtomicInteger calls = new AtomicInteger();
        if (accepted) {
            calls.incrementAndGet(); // Notify accepted, but this process dies before recording the response.
        }
        expire(row);
        when(sender.prepare(any())).thenAnswer(call -> new PreparedNotification.Ready(() -> {
            calls.incrementAndGet();
            return SendOutcome.sent(null);
        }));
        assertThat(outbox.drain()).isZero();
        assertThat(outbox.drain()).isZero();
        assertThat(calls).hasValue(accepted ? 1 : 0);
        assertThat(stored(row).getState()).isEqualTo(NotificationState.Failed);
        assertThat(stored(row).getFailureVerdict()).isEqualTo(NotificationFailureVerdict.TECHNICAL);
        assertThat(stored(row).getSendStartedAt()).isNotNull();
        assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(row.getDeedPollId())).hasSize(1);
        assertThat(transactions.complete(claim, SendOutcome.sent(null))).isFalse();
        verifyNoInteractions(sender);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lostOrFailedOutcomeCommitDoesNotAuthorizeAnotherSend(boolean commitReachedDatabase) {
        final Notification row = owed();
        AtomicInteger calls = new AtomicInteger();
        when(sender.prepare(any())).thenReturn(new PreparedNotification.Ready(() -> {
            calls.incrementAndGet();
            return SendOutcome.sent(null);
        }));
        // Decorate the real transactional collaborator at the coordinator boundary, including lost commit replies.
        NotificationDeliveryTransactions boundary = org.mockito.Mockito.mock(NotificationDeliveryTransactions.class,
            org.mockito.AdditionalAnswers.delegatesTo(transactions));
        doAnswer(call -> {
            if (commitReachedDatabase) {
                transactions.complete(call.getArgument(0), call.getArgument(1));
            } else {
                new TransactionTemplate(manager).executeWithoutResult(status -> {
                    transactions.complete(call.getArgument(0), call.getArgument(1));
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            throw new IllegalStateException("outcome commit connection lost");
                        }
                    });
                });
            }
            throw new IllegalStateException("outcome commit connection lost");
        }).when(boundary).complete(any(), any());
        NotificationOutbox crashing = new NotificationOutbox(boundary, Optional.of(sender));
        assertThatThrownBy(crashing::drain).hasMessageContaining("commit connection lost");
        assertThat(stored(row).getSendStartedAt()).isNotNull();
        expire(row);
        assertThat(outbox.drain()).isZero();
        assertThat(calls).hasValue(1);
        assertThat(stored(row).getState()).isEqualTo(
            commitReachedDatabase ? NotificationState.Sent : NotificationState.Failed);
    }

    @Test
    void failedSendStartCommitNeverCallsDelivery() {
        final Notification row = owed();
        AtomicInteger calls = new AtomicInteger();
        when(sender.prepare(any())).thenReturn(new PreparedNotification.Ready(() -> {
            calls.incrementAndGet();
            return SendOutcome.sent(null);
        }));
        NotificationDeliveryTransactions boundary = org.mockito.Mockito.mock(NotificationDeliveryTransactions.class,
            org.mockito.AdditionalAnswers.delegatesTo(transactions));
        doAnswer(call -> {
            transactions.startSend(call.getArgument(0));
            throw new IllegalStateException("send-start commit reply lost");
        }).when(boundary).startSend(any());
        assertThatThrownBy(() -> new NotificationOutbox(boundary, Optional.of(sender)).drain())
            .hasMessageContaining("commit reply lost");
        expire(row);
        assertThat(outbox.drain()).isZero();
        assertThat(calls).hasValue(0);
        assertThat(stored(row).getState()).isEqualTo(NotificationState.Failed);
    }

    @Test
    void laterNoteRollbackNeverUnsetsEarlierCommittedSend() {
        Notification accepted = owed();
        Notification failing = owed();
        when(sender.prepare(any())).thenAnswer(call -> {
            Notification row = call.getArgument(0);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new PreparedNotification.Ready(() -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(stored(row).getSendStartedAt()).isNotNull();
                return row.getId().equals(accepted.getId()) ? SendOutcome.sent(null)
                    : SendOutcome.failed(NotificationFailure.TECHNICAL, "unknown answer");
            });
        });
        doAnswer(call -> {
            call.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new IllegalStateException("database commit refused");
                }
            });
            return null;
        }).when(noteService).createNote(any(), any(), any());
        assertThatThrownBy(outbox::drain).hasMessageContaining("database commit refused");
        assertThat(stored(accepted).getState()).isEqualTo(NotificationState.Sent);
        assertThat(stored(failing).getState()).isEqualTo(NotificationState.Pending);
        assertThat(stored(failing).getSendStartedAt()).isNotNull();
        assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(failing.getDeedPollId())).isEmpty();
        reset(noteService);
        expire(failing);
        assertThat(outbox.drain()).isZero();
        assertThat(stored(failing).getState()).isEqualTo(NotificationState.Failed);
        assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(failing.getDeedPollId())).hasSize(1);
    }

    @Test
    void laterPreparationFailureLeavesEarlierSendCommittedAndRetriesBeforeSendStart() {
        Notification first = owed();
        Notification second = owed();
        when(sender.prepare(any())).thenAnswer(call -> {
            Notification row = call.getArgument(0);
            if (row.getId().equals(second.getId())) {
                throw new IllegalStateException("file read failed");
            }
            return new PreparedNotification.Ready(() -> SendOutcome.sent(null));
        });
        assertThat(outbox.drain()).isEqualTo(1);
        assertThat(stored(first).getState()).isEqualTo(NotificationState.Sent);
        assertThat(stored(second).getState()).isEqualTo(NotificationState.Pending);
        assertThat(stored(second).getSendStartedAt()).isNull();
        assertThat(stored(second).getClaimOwner()).isNull();
        assertThat(stored(second).getNextAttemptAt()).isAfter(AuditClock.now());
    }

    @Test
    void deathDuringPreparationIsRetryableButDeathInsideDeliveryIsParked() {
        final Notification row = owed();
        doThrow(new SimulatedDeath()).when(sender).prepare(any());
        assertThatThrownBy(outbox::drain).isInstanceOf(SimulatedDeath.class);
        assertThat(stored(row).getAttempts()).isEqualTo(1);
        assertThat(stored(row).getSendStartedAt()).isNull();
        expire(row);
        doReturn(new PreparedNotification.Ready(() -> {
            throw new SimulatedDeath();
        })).when(sender).prepare(any());
        assertThatThrownBy(outbox::drain).isInstanceOf(SimulatedDeath.class);
        assertThat(stored(row).getAttempts()).isEqualTo(2);
        expire(row);
        assertThat(outbox.drain()).isZero();
        assertThat(stored(row).getState()).isEqualTo(NotificationState.Failed);
    }

    @Test
    void noSenderAndNonpositiveLimitDoNotClaimAndAnOuterTransactionIsRefused() {
        final Notification row = owed();
        assertThat(new NotificationOutbox(transactions, Optional.empty()).drain()).isZero();
        assertThat(outbox.drain(0)).isZero();
        assertThat(outbox.drain(-1)).isZero();
        assertThat(stored(row).getAttempts()).isZero();
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status -> outbox.drain()))
            .hasMessageContaining("outside a transaction");
        verifyNoInteractions(sender);
    }

    private NotificationClaim claim(Notification row) {
        return transactions.claim(row.getId(), AuditClock.now()).orElseThrow();
    }

    private Notification stored(Notification row) {
        return notifications.findById(row.getId()).orElseThrow();
    }

    private void expire(Notification row) {
        jdbc.update("UPDATE deedpoll_notification SET \"nextAttemptAt\" = ? WHERE id = ?",
                    AuditClock.now().minusSeconds(1), row.getId());
    }

    private Notification owed() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(AuditClock.now());
        deedPoll = cases.saveAndFlush(deedPoll);
        Notification row = new Notification();
        row.setDeedPollId(deedPoll.getId());
        row.setRequestId(UUID.randomUUID());
        row.setTemplate(EmailTemplate.APPLICATION_REJECTED.name());
        row.setExclusionGroup(EmailTemplate.APPLICATION_REJECTED.exclusionGroup().name());
        row.setRecipient("synthetic@example.com");
        row.setPersonalisation(Map.of("reason", "frozen"));
        row.setNextAttemptAt(AuditClock.now().minusSeconds(10));
        row.setCreatedBy("Caseworker");
        return notifications.saveAndFlush(row);
    }

    private static final class SimulatedDeath extends Error {
        private static final long serialVersionUID = 1L;
    }
}
