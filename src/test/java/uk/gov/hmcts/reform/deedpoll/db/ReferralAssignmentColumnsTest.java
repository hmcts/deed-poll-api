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
import uk.gov.hmcts.reform.deedpoll.entity.enums.WindowSlot;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code V43__referral_assignment.sql}'s four columns as {@link Referral} maps them, and the checks that hold them
 * together. {@code ddl-auto: validate} proves the names and types; only a round trip proves the slot is stored as
 * its name, and only a real table proves the checks refuse what they should and admit a claim made before V43.
 *
 * <p>Each refusal is the last statement of its test, because a refused statement aborts the test's transaction.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class ReferralAssignmentColumnsTest extends AbstractPostgresContainerTest {

    private static final LocalDateTime ASSIGNED_AT = LocalDateTime.of(2026, 10, 28, 10, 31);

    private static final LocalDate WINDOW_DATE = LocalDate.of(2026, 10, 28);

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID master() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            insert into deedpoll_master (id, name, email, created_at, created_by_id, created_by_name)
            values (?, 'Master Sullivan', ?, ?, 'subject-dm', 'Dee Manager')
            """, id, id + "@example.test", ASSIGNED_AT);
        return id;
    }

    private Referral referral(ReferralState state) {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(LocalDateTime.of(2026, 1, 2, 10, 0));
        entityManager.persist(deedPoll);
        Referral referral = new Referral();
        referral.setDeedPoll(deedPoll);
        referral.setState(state);
        return referral;
    }

    private Referral assigned(UUID masterId) {
        Referral referral = referral(ReferralState.Claimed);
        referral.setClaimedBy("Master Sullivan");
        referral.setClaimedAt(ASSIGNED_AT);
        referral.setAssignedMasterId(masterId);
        referral.setAssignedAt(ASSIGNED_AT);
        referral.setWindowDate(WINDOW_DATE);
        referral.setWindowSlot(WindowSlot.PM);
        return referral;
    }

    private void persistAndFlush(Referral referral) {
        entityManager.persist(referral);
        entityManager.flush();
    }

    /** Persisting through the entity manager is not translated to Spring's exceptions, so the name is the proof. */
    private void assertRefused(Referral referral, String constraint) {
        assertThatThrownBy(() -> persistAndFlush(referral)).hasMessageContaining(constraint);
    }

    @Test
    @DisplayName("round-trips an assignment, with the slot stored as its name")
    void roundTrips() {
        UUID masterId = master();
        Referral referral = assigned(masterId);
        persistAndFlush(referral);
        entityManager.clear();

        Referral read = entityManager.find(Referral.class, referral.getId());

        assertThat(read.getAssignedMasterId()).isEqualTo(masterId);
        assertThat(read.getAssignedAt()).isEqualTo(ASSIGNED_AT);
        assertThat(read.getWindowDate()).isEqualTo(WINDOW_DATE);
        assertThat(read.getWindowSlot()).isEqualTo(WindowSlot.PM);
        assertThat(jdbcTemplate.queryForObject(
            "select \"windowSlot\" from \"deedpoll_referral\" where \"id\" = ?", String.class, referral.getId()))
            .isEqualTo("PM");
    }

    @Test
    @DisplayName("admits a claim made before V43: a subject, no Master and no window")
    void admitsALegacyClaim() {
        Referral referral = referral(ReferralState.Claimed);
        referral.setClaimedBy("District Judge Bell");
        referral.setClaimedById("idam-district-judge-bell");
        referral.setClaimedAt(ASSIGNED_AT);
        persistAndFlush(referral);

        assertThat(referral.getId()).isNotNull();
    }

    @Test
    @DisplayName("admits a legacy claim linked to its Master: a Master and a subject, still no window")
    void admitsALinkedLegacyClaim() {
        Referral referral = referral(ReferralState.Claimed);
        referral.setClaimedById("idam-district-judge-bell");
        referral.setClaimedAt(ASSIGNED_AT);
        referral.setAssignedMasterId(master());
        persistAndFlush(referral);

        assertThat(referral.getId()).isNotNull();
    }

    @Test
    @DisplayName("refuses a Master who is not on the list")
    void refusesAnUnknownMaster() {
        Referral referral = assigned(UUID.randomUUID());

        assertRefused(referral, "deedpoll_referral_assignedMasterId_fkey");
    }

    @Test
    @DisplayName("refuses a window date without its slot")
    void refusesADateWithoutASlot() {
        Referral referral = assigned(master());
        referral.setWindowSlot(null);

        assertRefused(referral, "deedpoll_referral_window_check");
    }

    @Test
    @DisplayName("refuses a slot other than AM or PM")
    void refusesAnUnknownSlot() {
        Referral referral = assigned(master());
        persistAndFlush(referral);

        assertThatThrownBy(() -> jdbcTemplate.update(
            "update \"deedpoll_referral\" set \"windowSlot\" = 'XX' where \"id\" = ?", referral.getId()))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("deedpoll_referral_windowSlot_check");
    }

    @Test
    @DisplayName("refuses a window with no assignment time")
    void refusesAWindowWithoutAssignedAt() {
        Referral referral = assigned(master());
        referral.setAssignedAt(null);

        assertRefused(referral, "deedpoll_referral_window_assigned_check");
    }

    @Test
    @DisplayName("refuses an assignment that belongs to nobody")
    void refusesAnOwnerlessAssignment() {
        Referral referral = assigned(master());
        referral.setAssignedMasterId(null);

        assertRefused(referral, "deedpoll_referral_assigned_owner_check");
    }

    @Test
    @DisplayName("refuses an Open referral, waiting for assignment, that holds one")
    void refusesAnAssignedOpenReferral() {
        Referral referral = assigned(master());
        referral.setState(ReferralState.Open);

        assertRefused(referral, "deedpoll_referral_open_unassigned_check");
    }
}
