package uk.gov.hmcts.reform.deedpoll.notify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Notification;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NotificationState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.NotificationRepository;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The outbox's multi-replica promise against a real Postgres: two drains send one row once between them.
 *
 * <p>{@link NotificationRepositoryTest} proves the claim statement answers one then zero serially, and
 * {@link NotificationOutboxTest} proves a mocked losing claim is not sent. This test joins those halves under
 * the interleaving production creates: the first drain holds the row while its sender is running, the second
 * drain sees the same committed candidate and blocks on the same conditional update, then re-evaluates it after
 * the first commits. If the claim stops re-asserting state or dueness, this sends twice.
 *
 * <p>Not {@code @Transactional}: each worker must enter the proxied outbox with its own {@code REQUIRES_NEW}
 * transaction, and the final read must see what both committed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NotificationOutboxConcurrencyTest extends AbstractPostgresContainerTest {

    private static final int TIMEOUT_SECONDS = 60;

    private static final long OVERLAP_MILLIS = 500;

    private static final UUID PROVIDER_ID = UUID.fromString("64904498-ab1c-47ad-8894-a65754802670");

    @Autowired
    private NotificationOutbox outbox;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private DeedPollRepository deedPolls;

    @MockitoBean
    private NotificationSender sender;

    @BeforeEach
    @AfterEach
    void clearTables() {
        notifications.deleteAll();
        deedPolls.deleteAll();
    }

    @Test
    @DisplayName("two drains racing over one Pending row send it once between them")
    void concurrentDrainsSendOneEmail() throws Exception {
        final Notification owed = owedNotification();
        CountDownLatch firstSending = new CountDownLatch(1);
        CountDownLatch secondStarting = new CountDownLatch(1);
        given(sender.send(any(Notification.class))).willAnswer(call -> {
            firstSending.countDown();
            await(secondStarting);
            sleep(OVERLAP_MILLIS);
            return SendOutcome.sent(PROVIDER_ID, StubNotify.TEMPLATE_VERSION);
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> outbox.drain(1));
            Future<Integer> second = pool.submit(() -> {
                await(firstSending);
                secondStarting.countDown();
                return outbox.drain(1);
            });

            assertThat(first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                + second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isOne();
        } finally {
            pool.shutdownNow();
        }

        verify(sender, times(1)).send(any(Notification.class));
        Notification stored = notifications.findById(owed.getId()).orElseThrow();
        assertThat(stored.getState()).isEqualTo(NotificationState.Sent);
        assertThat(stored.getAttempts()).isOne();
        assertThat(stored.getProviderNotificationId()).isEqualTo(PROVIDER_ID);
        assertThat(stored.getTemplateVersion()).isEqualTo(StubNotify.TEMPLATE_VERSION);
    }

    private Notification owedNotification() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(LocalDateTime.of(2026, 1, 2, 10, 0));
        deedPoll = deedPolls.saveAndFlush(deedPoll);

        Notification notification = new Notification();
        notification.setDeedPollId(deedPoll.getId());
        notification.setTemplate(EmailTemplate.APPLICATION_REJECTED.name());
        notification.setExclusionGroup(EmailTemplate.APPLICATION_REJECTED.exclusionGroup().name());
        notification.setRecipient("applicant@example.com");
        notification.setPersonalisation(Map.of(EmailSlot.JUDGES_REASON.placeholder(), "No evidence of use"));
        notification.setNextAttemptAt(LocalDateTime.now().minusMinutes(1));
        notification.setCreatedBy("A Caseworker");
        return notifications.saveAndFlush(notification);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the other drain never reached its signal");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
