package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ReferralWindowTimes;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * {@link ReferralQueue} against a real database (plan F1): the capability gate, both orders over an every-combination
 * fixture, paging, and the window envelope at moments inside a window, between windows, on a weekend and on a bank
 * holiday (V42's seeded England and Wales dates). The HTTP answers are {@code CaseworkerReferralQueueControllerTest}'s.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReferralQueueTest extends AbstractPostgresContainerTest {

    private static final LocalDateTime T1 = LocalDateTime.of(2026, 10, 20, 9, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 10, 21, 9, 0);
    private static final LocalDateTime T3 = LocalDateTime.of(2026, 10, 22, 9, 0);

    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 28);

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

    private int serial;

    @BeforeEach
    void setUp() {
        clearTables();
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
        jdbc.update("DELETE FROM deedpoll_master_email");
        jdbc.update("DELETE FROM deedpoll_master");
    }

    @Nested
    @DisplayName("the gate")
    class Gate {

        @ParameterizedTest
        @ValueSource(strings = {"CASEWORKER", "JUDICIAL"})
        @DisplayName("refuses a caller without ALLOCATE_REFERRALS before reading its parameters")
        void refuses(Role role) {
            CurrentUser caller = CurrentUser.currentUser(List.of(role), "Ada", "subject-1");

            assertThatThrownBy(() -> queue.list(caller, "Decided", "nope", "0")).isInstanceOf(ForbiddenError.class);
            assertThatThrownBy(() -> queue.list(caller, null, null, null)).isInstanceOf(ForbiddenError.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"TEAM_LEADER", "DELIVERY_MANAGER", "DEEDPOLL_ADMIN"})
        @DisplayName("admits a Team leader, a Delivery Manager and the legacy admin")
        void admits(Role role) {
            openReferral(Urgency.Normal, T1);
            CurrentUser caller = CurrentUser.currentUser(List.of(role), "Tia", "subject-2");

            assertThat(queue.list(caller, null, null, null).total()).isOne();
        }

        @Test
        @DisplayName("reads state as Open by default, Claimed exactly, and refuses anything else on state")
        void state() {
            assertThat(ReferralQueue.parseState(null)).isEqualTo(ReferralState.Open);
            assertThat(ReferralQueue.parseState(" ")).isEqualTo(ReferralState.Open);
            assertThat(ReferralQueue.parseState("Open")).isEqualTo(ReferralState.Open);
            assertThat(ReferralQueue.parseState("Claimed")).isEqualTo(ReferralState.Claimed);
            for (String refused : List.of("claimed", "Decided", "Withdrawn", "Closed", "nope")) {
                assertThatThrownBy(() -> ReferralQueue.parseState(refused))
                    .isInstanceOfSatisfying(InvalidInputError.class, error -> assertThat(error.errors())
                        .containsExactly(new FieldError("state", "state is Open or Claimed")));
            }
        }
    }

    @Nested
    @DisplayName("the order")
    class Order {

        @Test
        @DisplayName("Open: urgent first, then the oldest raised, then id; no other state appears")
        void open() {
            final String normalLate = openReferral(Urgency.Normal, T3);
            final String urgentLate = openReferral(Urgency.Urgent, T2);
            final String normalEarlyA = openReferral(Urgency.Normal, T1);
            final String normalEarlyB = openReferral(Urgency.Normal, T1);
            final String urgentEarly = openReferral(Urgency.Urgent, T1);
            UUID master = master("Master Sullivan", true);
            sentReferral(ReferralState.Claimed, Urgency.Urgent, master, T1, WEDNESDAY, WindowSlot.AM);
            sentReferral(ReferralState.Withdrawn, Urgency.Urgent, master, T1, WEDNESDAY, WindowSlot.AM);
            sentReferral(ReferralState.Decided, Urgency.Urgent, master, T1, WEDNESDAY, WindowSlot.AM);
            sentReferral(ReferralState.Closed, Urgency.Urgent, master, T1, WEDNESDAY, WindowSlot.AM);

            List<String> tied = sortedIds(normalEarlyA, normalEarlyB);
            assertThat(ids(queue.list(ReferralState.Open, CasePaging.FIRST, at("2026-10-28T09:00"))))
                .containsExactly(urgentEarly, urgentLate, tied.get(0), tied.get(1), normalLate);
        }

        @Test
        @DisplayName("Claimed: the oldest sent first whatever the urgency, a pre-V43 claim by its claimedAt, then id")
        void claimed() {
            UUID master = master("Master Sullivan", true);
            String late = sentReferral(ReferralState.Claimed, Urgency.Urgent, master, T3, WEDNESDAY, WindowSlot.AM);
            String earlyA = sentReferral(ReferralState.Claimed, Urgency.Normal, master, T1, WEDNESDAY, WindowSlot.AM);
            String earlyB = sentReferral(ReferralState.Claimed, Urgency.Normal, master, T1, WEDNESDAY, WindowSlot.AM);
            String legacy = legacyClaim(T2);
            openReferral(Urgency.Urgent, T1);
            sentReferral(ReferralState.Decided, Urgency.Normal, master, T1, WEDNESDAY, WindowSlot.AM);

            List<String> tied = sortedIds(earlyA, earlyB);
            assertThat(ids(queue.list(ReferralState.Claimed, CasePaging.FIRST, at("2026-10-28T09:00"))))
                .containsExactly(tied.get(0), tied.get(1), legacy, late);
        }

        @Test
        @DisplayName("pages are stable: three pages of two are the whole order once, with the total, and past the end "
            + "is empty")
        void paging() {
            for (int i = 0; i < 5; i++) {
                openReferral(i % 2 == 0 ? Urgency.Urgent : Urgency.Normal, T1);
            }
            List<String> whole = ids(queue.list(ReferralState.Open, CasePaging.FIRST, at("2026-10-28T09:00")));

            List<String> paged = new ArrayList<>();
            for (int page = 1; page <= 3; page++) {
                ReferralQueuePage one = queue.list(ReferralState.Open, new CasePaging(page, 2), at("2026-10-28T09:00"));
                assertThat(one.total()).isEqualTo(5);
                assertThat(one.totalPages()).isEqualTo(3);
                assertThat(one.page()).isEqualTo(page);
                assertThat(one.size()).isEqualTo(2);
                paged.addAll(ids(one));
            }
            assertThat(paged).containsExactlyElementsOf(whole).doesNotHaveDuplicates().hasSize(5);
            assertThat(queue.list(ReferralState.Open, new CasePaging(4, 2), at("2026-10-28T09:00")).referrals())
                .isEmpty();
        }

        @Test
        @DisplayName("each row carries its case, loaded")
        void caseLoaded() {
            String id = openReferral(Urgency.Normal, T1);

            Referral row = queue.list(ReferralState.Open, CasePaging.FIRST, at("2026-10-28T09:00")).referrals().get(0);
            assertThat(row.getId().toString()).isEqualTo(id);
            assertThat(row.getDeedPoll().getNewSurname()).isEqualTo("Newname");
            assertThat(row.getDeedPoll().getDisplayReference()).startsWith("DP-");
        }
    }

    @Nested
    @DisplayName("the window envelope")
    class Envelope {

        private UUID adams;
        private UUID okafor;
        private UUID sullivan;

        @BeforeEach
        void fixture() {
            adams = master("Master Adams", true);
            okafor = master("Master Okafor", true);
            sullivan = master("Master Sullivan", true);
            final UUID away = master("Master Away", false);
            // Wednesday morning: two to Sullivan (one since withdrawn), one to Okafor (since directed), one to a
            // Master since made unavailable. Wednesday afternoon and Tuesday morning count towards neither.
            sentReferral(ReferralState.Claimed, Urgency.Normal, sullivan, T1, WEDNESDAY, WindowSlot.AM);
            sentReferral(ReferralState.Withdrawn, Urgency.Normal, sullivan, T1, WEDNESDAY, WindowSlot.AM);
            sentReferral(ReferralState.Decided, Urgency.Normal, okafor, T1, WEDNESDAY, WindowSlot.AM);
            sentReferral(ReferralState.Claimed, Urgency.Normal, away, T1, WEDNESDAY, WindowSlot.AM);
            sentReferral(ReferralState.Claimed, Urgency.Normal, okafor, T1, WEDNESDAY, WindowSlot.PM);
            sentReferral(ReferralState.Claimed, Urgency.Normal, adams, T1, WEDNESDAY.minusDays(1), WindowSlot.AM);
        }

        @Test
        @DisplayName("inside a window: it is current with its close, the next is its successor, and the counts are "
            + "its, in any state but withdrawn (A-2), with each available Master's share by name")
        void insideAWindow() {
            ReferralQueueWindow window = windowAt("2026-10-28T09:00");

            assertThat(window.current()).isEqualTo(new ReferralWindow(WEDNESDAY, WindowSlot.AM));
            assertThat(window.closesAt()).isEqualTo(Instant.parse("2026-10-28T10:30:00Z"));
            assertThat(window.next()).isEqualTo(new ReferralWindow(WEDNESDAY, WindowSlot.PM));
            assertThat(window.opensAt()).isEqualTo(Instant.parse("2026-10-28T14:00:00Z"));
            assertThat(window.cap()).isEqualTo(10);
            // Sullivan's withdrawn referral gives its place back, so the window holds three, Sullivan's share one.
            assertThat(window.sent()).isEqualTo(3);
            assertThat(window.masters())
                .extracting(ReferralQueueWindow.SentToMaster::id, ReferralQueueWindow.SentToMaster::name,
                            ReferralQueueWindow.SentToMaster::sentInWindow)
                .containsExactly(tuple(adams, "Master Adams", 0L), tuple(okafor, "Master Okafor", 1L),
                                 tuple(sullivan, "Master Sullivan", 1L));
        }

        @Test
        @DisplayName("the afternoon counts its own referrals only")
        void afternoon() {
            ReferralQueueWindow window = windowAt("2026-10-28T14:15");

            assertThat(window.current()).isEqualTo(new ReferralWindow(WEDNESDAY, WindowSlot.PM));
            assertThat(window.next()).isEqualTo(new ReferralWindow(WEDNESDAY.plusDays(1), WindowSlot.AM));
            assertThat(window.sent()).isOne();
            assertThat(window.masters()).extracting(ReferralQueueWindow.SentToMaster::sentInWindow)
                .containsExactly(0L, 1L, 0L);
        }

        @Test
        @DisplayName("between windows: none is current, nothing is counted, and the next is the afternoon")
        void betweenWindows() {
            ReferralQueueWindow window = windowAt("2026-10-28T12:00");

            assertThat(window.current()).isNull();
            assertThat(window.closesAt()).isNull();
            assertThat(window.next()).isEqualTo(new ReferralWindow(WEDNESDAY, WindowSlot.PM));
            assertThat(window.sent()).isZero();
            assertThat(window.masters()).extracting(ReferralQueueWindow.SentToMaster::sentInWindow)
                .containsOnly(0L).hasSize(3);
        }

        @Test
        @DisplayName("on a weekend: none is current, and the next is Monday's morning, after the clocks went back")
        void weekend() {
            ReferralQueueWindow window = windowAt("2026-10-31T09:00");

            assertThat(window.current()).isNull();
            assertThat(window.next()).isEqualTo(new ReferralWindow(LocalDate.of(2026, 11, 2), WindowSlot.AM));
            assertThat(window.opensAt()).isEqualTo(Instant.parse("2026-11-02T07:00:00Z"));
        }

        @Test
        @DisplayName("on a bank holiday: none is current at a window's time, and Christmas goes to the 29th")
        void bankHoliday() {
            ReferralQueueWindow window = windowAt("2026-12-25T09:00");

            assertThat(window.current()).isNull();
            assertThat(window.next()).isEqualTo(new ReferralWindow(LocalDate.of(2026, 12, 29), WindowSlot.AM));
        }

        @Test
        @DisplayName("the cap is the setting as it is now")
        void cap() {
            jdbc.update("UPDATE deedpoll_process_settings SET window_cap = 7");

            assertThat(windowAt("2026-10-28T09:00").cap()).isEqualTo(7);
        }

        private ReferralQueueWindow windowAt(String london) {
            return queue.list(ReferralState.Open, CasePaging.FIRST, at(london)).window();
        }
    }

    private static Instant at(String london) {
        return LocalDateTime.parse(london).atZone(ReferralWindowTimes.ZONE).toInstant();
    }

    private static List<String> ids(ReferralQueuePage page) {
        return page.referrals().stream().map(referral -> referral.getId().toString()).toList();
    }

    private static List<String> sortedIds(String... ids) {
        // As PostgreSQL orders a uuid, byte by byte unsigned, which is its lower-case text's order and not
        // UUID.compareTo's signed one.
        return Arrays.stream(ids).sorted().toList();
    }

    private DeedPoll storedCase() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(LocalDateTime.of(2026, 1, 2, 10, 0));
        deedPoll.setDisplayReference("DP-2026-" + (++serial));
        deedPoll.setNewForename("Ada");
        deedPoll.setNewSurname("Newname");
        deedPoll.setOldForename("Ada");
        deedPoll.setOldSurname("Oldname");
        return deedPolls.save(deedPoll);
    }

    private String openReferral(Urgency urgency, LocalDateTime createdOn) {
        Referral referral = referral(ReferralState.Open, urgency, createdOn);
        return referrals.save(referral).getId().toString();
    }

    private String sentReferral(ReferralState state, Urgency urgency, UUID master, LocalDateTime assignedAt,
                                LocalDate windowDate, WindowSlot windowSlot) {
        Referral referral = referral(state, urgency, T1);
        referral.setClaimedBy("A Master");
        referral.setClaimedAt(assignedAt);
        referral.setAssignedMasterId(master);
        referral.setAssignedAt(assignedAt);
        referral.setWindowDate(windowDate);
        referral.setWindowSlot(windowSlot);
        return referrals.save(referral).getId().toString();
    }

    /** A claim made before V43: a subject, no Master, no assignedAt and no window. */
    private String legacyClaim(LocalDateTime claimedAt) {
        Referral referral = referral(ReferralState.Claimed, Urgency.Normal, T1);
        referral.setClaimedBy("District Judge Bell");
        referral.setClaimedById("judge-subject");
        referral.setClaimedAt(claimedAt);
        return referrals.save(referral).getId().toString();
    }

    private Referral referral(ReferralState state, Urgency urgency, LocalDateTime createdOn) {
        Referral referral = new Referral();
        referral.setDeedPoll(storedCase());
        referral.setState(state);
        referral.setReasons(List.of(ReferralReason.DomesticAbuse.name()));
        referral.setUrgency(urgency);
        referral.setUrgencyReason(urgency == Urgency.Urgent ? "Hearing on Friday" : null);
        referral.setCreatedBy("Ada Caseworker");
        referral.setCreatedOn(createdOn);
        return referral;
    }

    private UUID master(String name, boolean available) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO deedpoll_master (id, name, email, available, created_at, created_by_id, created_by_name)
            VALUES (?, ?, ?, ?, now(), 'subject-dm', 'Dee Manager')
            """, id, name, id + "@example.test", available);
        return id;
    }
}
