package uk.gov.hmcts.reform.deedpoll.domain.masters;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;
import uk.gov.hmcts.reform.deedpoll.notify.MasterEmailSender;
import uk.gov.hmcts.reform.deedpoll.notify.StubNotify;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link MasterEmailDelivery} against real Postgres and a WireMock Notify (plan D3, step 4): the claim, {@code Sending}
 * committed before the one call, settlement, the retries a known temporary refusal gets, the unknown outcome that is
 * never resent, and the unprovisioned template that sends nothing. The digest template is provisioned here and the
 * reminder template is not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "notify.templates.master-referral-digest=" + StubNotify.TEMPLATE_ID,
    "deedpoll.admin.base-url=https://admin.example.test/"
})
class MasterEmailDeliveryTest extends AbstractPostgresContainerTest {

    private static final String PERIOD = "2026-10-28:AM";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final StubNotify STUB = new StubNotify();

    @Autowired private MasterEmailTransactions transactions;
    @Autowired private DeedPollRepository cases;
    @Autowired private ReferralRepository referrals;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;

    private MasterEmailDelivery delivery;
    private final List<UUID> mastersMade = new ArrayList<>();
    private final List<String> casesMade = new ArrayList<>();

    @BeforeAll
    static void startNotify() {
        STUB.start();
    }

    @AfterAll
    static void stopNotify() {
        STUB.stop();
    }

    @BeforeEach
    void setUp() {
        STUB.stubDefaults();
        delivery = new MasterEmailDelivery(transactions, new MasterEmailSender(STUB.client()));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM deedpoll_master_email");
        casesMade.forEach(id -> jdbc.update("DELETE FROM \"deedpoll_deedpoll\" WHERE \"id\" = ?", id));
        mastersMade.forEach(id -> jdbc.update("DELETE FROM deedpoll_master WHERE id = ?", id));
    }

