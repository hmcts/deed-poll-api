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
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralService;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralWindow;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralWindows;
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
import java.time.Instant;
import java.time.LocalDate;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.reform.deedpoll.domain.masters.MasterDigestWindowsTest.london;

/**
 * {@link MasterDigests} against real Postgres and a WireMock Notify (plan D3, step 2): a closed window's digests, one
 * per Master, in the Master's page order with their links; nothing for an empty window, a stale one or a weekend;
 * once between two pods; and a window recorded {@code Skipped} before provisioning is never sent afterwards. The
 * unprovisioned run itself is {@link MasterDigestsUnprovisionedTest}'s.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "notify.templates.master-referral-digest=" + StubNotify.TEMPLATE_ID,
    "deedpoll.admin.base-url=https://admin.example.test/"
})
class MasterDigestsTest extends AbstractPostgresContainerTest {

    /** A Wednesday. */
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 11, 4);
    private static final Instant AFTER_WEDNESDAY_AM = london(2026, 11, 4, 10, 31);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final StubNotify STUB = new StubNotify();

    private static final CurrentUser CASEWORKER = CurrentUser.currentUser(List.of(), "Ada Caseworker", "caseworker-1");

    @Autowired private MasterEmailTransactions transactions;
    @Autowired private ProcessSettingsService settings;
    @Autowired private BankHolidays bankHolidays;
    @Autowired private ReferralService referralService;
    @Autowired private DeedPollRepository cases;
    @Autowired private ReferralRepository referrals;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;

    private MasterDigests digests;
    private final List<UUID> mastersMade = new ArrayList<>();
    private final List<String> casesMade = new ArrayList<>();
    private final List<LocalDate> windowDays = new ArrayList<>();

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
        digests = new MasterDigests(transactions,
                                    new MasterEmailDelivery(transactions, new MasterEmailSender(STUB.client())),
                                    settings, bankHolidays);
    }

    @AfterEach
    void tearDown() {
        mastersMade.forEach(id -> jdbc.update("DELETE FROM deedpoll_master_email WHERE master_id = ?", id));
        casesMade.forEach(id -> jdbc.update("DELETE FROM \"deedpoll_deedpoll\" WHERE \"id\" = ?", id));
        mastersMade.forEach(id -> jdbc.update("DELETE FROM deedpoll_master WHERE id = ?", id));
        windowDays.forEach(day -> jdbc.update(
            "DELETE FROM deedpoll_window_digest_run WHERE window_date BETWEEN ? AND ?", day.minusDays(3), day));
    }

    @Test
    @DisplayName("a closed window with referrals for two Masters sends each one digest, in page order with links")
    void twoMasters() throws IOException {
        UUID sullivan = master("master.sullivan@example.test", "Master Sullivan");
        UUID okafor = master("master.okafor@example.test", "Master Okafor");
        final Referral olderNormal = assigned("DP2026-000101", sullivan, WEDNESDAY, WindowSlot.AM, Urgency.Normal,
                                        LocalDateTime.of(2026, 11, 2, 9, 0));
        final Referral newerUrgent = assigned("DP2026-000102", sullivan, WEDNESDAY, WindowSlot.AM, Urgency.Urgent,
                                        LocalDateTime.of(2026, 11, 3, 9, 0));
        final Referral newerNormal = assigned("DP2026-000103", sullivan, WEDNESDAY, WindowSlot.AM, Urgency.Normal,
                                        LocalDateTime.of(2026, 11, 3, 10, 0));
        final Referral okaforsOwn = assigned("DP2026-000104", okafor, WEDNESDAY, WindowSlot.AM, Urgency.Normal,
                                       LocalDateTime.of(2026, 11, 3, 9, 0));
        // Not in the window: the afternoon's, and one already directed.
        assigned("DP2026-000105", sullivan, WEDNESDAY, WindowSlot.PM, Urgency.Normal,
                 LocalDateTime.of(2026, 11, 3, 9, 0));
        Referral directed = assigned("DP2026-000106", sullivan, WEDNESDAY, WindowSlot.AM, Urgency.Normal,
                                     LocalDateTime.of(2026, 11, 3, 9, 0));
        jdbc.update("UPDATE \"deedpoll_referral\" SET \"state\" = 'Decided' WHERE \"id\" = ?", directed.getId());
        ListAppender<ILoggingEvent> logs = capture();

        try {
            assertThat(digests.run(AFTER_WEDNESDAY_AM)).isEqualTo(2);
        } finally {
            release(logs);
        }

        assertThat(rows(sullivan)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("kind", "DIGEST").containsEntry("period_key", "2026-11-04:AM")
                .containsEntry("state", "Sent");
            assertThat(ids(row)).containsExactly(newerUrgent.getId(), olderNormal.getId(), newerNormal.getId());
        });
        assertThat(rows(okafor)).singleElement()
            .satisfies(row -> assertThat(ids(row)).containsExactly(okaforsOwn.getId()));

        JsonNode toSullivan = sentTo("master.sullivan@example.test");
        assertThat(toSullivan.path("period").asText()).isEqualTo("the morning of 04-11-26");
        assertThat(toSullivan.path("referral_count").asText()).isEqualTo("3");
        String[] lines = toSullivan.path("referral_list").asText().split("\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).startsWith("DP2026-000102 – ").contains(" – Urgent – ")
            .endsWith(" – https://admin.example.test/judicial/referrals/" + newerUrgent.getId());
        assertThat(lines[1]).startsWith("DP2026-000101 – ")
            .endsWith("/judicial/referrals/" + olderNormal.getId());
        assertThat(lines[2]).startsWith("DP2026-000103 – ")
            .endsWith("/judicial/referrals/" + newerNormal.getId());
        assertThat(sentTo("master.okafor@example.test").path("referral_count").asText()).isEqualTo("1");
        assertThat(STUB.emailRequests()).hasSize(2);

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM deedpoll_window_digest_run WHERE window_date = ? AND window_slot = 'AM'",
            Integer.class, WEDNESDAY)).isOne();
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).noneMatch(line -> line.contains("@"));

        // A later run in the same day sends none of it again.
        assertThat(digests.run(london(2026, 11, 4, 11, 0))).isZero();
        assertThat(STUB.emailRequests()).hasSize(2);
    }

    @Test
    @DisplayName("a window with nothing in it enqueues nothing, and is still recorded as run")
    void emptyWindow() {
        UUID sullivan = master("m-empty@example.test", "Master Sullivan");
        windowDays.add(WEDNESDAY);

        assertThat(digests.run(AFTER_WEDNESDAY_AM)).isZero();

        assertThat(rows(sullivan)).isEmpty();
        assertThat(STUB.emailRequests()).isEmpty();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM deedpoll_window_digest_run WHERE window_date = ? AND window_slot = 'AM'",
            Integer.class, WEDNESDAY)).isOne();
    }

    @Test
    @DisplayName("a referral not assigned to a Masters-list entry is left out, and the rest still go")
    void unassignedLeftOut() {
        UUID sullivan = master("m-legacy@example.test", "Master Sullivan");
        Referral listed = assigned("DP2026-000111", sullivan, WEDNESDAY, WindowSlot.AM, Urgency.Normal,
                                   LocalDateTime.of(2026, 11, 3, 9, 0));
        Referral legacy = assigned("DP2026-000112", sullivan, WEDNESDAY, WindowSlot.AM, Urgency.Normal,
                                   LocalDateTime.of(2026, 11, 3, 9, 0));
        jdbc.update("UPDATE \"deedpoll_referral\" SET \"assignedMasterId\" = NULL, \"claimedById\" = 'judge-legacy'"
                    + " WHERE \"id\" = ?", legacy.getId());

        assertThat(digests.run(AFTER_WEDNESDAY_AM)).isOne();

        assertThat(rows(sullivan)).singleElement()
            .satisfies(row -> assertThat(ids(row)).containsExactly(listed.getId()));
    }

    @Test
    @DisplayName("two pods running the job together enqueue and send each digest once")
    void twoPods() throws Exception {
        UUID sullivan = master("m-race1@example.test", "Master Sullivan");
        UUID okafor = master("m-race2@example.test", "Master Okafor");
        assigned("DP2026-000121", sullivan, WEDNESDAY, WindowSlot.AM, Urgency.Normal,
                 LocalDateTime.of(2026, 11, 3, 9, 0));
        assigned("DP2026-000122", okafor, WEDNESDAY, WindowSlot.AM, Urgency.Normal,
                 LocalDateTime.of(2026, 11, 3, 9, 0));
        MasterDigests other = new MasterDigests(
            transactions, new MasterEmailDelivery(transactions, new MasterEmailSender(STUB.client())), settings,
            bankHolidays);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> {
                start.await();
                return digests.run(AFTER_WEDNESDAY_AM);
            });
            Future<Integer> second = pool.submit(() -> {
                start.await();
                return other.run(AFTER_WEDNESDAY_AM);
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
    @DisplayName("a window that closed 25 hours ago is never sent")
    void staleWindow() {
        UUID sullivan = master("m-stale@example.test", "Master Sullivan");
        assigned("DP2026-000131", sullivan, WEDNESDAY, WindowSlot.AM, Urgency.Normal,
                 LocalDateTime.of(2026, 11, 3, 9, 0));
        windowDays.add(WEDNESDAY.plusDays(1));

        assertThat(digests.run(london(2026, 11, 5, 11, 30))).isZero();

        assertThat(rows(sullivan)).isEmpty();
        assertThat(STUB.emailRequests()).isEmpty();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM deedpoll_window_digest_run WHERE window_date = ? AND window_slot = 'AM'",
            Integer.class, WEDNESDAY)).isZero();
    }

    @Test
    @DisplayName("a Saturday has no window, so nothing is sent on one")
    void saturday() {
        LocalDate saturday = LocalDate.of(2026, 11, 7);
        UUID sullivan = master("m-saturday@example.test", "Master Sullivan");
        // Not a window any assignment would take; written directly to prove the job never looks for one.
        assigned("DP2026-000141", sullivan, saturday, WindowSlot.AM, Urgency.Normal,
                 LocalDateTime.of(2026, 11, 6, 9, 0));

        assertThat(digests.run(london(2026, 11, 7, 15, 0))).isZero();

        assertThat(rows(sullivan)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_window_digest_run WHERE window_date = ?",
                                       Integer.class, saturday)).isZero();
    }

    @Test
    @DisplayName("a window recorded Skipped before the template was provisioned is not sent; the next window is")
    void provisionedLater() {
        UUID sullivan = master("m-later@example.test", "Master Sullivan");
        Referral morning = assigned("DP2026-000161", sullivan, WEDNESDAY, WindowSlot.AM, Urgency.Normal,
                                    LocalDateTime.of(2026, 11, 3, 9, 0));
        final Referral afternoon = assigned("DP2026-000162", sullivan, WEDNESDAY, WindowSlot.PM, Urgency.Normal,
                                      LocalDateTime.of(2026, 11, 3, 9, 0));
        // What an unprovisioned run at 10:35 left: the window recorded, its digest Skipped.
        jdbc.update("INSERT INTO deedpoll_window_digest_run (window_date, window_slot, ran_at) VALUES (?, 'AM', ?)",
                    WEDNESDAY, LocalDateTime.of(2026, 11, 4, 10, 35));
        jdbc.update("""
            INSERT INTO deedpoll_master_email (id, master_id, kind, period_key, referral_ids, state, created_at,
                settled_at)
            VALUES (?, ?, 'DIGEST', '2026-11-04:AM', ?, 'Skipped', ?, ?)
            """, UUID.randomUUID(), sullivan, new UUID[] {morning.getId()}, LocalDateTime.of(2026, 11, 4, 10, 35),
            LocalDateTime.of(2026, 11, 4, 10, 35));

        assertThat(digests.run(london(2026, 11, 4, 14, 35))).isOne();

        assertThat(rows(sullivan)).extracting(row -> row.get("period_key") + " " + row.get("state"))
            .containsExactlyInAnyOrder("2026-11-04:AM Skipped", "2026-11-04:PM Sent");
        assertThat(STUB.emailRequests()).singleElement().satisfies(request -> assertThat(request.getBodyAsString())
            .contains(afternoon.getId().toString()).doesNotContain(morning.getId().toString()));
    }

    @Test
    @DisplayName("refuses to run inside a transaction, before any window is taken")
    void refusesATransaction() {
        windowDays.add(WEDNESDAY);

        assertThatThrownBy(() -> new TransactionTemplate(manager)
            .executeWithoutResult(status -> digests.run(AFTER_WEDNESDAY_AM)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_window_digest_run WHERE window_date = ?",
                                       Integer.class, WEDNESDAY)).isZero();
    }

    private Instant closedAfter(ReferralWindow window) {
        windowDays.add(window.date());
        return ReferralWindows.closesAt(window, settings.referralWindows()).plusSeconds(60);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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
     * A referral assigned to {@code master} in a window, written directly, raised at {@code createdOn}.
     */
    private Referral assigned(String displayReference, UUID master, LocalDate day, WindowSlot slot, Urgency urgency,
                              LocalDateTime createdOn) {
        windowDays.add(day);
        Referral referral = open(displayReference);
        referral.setState(ReferralState.Claimed);
        referral.setUrgency(urgency);
        referral.setClaimedBy("Master");
        referral.setClaimedAt(createdOn);
        referral.setAssignedMasterId(master);
        referral.setAssignedAt(createdOn);
        referral.setWindowDate(day);
        referral.setWindowSlot(slot);
        Referral saved = referrals.saveAndFlush(referral);
        jdbc.update("UPDATE \"deedpoll_referral\" SET \"createdOn\" = ? WHERE \"id\" = ?", createdOn, saved.getId());
        return saved;
    }

    private List<Map<String, Object>> rows(UUID master) {
        return jdbc.queryForList("SELECT * FROM deedpoll_master_email WHERE master_id = ? ORDER BY period_key",
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
        for (Class<?> type : List.of(MasterDigests.class, MasterEmailTransactions.class, MasterEmailDelivery.class,
                                     MasterEmailSender.class)) {
            ((Logger) LoggerFactory.getLogger(type)).addAppender(appender);
        }
        return appender;
    }

    private static void release(ListAppender<ILoggingEvent> appender) {
        for (Class<?> type : List.of(MasterDigests.class, MasterEmailTransactions.class, MasterEmailDelivery.class,
                                     MasterEmailSender.class)) {
            ((Logger) LoggerFactory.getLogger(type)).detachAppender(appender);
        }
    }
}
