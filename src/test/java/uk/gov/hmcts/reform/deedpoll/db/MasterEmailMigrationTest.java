package uk.gov.hmcts.reform.deedpoll.db;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code V44__master_emails.sql} (plan D3): the Master email ledger, the digest-run table and the referral's
 * {@code "remindersSent"}. Each refusal is the last statement of its test, because a refused statement aborts the
 * test's transaction.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class MasterEmailMigrationTest extends AbstractPostgresContainerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 10, 31);

    private static final String INSERT_EMAIL = """
        insert into deedpoll_master_email (id, master_id, kind, period_key, referral_ids, state, created_at)
        values (?, ?, ?, ?, array[?]::uuid[], ?, ?)
        on conflict do nothing
        """;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID master() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            insert into deedpoll_master (id, name, email, created_at, created_by_id, created_by_name)
            values (?, 'Master Sullivan', ?, ?, 'subject-dm', 'Dee Manager')
            """, id, id + "@example.test", NOW);
        return id;
    }

    private int email(UUID masterId, String kind, String periodKey, String state) {
        return jdbcTemplate.update(INSERT_EMAIL, UUID.randomUUID(), masterId, kind, periodKey,
                                   UUID.randomUUID().toString(), state, NOW);
    }

    @Test
    @DisplayName("a new email starts with no attempts, no claim and no send")
    void defaults() {
        UUID masterId = master();
        email(masterId, "DIGEST", "2026-10-28:AM", "Pending");

        assertThat(jdbcTemplate.queryForMap("""
            select attempts, owner, lease_until, send_started_at, notify_id, failure, settled_at
              from deedpoll_master_email where master_id = ?
            """, masterId))
            .containsEntry("attempts", 0)
            .containsEntry("owner", null)
            .containsEntry("lease_until", null)
            .containsEntry("send_started_at", null)
            .containsEntry("notify_id", null)
            .containsEntry("failure", null)
            .containsEntry("settled_at", null);
    }

    @Test
    @DisplayName("one Master, kind and period enqueue once; another kind or period is a second email")
    void enqueuesOncePerPeriod() {
        UUID masterId = master();

        assertThat(email(masterId, "DIGEST", "2026-10-28:AM", "Pending")).isOne();
        assertThat(email(masterId, "DIGEST", "2026-10-28:AM", "Pending")).isZero();
        assertThat(email(masterId, "DIGEST", "2026-10-28:PM", "Pending")).isOne();
        assertThat(email(masterId, "REMINDER", "2026-10-28:AM", "Skipped")).isOne();
        assertThat(email(master(), "DIGEST", "2026-10-28:AM", "Pending")).isOne();
    }

    @Test
    @DisplayName("keeps the referral ids in the order they were written")
    void keepsTheOrder() {
        UUID masterId = master();
        UUID first = UUID.fromString("ffffffff-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        jdbcTemplate.update("""
            insert into deedpoll_master_email (id, master_id, kind, period_key, referral_ids, state, created_at)
            values (?, ?, 'DIGEST', '2026-10-28:AM', ?, 'Pending', ?)
            """, UUID.randomUUID(), masterId, new UUID[] {first, second}, NOW);

        UUID[] read = jdbcTemplate.queryForObject(
            "select referral_ids from deedpoll_master_email where master_id = ?",
            (row, number) -> (UUID[]) row.getArray(1).getArray(), masterId);

        assertThat(read).containsExactly(first, second);
    }

    @Test
    @DisplayName("refuses a kind other than DIGEST or REMINDER")
    void refusesAnUnknownKind() {
        UUID masterId = master();

        assertThatThrownBy(() -> email(masterId, "WEEKLY", "2026-10-28:AM", "Pending"))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("deedpoll_master_email_kind_check");
    }

    @Test
    @DisplayName("refuses a state outside the ledger's five")
    void refusesAnUnknownState() {
        UUID masterId = master();

        assertThatThrownBy(() -> email(masterId, "DIGEST", "2026-10-28:AM", "Delivered"))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("deedpoll_master_email_state_check");
    }

    @Test
    @DisplayName("refuses a Master who is not on the list")
    void refusesAnUnknownMaster() {
        assertThatThrownBy(() -> email(UUID.randomUUID(), "DIGEST", "2026-10-28:AM", "Pending"))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("deedpoll_master_email_master_id_fkey");
    }

    @Test
    @DisplayName("refuses an owner without a lease")
    void refusesAnOwnerWithoutALease() {
        UUID masterId = master();
        email(masterId, "DIGEST", "2026-10-28:AM", "Pending");

        assertThatThrownBy(() -> jdbcTemplate.update(
            "update deedpoll_master_email set owner = ? where master_id = ?", UUID.randomUUID(), masterId))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("deedpoll_master_email_claim_check");
    }

    @Test
    @DisplayName("records a closed window once, whichever pod reaches it first")
    void recordsAWindowOnce() {
        String insert = """
            insert into deedpoll_window_digest_run (window_date, window_slot, ran_at) values (?, ?, ?)
            on conflict do nothing
            """;
        LocalDate day = LocalDate.of(2026, 10, 28);

        assertThat(jdbcTemplate.update(insert, day, "AM", NOW)).isOne();
        assertThat(jdbcTemplate.update(insert, day, "AM", NOW.plusMinutes(5))).isZero();
        assertThat(jdbcTemplate.update(insert, day, "PM", NOW)).isOne();
    }

    @Test
    @DisplayName("refuses a digest run for a slot other than AM or PM")
    void refusesAnUnknownSlot() {
        assertThatThrownBy(() -> jdbcTemplate.update(
            "insert into deedpoll_window_digest_run (window_date, window_slot, ran_at) values (?, 'XX', ?)",
            LocalDate.of(2026, 10, 28), NOW))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("deedpoll_window_digest_run_window_slot_check");
    }

    @Test
    @DisplayName("a referral starts with no reminders, and the entity maps the count")
    void remindersSent() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(LocalDateTime.of(2026, 1, 2, 10, 0));
        entityManager.persist(deedPoll);
        Referral referral = new Referral();
        referral.setDeedPoll(deedPoll);
        referral.setState(ReferralState.Open);
        entityManager.persist(referral);
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject(
            "select \"remindersSent\" from \"deedpoll_referral\" where \"id\" = ?", Integer.class, referral.getId()))
            .isZero();

        jdbcTemplate.update("update \"deedpoll_referral\" set \"remindersSent\" = 2 where \"id\" = ?",
                            referral.getId());
        entityManager.clear();

        assertThat(entityManager.find(Referral.class, referral.getId()).getRemindersSent()).isEqualTo(2);
    }

    @Test
    @DisplayName("refuses a negative reminder count")
    void refusesANegativeCount() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(LocalDateTime.of(2026, 1, 2, 10, 0));
        entityManager.persist(deedPoll);
        Referral referral = new Referral();
        referral.setDeedPoll(deedPoll);
        referral.setState(ReferralState.Open);
        entityManager.persist(referral);
        entityManager.flush();

        assertThatThrownBy(() -> jdbcTemplate.update(
            "update \"deedpoll_referral\" set \"remindersSent\" = -1 where \"id\" = ?", referral.getId()))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("deedpoll_referral_remindersSent_check");
    }
}