    @Test
    @DisplayName("sends a pending digest once, to the Master's address, listing its referrals in order with links")
    void sends() throws Exception {
        UUID master = master("master.sullivan@example.test");
        Referral second = referral("DP2026-000002", Urgency.Normal, List.of("Other"));
        Referral first = referral("DP2026-000001", Urgency.Urgent, List.of("NonPublication"));
        UUID row = enqueue(master, MasterEmailKind.DIGEST, List.of(first.getId(), second.getId()));

        assertThat(delivery.drain()).isOne();

        assertThat(STUB.emailRequests()).hasSize(1);
        JsonNode body = JSON.readTree(STUB.emailRequests().getFirst().getBodyAsString());
        assertThat(body.path("template_id").asText()).isEqualTo(StubNotify.TEMPLATE_ID);
        assertThat(body.path("email_address").asText()).isEqualTo("master.sullivan@example.test");
        assertThat(body.path("reference").asText()).isEqualTo(row.toString());
        assertThat(body.has("email_reply_to_id")).isFalse();
        JsonNode personalisation = body.path("personalisation");
        assertThat(fieldNames(personalisation))
            .containsExactlyInAnyOrder("master_name", "referral_count", "period", "referral_list");
        assertThat(personalisation.path("master_name").asText()).isEqualTo("Master Sullivan");
        assertThat(personalisation.path("referral_count").asText()).isEqualTo("2");
        assertThat(personalisation.path("period").asText()).isEqualTo("the morning of 28-10-26");
        String[] lines = personalisation.path("referral_list").asText().split("\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).startsWith("DP2026-000001 – ").contains(" – Urgent – ")
            .endsWith(" – https://admin.example.test/judicial/referrals/" + first.getId());
        assertThat(lines[1]).startsWith("DP2026-000002 – ").contains(" – Standard – ")
            .endsWith("/judicial/referrals/" + second.getId());

        Map<String, Object> stored = stored(row);
        assertThat(stored).containsEntry("state", "Sent").containsEntry("attempts", 1)
            .containsEntry("notify_id", StubNotify.NOTIFICATION_ID).containsEntry("owner", null)
            .containsEntry("lease_until", null).containsEntry("failure", null);
        assertThat(stored.get("send_started_at")).isNotNull();
        assertThat(stored.get("settled_at")).isNotNull();

        assertThat(delivery.drain()).isZero();
        assertThat(STUB.emailRequests()).hasSize(1);
    }

    @Test
    @DisplayName("Sending is committed before the call, and a call that throws is Failed and never resent")
    void sendingBeforeTheCall() {
        UUID master = master("m1@example.test");
        UUID row = enqueue(master, MasterEmailKind.DIGEST, List.of(referral("DP2026-000003").getId()));
        MasterEmailSender sender = mock(MasterEmailSender.class);
        AtomicReference<Map<String, Object>> seen = new AtomicReference<>();
        when(sender.send(any(), any(), any(), any())).thenAnswer(call -> {
            seen.set(stored(row));
            throw new IllegalStateException("connection reset");
        });
        MasterEmailDelivery throwing = new MasterEmailDelivery(transactions, sender);

        assertThat(throwing.drain()).isZero();

        assertThat(seen.get()).containsEntry("state", "Sending").containsEntry("attempts", 1);
        assertThat(seen.get().get("send_started_at")).isNotNull();
        assertThat(stored(row)).containsEntry("state", "Failed").containsEntry("failure", "TECHNICAL");
        assertThat(stored(row).get("send_started_at")).isNotNull();
        assertThat(throwing.drain()).isZero();
        assertThat(delivery.drain()).isZero();
        assertThat(STUB.emailRequests()).isEmpty();
    }

    @Test
    @DisplayName("an ambiguous answer from Notify is Failed, keeps its evidence and is not sent again")
    void unknownOutcome() {
        STUB.stubSendStatus(500);
        UUID row = enqueue(master("m2@example.test"), MasterEmailKind.DIGEST,
                           List.of(referral("DP2026-000004").getId()));

        assertThat(delivery.drain()).isZero();
        assertThat(stored(row)).containsEntry("state", "Failed").containsEntry("failure", "TECHNICAL");
        assertThat(stored(row).get("send_started_at")).isNotNull();

        STUB.stubDefaults();
        assertThat(delivery.drain()).isZero();
        assertThat(STUB.emailRequests()).isEmpty();
    }

    @Test
    @DisplayName("a permanent refusal fails the row at once")
    void permanentRefusal() {
        STUB.stubSendStatus(400);
        UUID row = enqueue(master("m3@example.test"), MasterEmailKind.DIGEST,
                           List.of(referral("DP2026-000005").getId()));

        assertThat(delivery.drain()).isZero();

        assertThat(stored(row)).containsEntry("state", "Failed").containsEntry("failure", "PERMANENT")
            .containsEntry("attempts", 1);
        assertThat(STUB.emailRequests()).hasSize(1);
    }

    @Test
    @DisplayName("a known temporary refusal backs off and is tried again, eight attempts in all, then fails")
    void temporaryRefusalRetries() {
        STUB.stubSendStatus(429);
        UUID row = enqueue(master("m4@example.test"), MasterEmailKind.DIGEST,
                           List.of(referral("DP2026-000006").getId()));

        assertThat(delivery.drain()).isZero();
        Map<String, Object> first = stored(row);
        assertThat(first).containsEntry("state", "Pending").containsEntry("attempts", 1)
            .containsEntry("failure", "TEMPORARY").containsEntry("send_started_at", null)
            .containsEntry("owner", null);
        assertThat(((java.sql.Timestamp) first.get("next_attempt_at")).toLocalDateTime())
            .isAfter(LocalDateTime.now().minusSeconds(5));
        assertThat(delivery.drain()).isZero();
        assertThat(STUB.emailRequests()).as("deferred by the backoff").hasSize(1);

        for (int attempt = 2; attempt <= 8; attempt++) {
            due(row);
            delivery.drain();
        }
        assertThat(STUB.emailRequests()).hasSize(8);
        assertThat(stored(row)).containsEntry("state", "Failed").containsEntry("failure", "TEMPORARY")
            .containsEntry("attempts", 8);
        due(row);
        delivery.drain();
        assertThat(STUB.emailRequests()).hasSize(8);
    }

    @Test
    @DisplayName("a temporary refusal that ends in success is Sent with its failure cleared")
    void temporaryThenSent() {
        STUB.stubSendStatus(429);
        UUID row = enqueue(master("m5@example.test"), MasterEmailKind.DIGEST,
                           List.of(referral("DP2026-000007").getId()));
        delivery.drain();
        STUB.stubDefaults();
        due(row);

        assertThat(delivery.drain()).isOne();
        assertThat(stored(row)).containsEntry("state", "Sent").containsEntry("failure", null)
            .containsEntry("attempts", 2);
    }

    @Test
    @DisplayName("a Sending row whose worker never reported back is Failed once its lease ends, and nothing is sent")
    void abandonedSend() {
        UUID row = enqueue(master("m6@example.test"), MasterEmailKind.DIGEST,
                           List.of(referral("DP2026-000008").getId()));
        jdbc.update("""
            UPDATE deedpoll_master_email SET state = 'Sending', owner = ?, lease_until = ?, attempts = 1,
                send_started_at = ? WHERE id = ?
            """, UUID.randomUUID(), LocalDateTime.now().minusMinutes(1), LocalDateTime.now().minusMinutes(6), row);

        assertThat(delivery.drain()).isZero();

        assertThat(stored(row)).containsEntry("state", "Failed").containsEntry("failure", "TECHNICAL");
        assertThat(STUB.emailRequests()).isEmpty();
    }

    @Test
    @DisplayName("a held claim is left alone, and expired claims use up the attempts without a send")
    void claims() {
        UUID held = enqueue(master("m7@example.test"), MasterEmailKind.DIGEST,
                            List.of(referral("DP2026-000009").getId()));
        jdbc.update("UPDATE deedpoll_master_email SET owner = ?, lease_until = ?, attempts = 1 WHERE id = ?",
                    UUID.randomUUID(), LocalDateTime.now().plusMinutes(4), held);
        UUID exhausted = enqueue(master("m8@example.test"), MasterEmailKind.DIGEST,
                                 List.of(referral("DP2026-000010").getId()));
        jdbc.update("UPDATE deedpoll_master_email SET owner = ?, lease_until = ?, attempts = 8 WHERE id = ?",
                    UUID.randomUUID(), LocalDateTime.now().minusMinutes(1), exhausted);

        assertThat(delivery.drain()).isZero();

        assertThat(stored(held)).containsEntry("state", "Pending").containsEntry("attempts", 1);
        assertThat(stored(exhausted)).containsEntry("state", "Failed").containsEntry("failure", "TEMPORARY")
            .containsEntry("owner", null);
        assertThat(STUB.emailRequests()).isEmpty();
    }

    @Test
    @DisplayName("two drains at once send one email")
    void concurrentDrains() throws Exception {
        enqueue(master("m9@example.test"), MasterEmailKind.DIGEST, List.of(referral("DP2026-000011").getId()));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return delivery.drain();
                }));
            }
            start.countDown();
            int sent = 0;
            for (Future<Integer> run : runs) {
                sent += run.get(30, TimeUnit.SECONDS);
            }
            assertThat(sent).isOne();
        } finally {
            pool.shutdownNow();
        }
        assertThat(STUB.emailRequests()).hasSize(1);
    }

    @Test
    @DisplayName("an unprovisioned template enqueues Skipped and sends nothing; a Pending row of one is Skipped too")
    void unprovisioned() {
        UUID master = master("m10@example.test");
        UUID referral = referral("DP2026-000012").getId();

        assertThat(transactions.enqueue(master, MasterEmailKind.REMINDER, "run-1", List.of(referral)))
            .contains(MasterEmailState.Skipped);
        assertThat(transactions.enqueue(master, MasterEmailKind.REMINDER, "run-1", List.of(referral))).isEmpty();
        UUID pending = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO deedpoll_master_email (id, master_id, kind, period_key, referral_ids, state, created_at)
            VALUES (?, ?, 'REMINDER', 'run-2', ?, 'Pending', ?)
            """, pending, master, new UUID[] {referral}, LocalDateTime.now());

        assertThat(delivery.drain()).isZero();

        assertThat(jdbc.queryForList("SELECT state FROM deedpoll_master_email WHERE master_id = ?", String.class,
                                     master)).containsOnly("Skipped");
        assertThat(stored(pending)).containsEntry("attempts", 0);
        assertThat(stored(pending).get("settled_at")).isNotNull();
        assertThat(STUB.emailRequests()).isEmpty();
    }

    @Test
    @DisplayName("an email none of whose referrals is left is Skipped, not sent empty")
    void nothingLeftToList() {
        UUID row = enqueue(master("m11@example.test"), MasterEmailKind.DIGEST, List.of(UUID.randomUUID()));

        assertThat(delivery.drain()).isZero();

        assertThat(stored(row)).containsEntry("state", "Skipped");
        assertThat(STUB.emailRequests()).isEmpty();
    }

    @Test
    @DisplayName("goes to the address the Masters list holds at send time, and to an unavailable Master too")
    void addressAtSendTime() throws Exception {
        UUID master = master("old.address@example.test");
        enqueue(master, MasterEmailKind.DIGEST, List.of(referral("DP2026-000013").getId()));
        jdbc.update("UPDATE deedpoll_master SET email = 'new.address@example.test', available = false WHERE id = ?",
                    master);

        assertThat(delivery.drain()).isOne();

        assertThat(JSON.readTree(STUB.emailRequests().getFirst().getBodyAsString()).path("email_address").asText())
            .isEqualTo("new.address@example.test");
    }

    @Test
    @DisplayName("no log line and no ledger column carries the Master's address")
    void privacy() {
        Logger root = (Logger) LoggerFactory.getLogger("uk.gov.hmcts.reform.deedpoll");
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        try {
            UUID master = master("private.judge@example.test");
            int day = 1;
            for (int status : new int[] {429, 400, 500}) {
                STUB.stubSendStatus(status);
                enqueue(master, MasterEmailKind.DIGEST, "2026-11-0" + day++ + ":AM",
                        List.of(referral("DP2026-1" + status).getId()));
                delivery.drain();
            }
            STUB.stubDefaults();
            enqueue(master, MasterEmailKind.DIGEST, "2026-11-05:AM", List.of(referral("DP2026-000014").getId()));
            jdbc.update("UPDATE deedpoll_master_email SET next_attempt_at = NULL");
            delivery.drain();
            MasterEmailSender throwing = mock(MasterEmailSender.class);
            when(throwing.send(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("private.judge@example.test"));
            enqueue(master, MasterEmailKind.DIGEST, "2026-11-06:AM", List.of(referral("DP2026-000015").getId()));
            new MasterEmailDelivery(transactions, throwing).drain();
        } finally {
            root.detachAppender(logs);
            logs.stop();
        }

        assertThat(logs.list).isNotEmpty();
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
            .noneMatch(message -> message.contains("@"));
        assertThat(logs.list).allMatch(event -> event.getThrowableProxy() == null);
        assertThat(jdbc.queryForList("SELECT CAST(t AS text) FROM deedpoll_master_email t", String.class))
            .noneMatch(row -> row.contains("@"));
    }

    @Test
    @DisplayName("refuses to drain inside a transaction, before anything is claimed")
    void refusesATransaction() {
        UUID row = enqueue(master("m12@example.test"), MasterEmailKind.DIGEST,
                           List.of(referral("DP2026-000016").getId()));
        MasterEmailSender sender = mock(MasterEmailSender.class);
        MasterEmailDelivery inside = new MasterEmailDelivery(transactions, sender);

        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status -> inside.drain()))
            .isInstanceOf(IllegalStateException.class);
        assertThat(stored(row)).containsEntry("state", "Pending").containsEntry("attempts", 0);
        verifyNoInteractions(sender);
    }

    private UUID master(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO deedpoll_master (id, name, email, created_at, created_by_id, created_by_name)
            VALUES (?, 'Master Sullivan', ?, ?, 'subject-dm', 'Dee Manager')
            """, id, email, LocalDateTime.now());
        mastersMade.add(id);
        return id;
    }

    private Referral referral(String displayReference) {
        return referral(displayReference, Urgency.Normal, List.of("Other"));
    }

    private Referral referral(String displayReference, Urgency urgency, List<String> reasons) {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(LocalDateTime.of(2026, 10, 1, 10, 0));
        deedPoll.setDisplayReference(displayReference);
        deedPoll = cases.saveAndFlush(deedPoll);
        casesMade.add(deedPoll.getId());
        Referral referral = new Referral();
        referral.setDeedPoll(deedPoll);
        referral.setState(ReferralState.Claimed);
        referral.setUrgency(urgency);
        referral.setReasons(new ArrayList<>(reasons));
        return referrals.saveAndFlush(referral);
    }

    private UUID enqueue(UUID master, MasterEmailKind kind, List<UUID> referralIds) {
        return enqueue(master, kind, PERIOD, referralIds);
    }

    private UUID enqueue(UUID master, MasterEmailKind kind, String period, List<UUID> referralIds) {
        assertThat(transactions.enqueue(master, kind, period, referralIds)).isPresent();
        return jdbc.queryForObject(
            "SELECT id FROM deedpoll_master_email WHERE master_id = ? AND kind = ? AND period_key = ?",
            UUID.class, master, kind.name(), period);
    }

    /** Lets a backed-off row be claimed now. */
    private void due(UUID row) {
        jdbc.update("UPDATE deedpoll_master_email SET next_attempt_at = ? WHERE id = ?",
                    LocalDateTime.now().minusSeconds(1), row);
    }

    private Map<String, Object> stored(UUID row) {
        return jdbc.queryForMap("SELECT * FROM deedpoll_master_email WHERE id = ?", row);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
