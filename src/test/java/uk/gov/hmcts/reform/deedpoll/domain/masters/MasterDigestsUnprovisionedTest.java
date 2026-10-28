package uk.gov.hmcts.reform.deedpoll.domain.masters;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.settings.BankHolidays;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettingsService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.WindowSlot;
import uk.gov.hmcts.reform.deedpoll.notify.MasterEmailSender;
import uk.gov.hmcts.reform.deedpoll.notify.StubNotify;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.reform.deedpoll.domain.masters.MasterDigestWindowsTest.london;

/**
 * {@link MasterDigests} with the digest template unprovisioned, as it ships (plan D3, step 6): the window is still
 * recorded and its digest row written {@code Skipped}, nothing reaches Notify, and one INFO names the template key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MasterDigestsUnprovisionedTest extends AbstractPostgresContainerTest {

    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 11, 11);
    private static final StubNotify STUB = new StubNotify();

    @Autowired private MasterEmailTransactions transactions;
    @Autowired private ProcessSettingsService settings;
    @Autowired private BankHolidays bankHolidays;
    @Autowired private DeedPollRepository cases;
    @Autowired private ReferralRepository referrals;
    @Autowired private JdbcTemplate jdbc;

    private UUID master;
    private String caseId;

    @BeforeAll
    static void startNotify() {
        STUB.start();
    }

    @AfterAll
    static void stopNotify() {
        STUB.stop();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM deedpoll_master_email WHERE master_id = ?", master);
        jdbc.update("DELETE FROM \"deedpoll_deedpoll\" WHERE \"id\" = ?", caseId);
        jdbc.update("DELETE FROM deedpoll_master WHERE id = ?", master);
        jdbc.update("DELETE FROM deedpoll_window_digest_run WHERE window_date BETWEEN ? AND ?",
                    WEDNESDAY.minusDays(3), WEDNESDAY);
    }

    @Test
    @DisplayName("records the run and a Skipped digest, sends nothing, and says which template is missing")
    void skipped() {
        master = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO deedpoll_master (id, name, email, created_at, created_by_id, created_by_name)
            VALUES (?, 'Master Sullivan', 'm-unprovisioned@example.test', ?, 'subject-dm', 'Dee Manager')
            """, master, LocalDateTime.now());
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(LocalDateTime.of(2026, 10, 1, 10, 0));
        deedPoll.setDisplayReference("DP2026-000201");
        deedPoll = cases.saveAndFlush(deedPoll);
        caseId = deedPoll.getId();
        Referral referral = new Referral();
        referral.setDeedPoll(deedPoll);
        referral.setReasons(new ArrayList<>(List.of("Other")));
        referral.setState(ReferralState.Claimed);
        referral.setClaimedBy("Master Sullivan");
        referral.setClaimedAt(LocalDateTime.of(2026, 11, 11, 9, 0));
        referral.setAssignedMasterId(master);
        referral.setAssignedAt(LocalDateTime.of(2026, 11, 11, 9, 0));
        referral.setWindowDate(WEDNESDAY);
        referral.setWindowSlot(WindowSlot.AM);
        referrals.saveAndFlush(referral);
        MasterDigests digests = new MasterDigests(
            transactions, new MasterEmailDelivery(transactions, new MasterEmailSender(STUB.client())), settings,
            bankHolidays);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger logger = (Logger) LoggerFactory.getLogger(MasterDigests.class);
        logger.addAppender(logs);

        try {
            assertThat(digests.run(london(2026, 11, 11, 10, 35))).isZero();
            assertThat(digests.run(london(2026, 11, 11, 10, 40))).isZero();
        } finally {
            logger.detachAppender(logs);
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM deedpoll_master_email WHERE master_id = ?", master);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("state", "Skipped").containsEntry("period_key", "2026-11-11:AM")
                .containsEntry("attempts", 0);
            assertThat(row.get("settled_at")).isNotNull();
        });
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM deedpoll_window_digest_run WHERE window_date = ? AND window_slot = 'AM'",
            Integer.class, WEDNESDAY)).isOne();
        assertThat(STUB.emailRequests()).isEmpty();
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
            .filteredOn(line -> line.contains("not provisioned"))
            .singleElement().satisfies(line -> assertThat(line).contains("master-referral-digest"));
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).noneMatch(line -> line.contains("@"));
    }
}
