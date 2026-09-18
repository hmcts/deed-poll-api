package uk.gov.hmcts.reform.deedpoll.db;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code V7__backfill_referrals.sql} -- and {@code V10__backfill_current_referral_state.sql}
 * behind it -- actually does to real rows.
 *
 * <p>Nothing else can assert this. The migration runs during every context boot, but against an empty
 * case table, so it inserts nothing and a statement that selected the wrong cases -- or mapped the
 * wrong state -- would migrate cleanly on every build and be discovered once, in production, against
 * the only copy of the data it was written for. So this reads the committed file and runs it against
 * cases whose shapes are the ones the decision distinguishes.
 *
 * <p>The file is read rather than restated. A copy of the SQL here would be a copy that stops being
 * the migration the moment either changes, which is the failure this test exists to prevent.
 *
 * <p>Re-running it is safe and is the point: it is an {@code INSERT … SELECT} with no state of its own,
 * and the transaction rolls back, so the rows it writes here never reach another test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class ReferralBackfillTest extends AbstractPostgresContainerTest {

    private static final String REFERRALS = "db/migration/V7__backfill_referrals.sql";

    private static final String CURRENT_STATE = "db/migration/V10__backfill_current_referral_state.sql";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    /** The committed migration, as committed. */
    private static String backfillSql() {
        return sqlOf(REFERRALS);
    }

    private static String sqlOf(String migration) {
        try (var stream = new ClassPathResource(migration).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + migration, e);
        }
    }

    private DeedPoll persistedCase(LocalDateTime dateReferred, MasterDecision decision) {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(LocalDateTime.of(2026, 1, 2, 10, 0));
        deedPoll.setDateReferred(dateReferred);
        deedPoll.setMasterDecision(decision);
        entityManager.persist(deedPoll);
        return deedPoll;
    }

    /**
     * The referrals of one case, with the enum and {@code jsonb} columns cast to text.
     *
     * <p>The casts are for the driver, not for the assertions: pgjdbc hands back a {@code PGobject} for a
     * type it does not know, so {@code select *} would compare a wrapper against a string and fail for a
     * reason that has nothing to do with the migration.
     */
    private List<Map<String, Object>> referralsOf(String deedPollId) {
        return jdbcTemplate.queryForList("""
            select "state"::text        as "state",
                   "reasons"::text      as "reasons",
                   "notes",
                   "urgency"::text      as "urgency",
                   "urgencyReason",
                   "migrated",
                   "createdOn",
                   "modifiedOn",
                   "createdBy",
                   "modifiedBy"
            from "deedpoll_referral"
            where "deedPollId" = ?
            """, deedPollId);
    }

    @Test
    @DisplayName("a case whose Master decided gets one Closed referral")
    void decidedCaseGetsAClosedReferral() {
        DeedPoll decided = persistedCase(LocalDateTime.of(2026, 2, 1, 9, 0), MasterDecision.Approved);
        decided.setReferralNotes("Referred: applicant is a minor.");
        decided.setUrgency(Urgency.Urgent);
        decided.setUrgencyreason("Hearing listed.");
        entityManager.flush();

        jdbcTemplate.execute(backfillSql());

        assertThat(referralsOf(decided.getId())).singleElement().satisfies(row -> {
            // A decision came back, so the referral finished. Which decision it was stays in the case's
            // own columns -- inventing a direction here would be fabricating history.
            assertThat(row).containsEntry("state", "Closed");
            // The field did not exist in the power app, and the flag is what makes the empty array read as
            // history rather than as a bug.
            assertThat(row).containsEntry("reasons", "[]");
            assertThat(row).containsEntry("migrated", true);
            assertThat(row).containsEntry("notes", "Referred: applicant is a minor.");
            // Urgency and its reason travel together, so a backfilled urgent referral still says why --
            // which is the rule the domain enforces on every new one.
            assertThat(row).containsEntry("urgency", "Urgent");
            assertThat(row).containsEntry("urgencyReason", "Hearing listed.");
            assertThat(row).containsEntry("createdBy", CurrentUser.SERVICE_ACCOUNT_NAME);
            assertThat(row).containsEntry("modifiedBy", CurrentUser.SERVICE_ACCOUNT_NAME);
            // The date the case was referred, because that is when the referral came into being.
            assertThat(row.get("createdOn")).hasToString("2026-02-01 09:00:00.0");
            assertThat(row.get("modifiedOn")).as("now: when this row was written").isNotNull();
        });
    }

    @Test
    @DisplayName("a case referred and still awaiting a decision gets an Open referral, into the pool")
    void anAwaitingCaseGetsAnOpenReferral() {
        // The row this decision is really about. Closing it at migration would put a live backlog item
        // into a terminal state that nobody ever answers.
        DeedPoll awaiting = persistedCase(LocalDateTime.of(2026, 3, 1, 9, 0), null);
        entityManager.flush();

        jdbcTemplate.execute(backfillSql());

        assertThat(referralsOf(awaiting.getId())).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("state", "Open");
            assertThat(row).containsEntry("migrated", true);
            assertThat(row).containsEntry("notes", null);
            assertThat(row).containsEntry("urgency", "Normal");
        });
    }

    @Test
    @DisplayName("a case that was never referred gets no referral at all")
    void anUnreferredCaseGetsNoReferral() {
        DeedPoll neverReferred = persistedCase(null, null);
        // Referral notes with no referral date is not a referral: the note may be an aside about whether
        // to refer. The date is the signal, and this asserts a WHERE clause that keyed off the notes
        // instead would invent referrals.
        neverReferred.setReferralNotes("Discussed referring; decided not to.");
        entityManager.flush();

        jdbcTemplate.execute(backfillSql());

        assertThat(referralsOf(neverReferred.getId())).isEmpty();
    }

    @Test
    @DisplayName("a decision with no referral date still gets a Closed referral, dated now")
    void decisionWithNoReferralDateIsStillClosed() {
        // Real power-app data is not tidy: a decision recorded against a case whose date got lost is
        // still a referral that happened, and dropping it would lose it. COALESCE is what dates it.
        DeedPoll decidedWithoutDate = persistedCase(null, MasterDecision.Rejected);
        entityManager.flush();

        jdbcTemplate.execute(backfillSql());

        assertThat(referralsOf(decidedWithoutDate.getId())).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("state", "Closed");
            assertThat(row.get("createdOn")).isNotNull();
        });
    }

    /**
     * What the pair of backfills leaves on the case's own {@code currentReferralState} column.
     *
     * <p>Both files, in order, because the second is only meaningful after the first: V7 writes the
     * referrals and V10 projects them onto the case. Run together they are what a cutover environment
     * actually experiences, and the three shapes below are the three V7 distinguishes.
     */
    @Nested
    @DisplayName("the case's currentReferralState after both backfills")
    class CurrentReferralStateColumn {

        /** The column, cast to text for the same driver reason the referral query casts its enums. */
        private Object currentStateOf(String deedPollId) {
            return jdbcTemplate.queryForMap("""
                select "currentReferralState"::text as "currentReferralState"
                from "deedpoll_deedpoll"
                where "id" = ?
                """, deedPollId).get("currentReferralState");
        }

        /** Both files in version order, flushing first because raw SQL does not see the session. */
        private void runBothBackfills() {
            entityManager.flush();
            jdbcTemplate.execute(sqlOf(REFERRALS));
            jdbcTemplate.execute(sqlOf(CURRENT_STATE));
        }

        @Test
        @DisplayName("a decided legacy case is NULL, not Closed")
        void decidedLegacyCaseIsNull() {
            // The assertion V9's comment is about, and the one thing the backfill must NOT do: 'Closed' is
            // terminal, so the case has no referral in play. Writing 'Closed' here would make every migrated
            // case read as having a live-but-closed referral -- the one-referral-per-case snapshot model the
            // referral table replaced. Asserted rather than written: the subquery cannot produce a terminal
            // state, so this is NULL by construction and stays NULL if the statement is re-run.
            DeedPoll decided = persistedCase(LocalDateTime.of(2026, 2, 1, 9, 0), MasterDecision.Approved);

            runBothBackfills();

            assertThat(referralsOf(decided.getId())).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("state", "Closed"));
            assertThat(currentStateOf(decided.getId())).isNull();
        }

        @Test
        @DisplayName("a case that was never referred is NULL, having no referral at all")
        void anUnreferredCaseIsNull() {
            DeedPoll neverReferred = persistedCase(null, null);

            runBothBackfills();

            assertThat(referralsOf(neverReferred.getId())).isEmpty();
            assertThat(currentStateOf(neverReferred.getId())).isNull();
        }

        @Test
        @DisplayName("a legacy case still awaiting its decision is Open, because its referral is")
        void anAwaitingLegacyCaseIsOpen() {
            // The case the plan's decision 3 missed, and the reason V10 exists. V7's state rule has two arms
            // and only one is 'Closed': a case referred before cutover with no decision back gets a LIVE
            // 'Open' referral, so NULL would be the read model disagreeing with the referral table from the
            // first minute of the first environment -- and the case would be absent from every "which cases
            // have a live referral" query until a judge happened to claim it.
            DeedPoll awaiting = persistedCase(LocalDateTime.of(2026, 3, 1, 9, 0), null);

            runBothBackfills();

            assertThat(referralsOf(awaiting.getId())).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("state", "Open"));
            assertThat(currentStateOf(awaiting.getId())).isEqualTo("Open");
        }

        @Test
        @DisplayName("running the projection twice changes nothing, so a re-run is safe")
        void isIdempotent() {
            // It is an UPDATE from a correlated read with no state of its own. Pinned because a repair run
            // against a live environment is the likeliest way this file is ever executed again.
            // One list rather than two locals only because checkstyle's declaration-to-use distance counts
            // the statements between; the pair is the awaiting case and the decided one, in that order.
            List<String> ids = List.of(
                persistedCase(LocalDateTime.of(2026, 3, 1, 9, 0), null).getId(),
                persistedCase(LocalDateTime.of(2026, 2, 1, 9, 0), MasterDecision.Approved).getId());

            runBothBackfills();
            jdbcTemplate.execute(sqlOf(CURRENT_STATE));

            assertThat(currentStateOf(ids.get(0))).as("the awaiting case").isEqualTo("Open");
            assertThat(currentStateOf(ids.get(1))).as("the decided case").isNull();
        }
    }
}
