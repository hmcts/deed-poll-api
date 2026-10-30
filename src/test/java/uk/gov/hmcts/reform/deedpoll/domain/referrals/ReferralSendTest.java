package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollNote;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;
import uk.gov.hmcts.reform.deedpoll.entity.enums.WindowSlot;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollNoteRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * {@link ReferralQueue#send} against a real database (plan F1, step 6): the gate, the window, each refusal writing
 * nothing, the replay, the cap's boundary, urgent and withdrawn referrals counting (F-8, A-2), and what a send writes.
 * Concurrency is {@code ReferralSendConcurrencyTest}'s; the HTTP answers are
 * {@code CaseworkerReferralQueueControllerTest}'s.
 *
 * <p>The moments are UTC wall time, as {@code AuditClock} gives it; after 25 October 2026 that is London's too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReferralSendTest extends AbstractPostgresContainerTest {

    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 28);

    /** Wednesday morning's window, open. */
    private static final LocalDateTime MORNING = LocalDateTime.of(2026, 10, 28, 9, 0);

    /** Wednesday afternoon's window, open. */
    private static final LocalDateTime AFTERNOON = LocalDateTime.of(2026, 10, 28, 14, 15);

    private static final CurrentUser LEADER =
        CurrentUser.currentUser(List.of(Role.TEAM_LEADER), "Tia Leader", "leader-1");

    @Autowired
    private ReferralQueue queue;
    @Autowired
    private DeedPollRepository deedPolls;
    @Autowired
    private ReferralRepository referrals;
    @Autowired
    private DeedPollNoteRepository notes;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID sullivan;
    private UUID okafor;
    private int serial;

    @BeforeEach
    void setUp() {
        clearTables();
        sullivan = master("Master Sullivan", true, "sullivan-subject");
        okafor = master("Master Okafor", true, null);
    }

    @AfterEach
    void tearDown() {
        clearTables();
        jdbc.update("UPDATE deedpoll_process_settings SET window_cap = 10");
    }

    private void clearTables() {
        notes.deleteAll();
        referrals.deleteAll();
        deedPolls.deleteAll();
        jdbc.update("DELETE FROM deedpoll_master");
    }

    @Nested
    @DisplayName("the gate and the shape")
    class GateAndShape {

        @ParameterizedTest
        @ValueSource(strings = {"CASEWORKER", "JUDICIAL"})
        @DisplayName("refuses a caller without ALLOCATE_REFERRALS before reading the body, and writes nothing")
        void refuses(Role role) {
            Referral open = openReferral(Urgency.Normal);
            CurrentUser caller = CurrentUser.currentUser(List.of(role), "Ada", "subject-1");

            assertThatThrownBy(() -> queue.send(caller, Map.of("allocations", "nonsense")))
                .isInstanceOf(ForbiddenError.class);
            assertThatThrownBy(() -> queue.send(caller, body(open, sullivan))).isInstanceOf(ForbiddenError.class);
            assertUnchanged(open);
        }

        @ParameterizedTest
        @ValueSource(strings = {"TEAM_LEADER", "DELIVERY_MANAGER", "DEEDPOLL_ADMIN"})
        @DisplayName("past the gate, the body's shape is refused naming the entry")
        void shape(Role role) {
            Referral open = openReferral(Urgency.Normal);
            CurrentUser caller = CurrentUser.currentUser(List.of(role), "Tia", "subject-2");
            Map<String, Object> twice = Map.of("allocations", List.of(
                Map.of("referralId", open.getId().toString(), "masterId", sullivan.toString()),
                Map.of("referralId", open.getId().toString(), "masterId", okafor.toString())));

            assertThatThrownBy(() -> queue.send(caller, twice))
                .isInstanceOfSatisfying(InvalidInputError.class, error -> assertThat(error.errors()).containsExactly(
                    new FieldError("allocations[1].referralId", ReferralSchemas.SENT_TWICE)));
            assertUnchanged(open);
        }
    }

    @Nested
    @DisplayName("a send")
    class Sending {

        @Test
        @DisplayName("sends each referral to its Master in the open window: columns, a note and an audit event each, "
            + "and the case's read-model")
        void sends() {
            Referral first = openReferral(Urgency.Normal);
            Referral second = openReferral(Urgency.Urgent);
            Referral third = openReferral(Urgency.Normal);
            jdbc.update("UPDATE \"deedpoll_referral\" SET \"remindersSent\" = 3 WHERE \"id\" = ?", third.getId());

            ReferralSend send = send(List.of(
                new ReferralAllocation(first.getId(), sullivan),
                new ReferralAllocation(second.getId(), okafor),
                new ReferralAllocation(third.getId(), sullivan)), MORNING);

            assertThat(send.referrals()).extracting(Referral::getId)
                .containsExactly(first.getId(), second.getId(), third.getId());
            Referral sent = stored(first);
            assertThat(sent.getState()).isEqualTo(ReferralState.Claimed);
            assertThat(sent.getClaimedBy()).isEqualTo("Master Sullivan");
            assertThat(sent.getClaimedById()).isEqualTo("sullivan-subject");
            assertThat(sent.getClaimedAt()).isEqualTo(MORNING);
            assertThat(sent.getAssignedAt()).isEqualTo(MORNING);
            assertThat(sent.getLastTouchedAt()).isEqualTo(MORNING);
            assertThat(sent.getAssignedMasterId()).isEqualTo(sullivan);
            assertThat(sent.getWindowDate()).isEqualTo(WEDNESDAY);
            assertThat(sent.getWindowSlot()).isEqualTo(WindowSlot.AM);
            assertThat(sent.getModifiedBy()).isEqualTo("Tia Leader");
            // An unlinked Master: nobody can see it until they sign in.
            assertThat(stored(second).getClaimedBy()).isEqualTo("Master Okafor");
            assertThat(stored(second).getClaimedById()).isNull();
            assertThat(stored(third).getRemindersSent()).isZero();

            assertThat(noteTexts(first)).singleElement().asString()
                .startsWith("Referral raised on ").endsWith(", sent to Master Sullivan by the office.");
            assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(caseOf(first))).singleElement()
                .extracting(DeedPollNote::getCreatedBy).isEqualTo("Tia Leader");
            assertThat(noteTexts(second)).singleElement().asString()
                .endsWith(", sent to Master Okafor by the office.");
            for (Referral referral : List.of(first, second, third)) {
                assertThat(sentEvents(referral)).isOne();
                assertThat(deedPolls.findById(caseOf(referral)).orElseThrow().getCurrentReferralState())
                    .isEqualTo(ReferralState.Claimed);
            }
            assertThat(jdbc.queryForObject("""
                SELECT fields FROM deedpoll_audit_event WHERE action = 'REFERRAL_SENT' AND entity_id = ?
                """, String.class, first.getId().toString())).isEqualTo("{state,claimedBy,assignedAt}");
            assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM deedpoll_audit_event_value v JOIN deedpoll_audit_event e ON e.id = v.event_id
                 WHERE e.action = 'REFERRAL_SENT' AND e.entity_id = ? AND v.new_value LIKE '%subject%'
                """, Integer.class, first.getId().toString())).isZero();

            assertThat(send.window().current()).isEqualTo(new ReferralWindow(WEDNESDAY, WindowSlot.AM));
            assertThat(send.window().sent()).isEqualTo(3);
            assertThat(send.window().masters())
                .extracting(ReferralQueueWindow.SentToMaster::name, ReferralQueueWindow.SentToMaster::sentInWindow)
                .containsExactly(tuple("Master Okafor", 1L),
                                 tuple("Master Sullivan", 2L));
        }

        @Test
        @DisplayName("in the afternoon, it counts towards the afternoon")
        void afternoon() {
            Referral open = openReferral(Urgency.Normal);

            send(List.of(new ReferralAllocation(open.getId(), sullivan)), AFTERNOON);

            assertThat(stored(open).getWindowSlot()).isEqualTo(WindowSlot.PM);
        }

        @Test
        @DisplayName("the same send again is a replay: the same answer, nothing new written")
        void replay() {
            Referral first = openReferral(Urgency.Normal);
            Referral second = openReferral(Urgency.Normal);
            List<ReferralAllocation> allocations = List.of(new ReferralAllocation(first.getId(), sullivan),
                                                           new ReferralAllocation(second.getId(), okafor));
            send(allocations, MORNING);

            ReferralSend again = send(allocations, MORNING.plusMinutes(5));

            assertThat(again.referrals()).extracting(Referral::getId).containsExactly(first.getId(), second.getId());
            assertThat(stored(first).getAssignedAt()).isEqualTo(MORNING);
            assertThat(noteTexts(first)).hasSize(1);
            assertThat(sentEvents(first)).isOne();
            assertThat(again.window().sent()).isEqualTo(2);
        }

        @Test
        @DisplayName("a replay beside a new referral sends only the new one")
        void replayBesideNew() {
            Referral first = openReferral(Urgency.Normal);
            Referral second = openReferral(Urgency.Normal);
            send(List.of(new ReferralAllocation(first.getId(), sullivan)), MORNING);

            send(List.of(new ReferralAllocation(first.getId(), sullivan),
                                       new ReferralAllocation(second.getId(), okafor)), MORNING.plusMinutes(5));

            assertThat(sentEvents(first)).isOne();
            assertThat(stored(second).getState()).isEqualTo(ReferralState.Claimed);
            assertThat(stored(second).getAssignedAt()).isEqualTo(MORNING.plusMinutes(5));
        }
    }

    @Nested
    @DisplayName("the refusals, each writing nothing")
    class Refusals {

        @ParameterizedTest
        @ValueSource(strings = {"2026-10-28T06:59", "2026-10-28T10:30", "2026-10-28T12:00", "2026-10-28T14:30",
            "2026-10-31T09:00", "2026-12-25T09:00"})
        @DisplayName("outside a window: between, before, after, a weekend and a bank holiday")
        void outsideAWindow(String at) {
            Referral open = openReferral(Urgency.Normal);

            assertThatThrownBy(() -> send(List.of(new ReferralAllocation(open.getId(), sullivan)),
                                                LocalDateTime.parse(at)))
                .isInstanceOfSatisfying(ConflictError.class, error -> assertThat(error.getMessage())
                    .startsWith("Referrals can only be sent during a referral window. The next one opens at "));
            assertUnchanged(open);
        }

        @Test
        @DisplayName("outside a window, the refusal says when the next opens")
        void nextInWords() {
            Referral open = openReferral(Urgency.Normal);
            List<ReferralAllocation> one = List.of(new ReferralAllocation(open.getId(), sullivan));

            assertThatThrownBy(() -> send(one, LocalDateTime.of(2026, 10, 28, 12, 0)))
                .hasMessage("Referrals can only be sent during a referral window. The next one opens at 14:00 today.");
            assertThatThrownBy(() -> send(one, LocalDateTime.of(2026, 10, 28, 16, 0)))
                .hasMessage("Referrals can only be sent during a referral window. The next one opens at 07:00 "
                    + "tomorrow.");
            assertThatThrownBy(() -> send(one, LocalDateTime.of(2026, 10, 30, 16, 0)))
                .hasMessage("Referrals can only be sent during a referral window. The next one opens at 07:00 on "
                    + "Monday 2 November 2026.");
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Decided", "Withdrawn", "Closed"})
        @DisplayName("a referral that is not waiting is a 409 on its entry")
        void notWaiting(ReferralState state) {
            Referral open = openReferral(Urgency.Normal);
            Referral gone = sentReferral(state, sullivan, WEDNESDAY, WindowSlot.AM);

            assertThatThrownBy(() -> send(List.of(new ReferralAllocation(open.getId(), sullivan),
                                                                new ReferralAllocation(gone.getId(), sullivan)),
                                                MORNING))
                .isInstanceOfSatisfying(ConflictError.class, error -> assertThat(error.errors()).containsExactly(
                    new FieldError("allocations[1].referralId", ReferralQueue.NO_LONGER_WAITING)));
            assertUnchanged(open);
        }

        @Test
        @DisplayName("sent to another Master, or in another window, or unknown, is not a replay but a 409")
        void notAReplay() {
            Referral open = openReferral(Urgency.Normal);
            Referral elsewhere = sentReferral(ReferralState.Claimed, okafor, WEDNESDAY, WindowSlot.AM);
            Referral earlier = sentReferral(ReferralState.Claimed, sullivan, WEDNESDAY.minusDays(1), WindowSlot.AM);
            Referral afternoon = sentReferral(ReferralState.Claimed, sullivan, WEDNESDAY, WindowSlot.PM);

            assertThatThrownBy(() -> send(List.of(
                new ReferralAllocation(open.getId(), sullivan),
                new ReferralAllocation(elsewhere.getId(), sullivan),
                new ReferralAllocation(earlier.getId(), sullivan),
                new ReferralAllocation(afternoon.getId(), sullivan),
                new ReferralAllocation(UUID.randomUUID(), sullivan)), MORNING))
                .isInstanceOfSatisfying(ConflictError.class, error -> assertThat(error.errors())
                    .extracting(FieldError::field)
                    .containsExactly("allocations[1].referralId", "allocations[2].referralId",
                                     "allocations[3].referralId", "allocations[4].referralId"));
            assertUnchanged(open);
        }

        @Test
        @DisplayName("an unavailable or unknown Master is a 400 on its entry")
        void unavailableMaster() {
            UUID away = master("Master Away", false, null);
            Referral first = openReferral(Urgency.Normal);
            Referral second = openReferral(Urgency.Normal);
            Referral third = openReferral(Urgency.Normal);

            assertThatThrownBy(() -> send(List.of(
                new ReferralAllocation(first.getId(), sullivan),
                new ReferralAllocation(second.getId(), away),
                new ReferralAllocation(third.getId(), UUID.randomUUID())), MORNING))
                .isInstanceOfSatisfying(InvalidInputError.class, error -> assertThat(error.errors()).containsExactly(
                    new FieldError("allocations[1].masterId", ReferralSchemas.SELECT_A_MASTER),
                    new FieldError("allocations[2].masterId", ReferralSchemas.SELECT_A_MASTER)));
            assertUnchanged(first);
            assertUnchanged(second);
        }
    }

    @Nested
    @DisplayName("the cap, across every Master")
    class Cap {

        @Test
        @DisplayName("exactly at the cap succeeds; one past it is refused as a whole, saying how many fit")
        void boundary() {
            jdbc.update("UPDATE deedpoll_process_settings SET window_cap = 3");
            sentReferral(ReferralState.Claimed, okafor, WEDNESDAY, WindowSlot.AM);
            List<Referral> three = List.of(openReferral(Urgency.Normal), openReferral(Urgency.Normal),
                                           openReferral(Urgency.Normal));

            assertThatThrownBy(() -> send(allocations(three, sullivan), MORNING))
                .isInstanceOfSatisfying(ConflictError.class, error -> {
                    assertThat(error.getMessage())
                        .isEqualTo("Only 2 more referrals can be sent this morning. Remove 1 and try again.");
                    assertThat(error.errors()).isEmpty();
                });
            three.forEach(ReferralSendTest.this::assertUnchanged);

            send(allocations(three.subList(0, 2), sullivan), MORNING);
            assertThat(stored(three.get(1)).getState()).isEqualTo(ReferralState.Claimed);
        }

        @Test
        @DisplayName("urgent referrals count, and so does one sent and since withdrawn or directed (A-2)")
        void everythingSentCounts() {
            jdbc.update("UPDATE deedpoll_process_settings SET window_cap = 3");
            sentReferral(ReferralState.Withdrawn, okafor, WEDNESDAY, WindowSlot.AM);
            sentReferral(ReferralState.Decided, okafor, WEDNESDAY, WindowSlot.AM);
            // Another window's referrals do not count towards this one.
            sentReferral(ReferralState.Claimed, okafor, WEDNESDAY, WindowSlot.PM);
            Referral urgent = openReferral(Urgency.Urgent);
            Referral normal = openReferral(Urgency.Normal);

            assertThatThrownBy(() -> send(allocations(List.of(urgent, normal), sullivan), MORNING))
                .hasMessage("Only 1 more referral can be sent this morning. Remove 1 and try again.");
            send(allocations(List.of(urgent), sullivan), MORNING);

            assertThatThrownBy(() -> send(allocations(List.of(normal), sullivan), MORNING))
                .hasMessage("No more referrals can be sent this morning.");
            assertUnchanged(normal);
        }

        @Test
        @DisplayName("the afternoon's refusal says so, in the plural")
        void afternoonWords() {
            assertThat(ReferralQueue.full(new ReferralWindow(WEDNESDAY, WindowSlot.PM), 3, 5))
                .isEqualTo("Only 3 more referrals can be sent this afternoon. Remove 2 and try again.");
            assertThat(ReferralQueue.full(new ReferralWindow(WEDNESDAY, WindowSlot.PM), 0, 5))
                .isEqualTo("No more referrals can be sent this afternoon.");
        }

        @Test
        @DisplayName("a replay alone is not refused when the window is full")
        void replayAtTheCap() {
            jdbc.update("UPDATE deedpoll_process_settings SET window_cap = 1");
            Referral open = openReferral(Urgency.Normal);
            List<ReferralAllocation> one = List.of(new ReferralAllocation(open.getId(), sullivan));
            send(one, MORNING);

            assertThat(send(one, MORNING).referrals()).hasSize(1);
        }
    }

    @Test
    @DisplayName("the closed-window words for a moment at which the next window is today")
    void closedWords() {
        assertThat(ReferralQueue.closed(Instant.parse("2026-10-28T12:00:00Z"),
                                        new ReferralWindow(WEDNESDAY, WindowSlot.PM),
                                        Instant.parse("2026-10-28T14:00:00Z")))
            .isEqualTo("Referrals can only be sent during a referral window. The next one opens at 14:00 today.");
    }

    /** The package-private send at a moment, in its own transaction, as the public one runs. */
    private ReferralSend send(List<ReferralAllocation> allocations, LocalDateTime at) {
        return new TransactionTemplate(transactionManager).execute(status -> queue.send(LEADER, allocations, at));
    }

    private List<ReferralAllocation> allocations(List<Referral> referrals, UUID master) {
        List<ReferralAllocation> allocations = new ArrayList<>();
        referrals.forEach(referral -> allocations.add(new ReferralAllocation(referral.getId(), master)));
        return allocations;
    }

    private static Map<String, Object> body(Referral referral, UUID master) {
        return Map.of("allocations", List.of(Map.of("referralId", referral.getId().toString(),
                                                     "masterId", master.toString())));
    }

    private void assertUnchanged(Referral open) {
        Referral now = stored(open);
        assertThat(now.getState()).isEqualTo(ReferralState.Open);
        assertThat(now.getAssignedMasterId()).isNull();
        assertThat(now.getWindowDate()).isNull();
        assertThat(noteTexts(open)).isEmpty();
        assertThat(sentEvents(open)).isZero();
    }

    private Referral stored(Referral referral) {
        return referrals.findById(referral.getId()).orElseThrow();
    }

    private String caseOf(Referral referral) {
        return jdbc.queryForObject("SELECT \"deedPollId\" FROM \"deedpoll_referral\" WHERE \"id\" = ?",
                                   String.class, referral.getId());
    }

    private List<String> noteTexts(Referral referral) {
        return notes.findByDeedPollIdOrderByCreatedOnDesc(caseOf(referral)).stream().map(DeedPollNote::getNote)
            .toList();
    }

    private int sentEvents(Referral referral) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM deedpoll_audit_event WHERE action = 'REFERRAL_SENT' AND entity_id = ?",
            Integer.class, referral.getId().toString());
    }

    private DeedPoll storedCase() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(LocalDateTime.of(2026, 1, 2, 10, 0));
        deedPoll.setDisplayReference("DP-2026-" + (++serial));
        deedPoll.setNewForename("Ada");
        deedPoll.setNewSurname("Newname");
        return deedPolls.save(deedPoll);
    }

    private Referral openReferral(Urgency urgency) {
        return referrals.save(referral(ReferralState.Open, urgency));
    }

    private Referral sentReferral(ReferralState state, UUID master, LocalDate windowDate, WindowSlot windowSlot) {
        Referral referral = referral(state, Urgency.Normal);
        referral.setClaimedBy("A Master");
        referral.setClaimedAt(MORNING.minusDays(1));
        referral.setAssignedMasterId(master);
        referral.setAssignedAt(MORNING.minusDays(1));
        referral.setWindowDate(windowDate);
        referral.setWindowSlot(windowSlot);
        return referrals.save(referral);
    }

    private Referral referral(ReferralState state, Urgency urgency) {
        Referral referral = new Referral();
        referral.setDeedPoll(storedCase());
        referral.setState(state);
        referral.setReasons(List.of(ReferralReason.DomesticAbuse.name()));
        referral.setUrgency(urgency);
        referral.setUrgencyReason(urgency == Urgency.Urgent ? "Hearing on Friday" : null);
        referral.setCreatedBy("Ada Caseworker");
        return referral;
    }

    private UUID master(String name, boolean available, String subject) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO deedpoll_master (id, name, email, available, idam_subject, linked_at, created_at,
                created_by_id, created_by_name)
            VALUES (?, ?, ?, ?, ?, CASE WHEN ? THEN now() END, now(), 'subject-dm', 'Dee Manager')
            """, id, name, id + "@example.test", available, subject, subject != null);
        return id;
    }
}
