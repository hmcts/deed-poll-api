package uk.gov.hmcts.reform.deedpoll.domain.masters;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
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
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralService;
import uk.gov.hmcts.reform.deedpoll.domain.settings.BankHolidays;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettingsService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;
import uk.gov.hmcts.reform.deedpoll.entity.enums.WindowSlot;
import uk.gov.hmcts.reform.deedpoll.notify.MasterEmailSender;
import uk.gov.hmcts.reform.deedpoll.notify.StubNotify;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.reform.deedpoll.domain.masters.MasterDigestWindowsTest.london;

/**
 * {@link MasterReminders} against real Postgres and a WireMock Notify (plan D3, step 3): a reminder falls due every
 * reminder interval (48 hours, as V40 seeds it) from assignment, stops when the referral is directed or unassigned,
 * restarts on reassignment, waits for the sending hours, is claimed once between two pods, and goes as one email per
 * Master listing all their outstanding work. The unprovisioned run is {@link MasterRemindersUnprovisionedTest}'s.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "notify.templates.master-referral-reminder=" + StubNotify.TEMPLATE_ID,
    "deedpoll.admin.base-url=https://admin.example.test/"
})
class MasterRemindersTest extends AbstractPostgresContainerTest {

    /** A Wednesday, at noon in London, which in November is UTC. */
    private static final Instant NOON = london(2026, 11, 4, 12, 0);
    private static final LocalDateTime NOON_UTC = LocalDateTime.ofInstant(NOON, ZoneOffset.UTC);
    private static final Duration INTERVAL = Duration.ofHours(48);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final StubNotify STUB = new StubNotify();

    private static final CurrentUser LEADER =
        CurrentUser.currentUser(List.of(Role.TEAM_LEADER), "Tia Leader", "leader-1");

    @Autowired private MasterEmailTransactions transactions;
    @Autowired private ProcessSettingsService settings;
    @Autowired private BankHolidays bankHolidays;
    @Autowired private ReferralService referralService;
    @Autowired private DeedPollRepository cases;
    @Autowired private ReferralRepository referrals;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;

    private MasterReminders reminders;
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
        assertThat(settings.reminderInterval()).isEqualTo(INTERVAL);
        reminders = pod();
    }

    @AfterEach
    void tearDown() {
        mastersMade.forEach(id -> jdbc.update("DELETE FROM deedpoll_master_email WHERE master_id = ?", id));
        casesMade.forEach(id -> jdbc.update("DELETE FROM \"deedpoll_deedpoll\" WHERE \"id\" = ?", id));
        mastersMade.forEach(id -> jdbc.update("DELETE FROM deedpoll_master WHERE id = ?", id));
    }

    @Test
    @DisplayName("a referral assigned 47h59m ago is not due; at 48 hours it is due its first reminder")
    void firstReminder() throws IOException {
        UUID sullivan = master("master.sullivan@example.test", "Master Sullivan");
        Referral referral = assigned("DP2026-000301", sullivan, NOON_UTC.minusHours(47).minusMinutes(59));
        ListAppender<ILoggingEvent> logs = capture();

        try {
            assertThat(reminders.run(NOON)).isZero();
            assertThat(rows(sullivan)).isEmpty();
            assertThat(remindersSent(referral)).isZero();

            assertThat(reminders.run(NOON.plusSeconds(60))).isOne();
        } finally {
            release(logs);
        }

        assertThat(remindersSent(referral)).isOne();
        assertThat(rows(sullivan)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("kind", "REMINDER").containsEntry("state", "Sent");
            assertThat((String) row.get("period_key")).startsWith("2026-11-04T12:01:00Z:");
            assertThat(ids(row)).containsExactly(referral.getId());
        });
        JsonNode sent = sentTo("master.sullivan@example.test");
        assertThat(sent.path("master_name").asText()).isEqualTo("Master Sullivan");
        assertThat(sent.path("period").asText()).isEqualTo("waiting for your direction");
        assertThat(sent.path("referral_count").asText()).isEqualTo("1");
        assertThat(sent.path("referral_list").asText()).startsWith("DP2026-000301 – ")
            .endsWith(" – https://admin.example.test/judicial/referrals/" + referral.getId());
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).noneMatch(line -> line.contains("@"));

        // The next run sends nothing more until the second interval ends.
        assertThat(reminders.run(NOON.plus(Duration.ofHours(4)))).isZero();
        assertThat(rows(sullivan)).hasSize(1);
    }

    @Test
    @DisplayName("at 96 hours a referral is due its second reminder, and one owed two by then gets one email")
    void secondReminder() {
        UUID sullivan = master("m-second@example.test", "Master Sullivan");
        Referral once = assigned("DP2026-000311", sullivan, NOON_UTC.minusHours(96));
        jdbc.update("UPDATE \"deedpoll_referral\" SET \"remindersSent\" = 1 WHERE \"id\" = ?", once.getId());
        UUID okafor = master("m-second2@example.test", "Master Okafor");
        Referral never = assigned("DP2026-000312", okafor, NOON_UTC.minusHours(96));

        assertThat(reminders.run(NOON)).isEqualTo(2);

        assertThat(remindersSent(once)).isEqualTo(2);
        assertThat(remindersSent(never)).isEqualTo(2);
        assertThat(rows(sullivan)).hasSize(1);
        assertThat(rows(okafor)).hasSize(1);
    }

    @Test
    @DisplayName("a directed or an unassigned referral is not reminded")
    void directedOrUnassigned() {
        UUID sullivan = master("m-stop@example.test", "Master Sullivan");
        Referral directed = assigned("DP2026-000321", sullivan, NOON_UTC.minusHours(49));
        jdbc.update("UPDATE \"deedpoll_referral\" SET \"state\" = 'Decided', \"decision\" = 'Approved',"
                    + " \"decisionDate\" = ?, \"decidedBy\" = 'Master Sullivan', \"decidedById\" = 'judge-1'"
                    + " WHERE \"id\" = ?", NOON_UTC.minusHours(1), directed.getId());
        Referral unassigned = assigned("DP2026-000322", sullivan, NOON_UTC.minusHours(49));
        jdbc.update("""
            UPDATE "deedpoll_referral" SET "state" = 'Open', "claimedBy" = NULL, "claimedById" = NULL,
                "claimedAt" = NULL, "assignedMasterId" = NULL, "assignedAt" = NULL, "windowDate" = NULL,
                "windowSlot" = NULL
             WHERE "id" = ?
            """, unassigned.getId());

        assertThat(reminders.run(NOON)).isZero();

        assertThat(rows(sullivan)).isEmpty();
        assertThat(remindersSent(directed)).isZero();
        assertThat(remindersSent(unassigned)).isZero();
        assertThat(STUB.emailRequests()).isEmpty();
    }

    @Test
    @DisplayName("reassignment resets the count, and the new Master is reminded from their own assignment")
    void reassignment() {
        UUID sullivan = master("m-from@example.test", "Master Sullivan");
        UUID okafor = master("m-to@example.test", "Master Okafor");
        Referral referral = assigned("DP2026-000331", sullivan, NOON_UTC.minusHours(49));
        jdbc.update("UPDATE \"deedpoll_referral\" SET \"remindersSent\" = 1 WHERE \"id\" = ?", referral.getId());

        referralService.assign(referral.getDeedPoll().getId(), referral.getId().toString(),
                               Map.of("masterId", okafor.toString()), LEADER);
        assertThat(remindersSent(referral)).isZero();
        // As if the reassignment were 48 hours before noon.
        jdbc.update("UPDATE \"deedpoll_referral\" SET \"assignedAt\" = ? WHERE \"id\" = ?",
                    NOON_UTC.minusHours(48), referral.getId());

        assertThat(reminders.run(NOON)).isOne();

        assertThat(rows(sullivan)).isEmpty();
        assertThat(rows(okafor)).singleElement()
            .satisfies(row -> assertThat(ids(row)).containsExactly(referral.getId()));
        assertThat(STUB.emailRequests()).singleElement()
            .satisfies(request -> assertThat(request.getBodyAsString()).contains("m-to@example.test"));
    }

    @Test
    @DisplayName("outside 07:00–18:00, at weekends and on holidays nothing goes; the next in-hours run sends it")
    void sendingHours() {
        UUID sullivan = master("m-hours@example.test", "Master Sullivan");
        Referral referral = assigned("DP2026-000341", sullivan, LocalDateTime.of(2026, 12, 21, 12, 0));

        assertThat(reminders.run(london(2026, 12, 23, 18, 0))).isZero();
        assertThat(reminders.run(london(2026, 12, 24, 6, 59))).isZero();
        assertThat(reminders.run(london(2026, 12, 25, 12, 0))).isZero(); // Christmas Day, a Friday
        assertThat(reminders.run(london(2026, 12, 26, 12, 0))).isZero(); // Saturday
        assertThat(reminders.run(london(2026, 12, 28, 12, 0))).isZero(); // Boxing Day's substitute
        assertThat(rows(sullivan)).isEmpty();
        assertThat(remindersSent(referral)).isZero();

        // 08:00 on the 29th is 188 hours after assignment: three intervals, one email.
        assertThat(reminders.run(london(2026, 12, 29, 8, 0))).isOne();
        assertThat(remindersSent(referral)).isEqualTo(3);
        assertThat(rows(sullivan)).hasSize(1);
    }

    @Test
    @DisplayName("two pods running the job together claim each reminder once")
    void twoPods() throws Exception {
        UUID sullivan = master("m-race1@example.test", "Master Sullivan");
        UUID okafor = master("m-race2@example.test", "Master Okafor");
        assigned("DP2026-000351", sullivan, NOON_UTC.minusHours(48));
        assigned("DP2026-000352", okafor, NOON_UTC.minusHours(48));
        MasterReminders other = pod();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> {
                start.await();
                return reminders.run(NOON);
            });
            Future<Integer> second = pool.submit(() -> {
                start.await();
                return other.run(NOON);
            });
            start.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS) + second.get(30, TimeUnit.SECONDS)).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }

        assertThat(rows(sullivan)).hasSize(1);
        assertThat(rows(okafor)).hasSize(1);
        assertThat(STUB.emailRequests()).hasSize(2);
    }

    @Test
    @DisplayName("the email lists every referral waiting for the Master's direction, not only the one that fell due")
    void listsAllOutstanding() throws IOException {
        UUID sullivan = master("m-all@example.test", "Master Sullivan");
        final Referral due = assigned("DP2026-000361", sullivan, NOON_UTC.minusHours(50));
        final Referral recent = assigned("DP2026-000362", sullivan, NOON_UTC.minusHours(2));
        Referral urgent = assigned("DP2026-000363", sullivan, NOON_UTC.minusHours(1));
        jdbc.update("UPDATE \"deedpoll_referral\" SET \"urgency\" = 'Urgent' WHERE \"id\" = ?", urgent.getId());
        Referral directed = assigned("DP2026-000364", sullivan, NOON_UTC.minusHours(3));
        jdbc.update("UPDATE \"deedpoll_referral\" SET \"state\" = 'Decided', \"decision\" = 'Approved',"
                    + " \"decisionDate\" = ?, \"decidedBy\" = 'Master Sullivan', \"decidedById\" = 'judge-1'"
                    + " WHERE \"id\" = ?", NOON_UTC.minusHours(1), directed.getId());

        assertThat(reminders.run(NOON)).isOne();

        assertThat(rows(sullivan)).singleElement().satisfies(row -> assertThat(ids(row))
            .containsExactly(urgent.getId(), due.getId(), recent.getId()));
        assertThat(remindersSent(due)).isOne();
        assertThat(remindersSent(recent)).isZero();
        assertThat(remindersSent(urgent)).isZero();
        assertThat(sentTo("m-all@example.test").path("referral_count").asText()).isEqualTo("3");
    }

    @Test
    @DisplayName("a run key a Master already has rolls the whole run back, claims included")
    void reusedKeyRollsBack() {
        UUID sullivan = master("m-key@example.test", "Master Sullivan");
        Referral referral = assigned("DP2026-000381", sullivan, NOON_UTC.minusHours(48));
        assertThat(transactions.enqueue(sullivan, MasterEmailKind.REMINDER, "run-taken", List.of(referral.getId())))
            .isTrue();

        assertThatThrownBy(() -> transactions.enqueueReminders(NOON_UTC, INTERVAL, "run-taken"))
            .isInstanceOf(IllegalStateException.class);

        assertThat(remindersSent(referral)).isZero();
        assertThat(rows(sullivan)).hasSize(1);
    }

    @Test
    @DisplayName("a pre-V43 claim attached to a Master never falls due itself, but is listed beside one that does")
    void legacyClaim() throws IOException {
        UUID sullivan = master("m-legacy@example.test", "Master Sullivan");
        Referral legacy = assigned("DP2026-000391", sullivan, NOON_UTC.minusHours(200));
        jdbc.update("""
            UPDATE "deedpoll_referral" SET "assignedAt" = NULL, "windowDate" = NULL, "windowSlot" = NULL
             WHERE "id" = ?
            """, legacy.getId());

        assertThat(reminders.run(NOON)).isZero();
        assertThat(rows(sullivan)).isEmpty();

        assigned("DP2026-000392", sullivan, NOON_UTC.minusHours(48));
        assertThat(reminders.run(NOON)).isOne();

        assertThat(remindersSent(legacy)).isZero();
        assertThat(ids(rows(sullivan).getFirst())).contains(legacy.getId());
        assertThat(sentTo("m-legacy@example.test").path("referral_count").asText()).isEqualTo("2");
    }

    @Test
    @DisplayName("refuses to run inside a transaction, before anything is claimed")
    void refusesATransaction() {
        UUID sullivan = master("m-tx@example.test", "Master Sullivan");
        Referral referral = assigned("DP2026-000371", sullivan, NOON_UTC.minusHours(48));

        assertThatThrownBy(() -> new TransactionTemplate(manager)
            .executeWithoutResult(status -> reminders.run(NOON)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(remindersSent(referral)).isZero();
    }

    private MasterReminders pod() {
        return new MasterReminders(transactions,
                                   new MasterEmailDelivery(transactions, new MasterEmailSender(STUB.client())),
                                   settings, bankHolidays);
    }

    private UUID master(String email, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO deedpoll_master (id, name, email, created_at, created_by_id, created_by_name)
            VALUES (?, ?, ?, ?, 'subject-dm', 'Dee Manager')
            """, id, name, email, LocalDateTime.now());
        mastersMade.add(id);
        return id;
    }

    private Referral open(String displayReference) {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(LocalDateTime.of(2026, 10, 1, 10, 0));
        deedPoll.setDisplayReference(displayReference);
        deedPoll = cases.saveAndFlush(deedPoll);
        casesMade.add(deedPoll.getId());
        Referral referral = new Referral();
        referral.setDeedPoll(deedPoll);
        referral.setReasons(new ArrayList<>(List.of("Other")));
        return referrals.saveAndFlush(referral);
    }

    /**
     * A referral assigned to {@code master} at {@code assignedAt} (UTC wall time), raised then, written directly.
     */
    private Referral assigned(String displayReference, UUID master, LocalDateTime assignedAt) {
        Referral referral = open(displayReference);
        referral.setState(ReferralState.Claimed);
        referral.setUrgency(Urgency.Normal);
        referral.setClaimedBy("Master");
        referral.setClaimedAt(assignedAt);
        referral.setAssignedMasterId(master);
        referral.setAssignedAt(assignedAt);
        referral.setWindowDate(LocalDate.from(assignedAt));
        referral.setWindowSlot(WindowSlot.PM);
        Referral saved = referrals.saveAndFlush(referral);
        jdbc.update("UPDATE \"deedpoll_referral\" SET \"createdOn\" = ? WHERE \"id\" = ?", assignedAt, saved.getId());
        return saved;
    }

    private int remindersSent(Referral referral) {
        return jdbc.queryForObject("SELECT \"remindersSent\" FROM \"deedpoll_referral\" WHERE \"id\" = ?",
                                   Integer.class, referral.getId());
    }

    private List<Map<String, Object>> rows(UUID master) {
        return jdbc.queryForList("SELECT * FROM deedpoll_master_email WHERE master_id = ? ORDER BY created_at",
                                 master);
    }

    private static List<UUID> ids(Map<String, Object> row) {
        try {
            return List.of((UUID[]) ((java.sql.Array) row.get("referral_ids")).getArray());
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static JsonNode sentTo(String address) throws IOException {
        for (LoggedRequest request : STUB.emailRequests()) {
            JsonNode body = JSON.readTree(request.getBodyAsString());
            if (address.equals(body.path("email_address").asText())) {
                return body.path("personalisation");
            }
        }
        throw new AssertionError("Nothing was sent to the expected Master");
    }

    private static ListAppender<ILoggingEvent> capture() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        for (Class<?> type : List.of(MasterReminders.class, MasterEmailTransactions.class, MasterEmailDelivery.class,
                                     MasterEmailSender.class)) {
            ((Logger) LoggerFactory.getLogger(type)).addAppender(appender);
        }
        return appender;
    }

    private static void release(ListAppender<ILoggingEvent> appender) {
        for (Class<?> type : List.of(MasterReminders.class, MasterEmailTransactions.class, MasterEmailDelivery.class,
                                     MasterEmailSender.class)) {
            ((Logger) LoggerFactory.getLogger(type)).detachAppender(appender);
        }
    }
}
