package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.notes.CreateNoteInput;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteAuthor;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteForm;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;
import uk.gov.hmcts.reform.deedpoll.repository.StaleClaim;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The whole referral state machine -- raising, reading, withdrawing, claiming, directing, closing and the
 * inactivity sweep -- with no database and no HTTP.
 *
 * <p><strong>Mocked repositories, following {@code SavedQuerySelectionTest} rather than
 * {@code NoteServiceTest}.</strong> The container-backed service tests exist because their services are
 * ports whose reference asserts the arguments a mocked Prisma client was called with, and container-backed
 * classes are excluded on the Jenkins agent -- {@code INTEGRATION_TESTS_ENABLED=false}, because it has no
 * Docker. Referrals have no reference to port, and the rules that matter here are about
 * <em>whether a row is written at all</em> and <em>what is written alongside it</em>, which is exactly what a
 * verified interaction states and what a stored row cannot: "the second referral was refused before anything
 * was saved" is invisible in a table that already holds the first. So the rules live where CI runs them, and
 * {@code CaseworkerReferralControllerTest} proves against a real database and a real token that the routes
 * reach them and which status code each answer becomes.
 *
 * <p>The note service is mocked for the same reason: the claim is that every successful transition writes one
 * note attributed to the actor that caused it -- the caseworker who raised or withdrew, the judge who claimed
 * or directed, the service account for a lapse -- and the author is an argument rather than something the
 * stored note distinguishes from a service-account write except by its text.
 */
class ReferralServiceTest {

    private static final String CASE_ID = "case-1";
    private static final String OTHER_CASE_ID = "case-2";

    private static final UUID REFERRAL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    /** An id of the right shape that nothing was ever stored under. */
    private static final UUID UNKNOWN_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    /** An ordinary caseworker: authenticated and named, holding no role this service asks about. */
    private static final CurrentUser CASEWORKER =
        CurrentUser.currentUser(List.of(), "Ada Caseworker", "caseworker-1");

    private static final CurrentUser ADMIN =
        CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "An Admin", "admin-2");

    /** A judge. The role IDAM does not issue yet, supplied directly -- which is what makes it testable. */
    private static final CurrentUser JUDGE =
        CurrentUser.currentUser(List.of(Role.JUDICIAL), "District Judge Bell", "judge-3");

    /** A second judge, for the questions that are about which of two judges holds a referral. */
    private static final CurrentUser OTHER_JUDGE =
        CurrentUser.currentUser(List.of(Role.JUDICIAL), "District Judge Okafor", "judge-4");

    /** The configured window, long enough that the arithmetic in a test is unambiguous. */
    private static final Duration CLAIM_EXPIRY = Duration.ofHours(2);

    private DeedPollRepository deedPolls;
    private ReferralRepository referrals;
    private NoteService notes;
    private ReferralService service;

    /**
     * Every referral the fake database holds, so the state projection can be answered from it.
     *
     * <p>What makes the current-referral-state recompute provable without a container: the service reads the
     * states of a case's referrals <em>after</em> mutating one of them, so a stubbed constant would prove
     * nothing about the transition. These are the same objects the transitions mutate, and a referral raised in
     * the call under test is added by the {@code save} stub, so the projection sees exactly what the real
     * query would.
     */
    private final List<Referral> rows = new ArrayList<>();

    @BeforeEach
    void createService() {
        deedPolls = mock(DeedPollRepository.class);
        referrals = mock(ReferralRepository.class);
        notes = mock(NoteService.class);
        service = serviceWithClaimExpiry(CLAIM_EXPIRY);

        // The repository hands back what it was given, as a real save does once the id is allocated -- and
        // records the row, because an INSERT is what makes the new referral visible to the state projection.
        when(referrals.save(any())).thenAnswer(call -> {
            Referral written = call.getArgument(0);
            if (rows.stream().noneMatch(row -> row == written)) {
                rows.add(written);
            }
            return written;
        });
        // No stale claims unless a test says otherwise, so the sweep at the top of claim() is a no-op.
        when(referrals.findStaleClaims(any(), any())).thenReturn(List.of());
        // Scoped to the case asked about, as the real query is: a recompute that read another case's referrals
        // would pass a stub that ignored the argument.
        when(referrals.findStatesByDeedPollId(anyString()))
            .thenAnswer(call -> statesOf(call.getArgument(0)));
    }

    /** The states of one case's referrals, as they stand at the moment of the call. */
    private List<ReferralState> statesOf(String deedPollId) {
        return rows.stream()
            .filter(row -> row.getDeedPoll() != null && deedPollId.equals(row.getDeedPoll().getId()))
            .map(Referral::getState)
            .toList();
    }

    /** The current referral state the service wrote onto the given case, which may legitimately be null. */
    private ReferralState caseColumnWritten(String deedPollId) {
        ArgumentCaptor<ReferralState> written = ArgumentCaptor.forClass(ReferralState.class);
        verify(deedPolls).writeCurrentReferralState(eq(deedPollId), written.capture());
        return written.getValue();
    }

    /** The same mocks, wired to a service configured with a different inactivity window. */
    private ReferralService serviceWithClaimExpiry(Duration window) {
        return new ReferralService(deedPolls, referrals, notes, new ReferralProperties(window));
    }

    /** The case exists, so the parent check passes. */
    private void storedCase(String id) {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setId(id);
        when(deedPolls.findById(id)).thenReturn(Optional.of(deedPoll));
    }

    /**
     * The case already holds a referral in the given state.
     *
     * <p>Answered from the states the service asks about rather than returning a flat {@code true}, so a
     * guard that asked about the wrong set would still be caught here: {@code Closed} is stored on the case
     * and the answer is nonetheless false, because the guard does not ask about terminal states.
     */
    private void liveReferral(ReferralState state) {
        when(referrals.existsByDeedPollIdAndStateIn(eq(CASE_ID), any()))
            .thenAnswer(call -> call.<Collection<ReferralState>>getArgument(1).contains(state));
    }

    private static CreateReferralInput input(Map<String, Object> raw) {
        return ReferralSchemas.parseCreateReferral(raw);
    }

    private static CreateReferralInput minimal() {
        return input(Map.of("reasons", List.of("Minor")));
    }

    /** A direction as the schema would have produced it, so a test cannot build a shape a judge could not send. */
    private static DirectionInput direction(String decision, String directionsText, boolean publishToGazette) {
        return ReferralSchemas.parseDirection(Map.of(
            "decision", decision,
            "directionsText", directionsText,
            "publishToGazette", publishToGazette));
    }

    /** The shortest acceptable direction, for the tests that are about who may record one rather than what. */
    private static DirectionInput approval() {
        return direction("Approved", "Approved", false);
    }

    /**
     * A stored referral of {@link #CASE_ID}, in the given state.
     */
    private Referral stored(ReferralState state) {
        DeedPoll parent = new DeedPoll();
        parent.setId(CASE_ID);
        Referral referral = new Referral();
        referral.setId(REFERRAL_ID);
        referral.setDeedPoll(parent);
        referral.setState(state);
        referral.setReasons(List.of("Minor"));
        referral.setCreatedOn(LocalDateTime.parse("2026-09-01T09:30:00"));
        referral.setModifiedOn(LocalDateTime.parse("2026-09-01T09:30:00"));
        when(referrals.findByIdAndDeedPollId(REFERRAL_ID, CASE_ID)).thenReturn(Optional.of(referral));
        rows.add(referral);
        return referral;
    }

    /**
     * A stored referral of {@link #CASE_ID} held by a judge, last touched {@code ago} before now.
     */
    private Referral storedClaim(CurrentUser judge, Duration ago) {
        Referral referral = stored(ReferralState.Claimed);
        LocalDateTime touched = AuditClock.now().minus(ago);
        referral.setClaimedBy(judge.name());
        referral.setClaimedAt(touched);
        referral.setLastTouchedAt(touched);
        return referral;
    }

    /** A claimed referral of its own case, so several can be swept in one call. */
    private Referral storedClaimOn(String deedPollId, UUID id, CurrentUser judge, Duration ago) {
        DeedPoll parent = new DeedPoll();
        parent.setId(deedPollId);
        LocalDateTime touched = AuditClock.now().minus(ago);
        Referral referral = new Referral();
        referral.setId(id);
        referral.setDeedPoll(parent);
        referral.setState(ReferralState.Claimed);
        referral.setClaimedBy(judge.name());
        referral.setClaimedAt(touched);
        referral.setLastTouchedAt(touched);
        referral.setCreatedOn(touched);
        referral.setModifiedOn(touched);
        when(referrals.findByIdAndDeedPollId(id, deedPollId)).thenReturn(Optional.of(referral));
        rows.add(referral);
        return referral;
    }

    /**
     * Makes the mocked repository answer the sweep's two statements the way the SQL does, over the given rows.
     *
     * <p><strong>This is what makes the staleness rule provable without a container.</strong> The rule is
     * arithmetic -- a claim untouched since {@code now - window} lapses and one touched since does not -- and
     * the service owns the window and the cutoff while the database owns only the comparison. So the
     * comparison is written once here, applied by both fake statements exactly as {@code ReferralRepository}
     * applies it in both real ones, and every test below asserts the service's half: which rows it asks
     * about, which cutoff it asks with, and what it does with the answer.
     * {@code ReferralRepositoryTest} proves the real statements against a real Postgres.
     *
     * <p>The release mutates the row, as the statement does, which is what lets a test claim a referral whose
     * claim lapsed a moment earlier in the same call.
     */
    private void rowsInTheDatabase(Referral... rows) {
        List<Referral> all = List.of(rows);
        when(referrals.findStaleClaims(eq(ReferralState.Claimed), any())).thenAnswer(call -> {
            LocalDateTime cutoff = call.getArgument(1);
            return all.stream()
                .filter(row -> lapsedBy(row, cutoff))
                .map(row -> new StaleClaim(row.getId(), row.getDeedPoll().getId(), row.getClaimedBy()))
                .toList();
        });
        when(referrals.releaseStaleClaim(any(), any(), any(), anyString())).thenAnswer(call -> {
            UUID id = call.getArgument(0);
            LocalDateTime cutoff = call.getArgument(1);
            for (Referral row : all) {
                if (row.getId().equals(id) && lapsedBy(row, cutoff)) {
                    row.setState(ReferralState.Open);
                    row.setClaimedBy(null);
                    row.setClaimedAt(null);
                    row.setLastTouchedAt(null);
                    row.setModifiedBy(call.getArgument(3));
                    row.setModifiedOn(call.getArgument(2));
                    return 1;
                }
            }
            // Nothing matched: the judge touched it, a caseworker withdrew it, or another replica got there
            // first. The real statement answers 0 for all three, and the caller must write no note.
            return 0;
        });
    }

    /** The predicate both sweep statements carry, in one place here as it is in one place in the SQL. */
    private static boolean lapsedBy(Referral row, LocalDateTime cutoff) {
        return row.getState() == ReferralState.Claimed
            && row.getLastTouchedAt() != null
            && row.getLastTouchedAt().isBefore(cutoff);
    }

    /** The cutoff the service asked the candidate query about. */
    private LocalDateTime cutoffAsked() {
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(referrals).findStaleClaims(eq(ReferralState.Claimed), cutoff.capture());
        return cutoff.getValue();
    }

    /** The referral the service saved. */
    private Referral saved() {
        ArgumentCaptor<Referral> written = ArgumentCaptor.forClass(Referral.class);
        verify(referrals).save(written.capture());
        return written.getValue();
    }

    /** The note the service wrote, and who it was attributed to. */
    private NoteWritten noteWritten() {
        ArgumentCaptor<CreateNoteInput> text = ArgumentCaptor.forClass(CreateNoteInput.class);
        ArgumentCaptor<NoteAuthor> author = ArgumentCaptor.forClass(NoteAuthor.class);
        ArgumentCaptor<String> caseId = ArgumentCaptor.forClass(String.class);
        verify(notes).createNote(caseId.capture(), text.capture(), author.capture());
        return new NoteWritten(caseId.getValue(), text.getValue().note(), author.getValue());
    }

    /**
     * Every note the service wrote, in the order it wrote them.
     *
     * <p>What {@link #noteWritten()} cannot state, and the reason it is worth a second helper: a claim whose own
     * sweep released a lapsed claim first writes two notes in one call, and the order of the two is the history
     * -- lapsed, then claimed again -- rather than an implementation detail.
     */
    private List<NoteWritten> notesWritten() {
        ArgumentCaptor<CreateNoteInput> text = ArgumentCaptor.forClass(CreateNoteInput.class);
        ArgumentCaptor<NoteAuthor> author = ArgumentCaptor.forClass(NoteAuthor.class);
        ArgumentCaptor<String> caseId = ArgumentCaptor.forClass(String.class);
        verify(notes, atLeastOnce()).createNote(caseId.capture(), text.capture(), author.capture());
        List<NoteWritten> written = new ArrayList<>();
        for (int i = 0; i < text.getAllValues().size(); i++) {
            written.add(new NoteWritten(caseId.getAllValues().get(i),
                                        text.getAllValues().get(i).note(),
                                        author.getAllValues().get(i)));
        }
        return written;
    }

    private record NoteWritten(String deedPollId, String text, NoteAuthor author) {
    }

    @Nested
    @DisplayName("raising a referral")
    class Creating {

        @Test
        @DisplayName("stores it Open, unclaimed, with the caller's name in both audit columns")
        void storesItOpen() {
            storedCase(CASE_ID);

            service.createReferral(CASE_ID, input(Map.of(
                "reasons", List.of("Minor", "Other"),
                "notes", "The applicant is nine",
                "urgency", "Urgent",
                "urgencyReason", "Hearing on Friday")), CASEWORKER);

            Referral written = saved();
            assertThat(written.getDeedPoll().getId()).isEqualTo(CASE_ID);
            // Open and nothing else: the pool is where a new referral goes, and the payload cannot say
            // otherwise.
            assertThat(written.getState()).isEqualTo(ReferralState.Open);
            // Names, not ordinals: the column is a JSON array of the enum's names, as a saved query's
            // conditions are.
            assertThat(written.getReasons()).containsExactly("Minor", "Other");
            assertThat(written.getNotes()).isEqualTo("The applicant is nine");
            assertThat(written.getUrgency()).isEqualTo(Urgency.Urgent);
            assertThat(written.getUrgencyReason()).isEqualTo("Hearing on Friday");
            // Not migrated: the flag is what explains a backfilled referral's empty reason set, so a
            // caseworker-raised one must not carry it.
            assertThat(written.isMigrated()).isFalse();
            assertThat(written.getCreatedBy()).isEqualTo("Ada Caseworker");
            assertThat(written.getModifiedBy()).isEqualTo("Ada Caseworker");
        }

        @Test
        @DisplayName("leaves the optional columns null rather than empty strings")
        void leavesTheOptionalColumnsNull() {
            storedCase(CASE_ID);

            service.createReferral(CASE_ID, minimal(), CASEWORKER);

            Referral written = saved();
            assertThat(written.getNotes()).isNull();
            assertThat(written.getUrgencyReason()).isNull();
            assertThat(written.getUrgency()).isEqualTo(Urgency.Normal);
        }

        @Test
        @DisplayName("writes a case note attributed to the caller, not the service account")
        void writesACaseNoteAttributedToTheCaller() {
            storedCase(CASE_ID);

            service.createReferral(CASE_ID, input(Map.of(
                "reasons", List.of("Minor"),
                "urgency", "Urgent",
                "urgencyReason", "Hearing on Friday")), CASEWORKER);

            NoteWritten note = noteWritten();
            // The acting caseworker: system-authored notes are the directions plans' business and use
            // NoteAuthor.NONE, so an author of the service account here would be a real caseworker's action
            // recorded as the machine's.
            assertThat(note.author().author()).isEqualTo("Ada Caseworker");
            assertThat(note.deedPollId()).isEqualTo(CASE_ID);
            assertThat(note.text()).contains("Referred to the judiciary", "Minor", "Hearing on Friday");
        }

        @Test
        @DisplayName("writes a note the notes validator would accept")
        void writesANoteTheNotesValidatorWouldAccept() {
            storedCase(CASE_ID);

            service.createReferral(CASE_ID, input(Map.of(
                "reasons", List.of("Minor", "Other"),
                "notes", "x".repeat(ReferralSchemas.MAX_NOTES_LENGTH),
                "urgency", "Urgent",
                "urgencyReason", "y".repeat(ReferralSchemas.MAX_URGENCY_REASON_LENGTH))), CASEWORKER);

            // The referral's free text is unbounded in the database and the note's is not, so the two limits
            // have to agree -- ReferralNotesTest pins the worst case, and this is the same claim reached
            // through the service that actually composes it.
            assertThat(NoteForm.validateNoteText(noteWritten().text())).isEmpty();
        }

        @Test
        @DisplayName("404s on a case that does not exist, and writes nothing")
        void refusesACaseThatDoesNotExist() {
            when(deedPolls.findById("nope")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createReferral("nope", minimal(), CASEWORKER))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("DeedPoll nope not found");

            verify(referrals, never()).save(any());
            verifyNoInteractions(notes);
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Open", "Claimed", "Decided"})
        @DisplayName("409s when the case already holds a referral that is not terminal")
        void refusesASecondLiveReferral(ReferralState live) {
            storedCase(CASE_ID);
            liveReferral(live);

            assertThatThrownBy(() -> service.createReferral(CASE_ID, minimal(), CASEWORKER))
                .isInstanceOf(ConflictError.class)
                .hasMessageContaining(CASE_ID);

            // The refusal alone would also pass against an implementation that threw after saving, which is
            // the failure that would put two of the same case in the shared pool.
            verify(referrals, never()).save(any());
            // And no note either: a timeline saying a referral was raised when none was is worse than silence.
            verifyNoInteractions(notes);
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Closed", "Withdrawn"})
        @DisplayName("permits a fresh referral when every existing one is terminal")
        void permitsAFreshReferralWhenEveryExistingOneIsTerminal(ReferralState terminal) {
            storedCase(CASE_ID);
            liveReferral(terminal);

            // The other half of the guard. A case referred and closed can be referred again -- an escape
            // hatch a check for "any referral at all" would have taken away.
            assertThatCode(() -> service.createReferral(CASE_ID, minimal(), CASEWORKER))
                .doesNotThrowAnyException();

            assertThat(saved().getState()).isEqualTo(ReferralState.Open);
        }

        @Test
        @DisplayName("asks the guard about every non-terminal state, derived from the enum")
        void asksTheGuardAboutEveryNonTerminalState() {
            storedCase(CASE_ID);

            service.createReferral(CASE_ID, minimal(), CASEWORKER);

            // The states are derived from ReferralState.isTerminal() rather than listed, which is what makes
            // a sixth state covered the day it is added. Asserted on the argument because that derivation is
            // invisible in the outcome of any single call.
            verify(referrals).existsByDeedPollIdAndStateIn(eq(CASE_ID), argThat(states ->
                Set.copyOf(states).equals(
                    Set.of(ReferralState.Open, ReferralState.Claimed, ReferralState.Decided))));
        }

        @Test
        @DisplayName("asks about the addressed case only, so another case's open referral does not block it")
        void asksAboutTheAddressedCaseOnly() {
            storedCase(OTHER_CASE_ID);
            liveReferral(ReferralState.Open);

            // CASE_ID has a live referral; OTHER_CASE_ID does not, and the guard is per case rather than
            // global. A guard that dropped the case id would make one open referral freeze the whole service.
            assertThatCode(() -> service.createReferral(OTHER_CASE_ID, minimal(), CASEWORKER))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("is open to a caller holding no roles, because there is no referral permission")
        void isOpenToACallerHoldingNoRoles() {
            // Asserted rather than assumed: in the process this replaces, anybody who works a case can email
            // a Master, so the absence of a role gate is a decision. An administrator is checked alongside so
            // this cannot pass by the service ignoring the caller entirely.
            storedCase(CASE_ID);

            assertThatCode(() -> service.createReferral(CASE_ID, minimal(), CASEWORKER))
                .doesNotThrowAnyException();
            assertThat(saved().getCreatedBy()).isEqualTo("Ada Caseworker");
        }

        @Test
        @DisplayName("attributes an administrator's referral to the administrator")
        void attributesAnAdministratorsReferralToTheAdministrator() {
            storedCase(CASE_ID);

            service.createReferral(CASE_ID, minimal(), ADMIN);

            assertThat(saved().getCreatedBy()).isEqualTo("An Admin");
            assertThat(noteWritten().author().author()).isEqualTo("An Admin");
        }
    }

    @Nested
    @DisplayName("reading referrals")
    class Reading {

        @Test
        @DisplayName("lists a case's referrals newest first")
        void listsACasesReferralsNewestFirst() {
            storedCase(CASE_ID);
            Referral referral = stored(ReferralState.Open);
            when(referrals.findByDeedPollIdOrderByCreatedOnDesc(CASE_ID)).thenReturn(List.of(referral));

            assertThat(service.findReferrals(CASE_ID)).containsExactly(referral);
        }

        @Test
        @DisplayName("404s listing for a case that does not exist, rather than answering an empty list")
        void refusesToListForACaseThatDoesNotExist() {
            // An empty list would say "this case has never been referred", which is a different fact from
            // "there is no such case" -- and the wrong one for a mistyped id. The same rule NoteService keeps.
            when(deedPolls.findById("nope")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.findReferrals("nope")).isInstanceOf(NotFoundError.class);
            verify(referrals, never()).findByDeedPollIdOrderByCreatedOnDesc(anyString());
        }

        @Test
        @DisplayName("returns an empty list for a real case that has never been referred")
        void returnsAnEmptyListForARealCase() {
            storedCase(CASE_ID);
            when(referrals.findByDeedPollIdOrderByCreatedOnDesc(CASE_ID)).thenReturn(List.of());

            // The other half of the pair: the 404 above must not swallow the legitimately empty case.
            assertThat(service.findReferrals(CASE_ID)).isEmpty();
        }

        @Test
        @DisplayName("reads one referral through its own case")
        void readsOneReferralThroughItsOwnCase() {
            Referral referral = stored(ReferralState.Open);

            assertThat(service.findReferral(CASE_ID, REFERRAL_ID.toString())).isSameAs(referral);
        }

        @Test
        @DisplayName("404s a referral addressed through another case, and never asks by id alone")
        void refusesAReferralAddressedThroughAnotherCase() {
            stored(ReferralState.Open);

            // The scoping rule. The parent is in the lookup rather than in a guard in front of it, and the
            // repository has no finder by referral id alone, so a cross-case read is not expressible.
            assertThatThrownBy(() -> service.findReferral(OTHER_CASE_ID, REFERRAL_ID.toString()))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Referral " + REFERRAL_ID + " not found");

            verify(referrals, never()).findById(any());
        }

        @Test
        @DisplayName("404s an id nothing was stored under")
        void refusesAnIdNothingWasStoredUnder() {
            when(referrals.findByIdAndDeedPollId(UNKNOWN_ID, CASE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.findReferral(CASE_ID, UNKNOWN_ID.toString()))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Referral " + UNKNOWN_ID + " not found");
        }

        @Test
        @DisplayName("404s an id that is not an identifier at all, without asking the database")
        void refusesAMalformedId() {
            assertThatThrownBy(() -> service.findReferral(CASE_ID, "not-a-uuid"))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Referral not-a-uuid not found");

            // Nothing could ever have been stored under it, so "not found" is the true answer rather than a
            // 400, and there is no lookup to make.
            verify(referrals, never()).findByIdAndDeedPollId(any(), anyString());
            verify(referrals, never()).findById(any());
        }
    }

    @Nested
    @DisplayName("withdrawing a referral")
    class Withdrawing {

        @Test
        @DisplayName("moves an open referral to Withdrawn, keeping the row")
        void movesAnOpenReferralToWithdrawn() {
            final Referral referral = stored(ReferralState.Open);

            service.withdrawReferral(CASE_ID, REFERRAL_ID.toString(), CASEWORKER);

            Referral written = saved();
            // Not a delete: the case's history still shows that it was referred, and Withdrawn is
            // deliberately not Closed -- a report that could not tell them apart would count an abandoned
            // referral as work done.
            assertThat(written.getState()).isEqualTo(ReferralState.Withdrawn);
            assertThat(written.getId()).isEqualTo(REFERRAL_ID);
            assertThat(written.getModifiedBy()).isEqualTo("Ada Caseworker");
            assertThat(written.getModifiedOn()).isAfter(referral.getCreatedOn());
            // The audit trail of who raised it survives the withdrawal.
            assertThat(written.getCreatedOn()).isEqualTo(LocalDateTime.parse("2026-09-01T09:30:00"));
        }

        @Test
        @DisplayName("writes a case note attributed to the caller, naming when the referral was raised")
        void writesACaseNoteAttributedToTheCaller() {
            stored(ReferralState.Open);

            service.withdrawReferral(CASE_ID, REFERRAL_ID.toString(), CASEWORKER);

            NoteWritten note = noteWritten();
            assertThat(note.author().author()).isEqualTo("Ada Caseworker");
            assertThat(note.deedPollId()).isEqualTo(CASE_ID);
            // A case may carry several referrals over its life, so a bare "referral withdrawn" in a grid of
            // thirty notes would not say which.
            assertThat(note.text()).contains("withdrawn", "1 September 2026", "Minor");
        }

        @Test
        @DisplayName("withdraws a referral a judge is holding, and that voids their claim")
        void withdrawsAReferralAJudgeIsAlreadyHolding() {
            // Claimed stays withdrawable under the tightened rule, and this is why: the office may need to
            // correct or cancel a referral while the papers are out, and the judge has recorded nothing yet.
            // The new rule starts at the direction, not at the claim.
            storedClaim(JUDGE, Duration.ofMinutes(5));

            assertThatCode(() -> service.withdrawReferral(CASE_ID, REFERRAL_ID.toString(), CASEWORKER))
                .doesNotThrowAnyException();

            assertThat(saved().getState()).isEqualTo(ReferralState.Withdrawn);
            // And the transition is what makes withdrawing a held referral safe rather than a race: the judge
            // who had the papers open no longer holds a Claimed referral, so a late direction is refused
            // instead of landing on a referral the office pulled back.
            assertThatThrownBy(() -> service.decide(CASE_ID, REFERRAL_ID.toString(), approval(), JUDGE))
                .isInstanceOf(ConflictError.class)
                .hasMessageContaining(ReferralState.Withdrawn.name());
        }

        @Test
        @DisplayName("409s a Decided referral, naming the direction that has to be actioned instead")
        void refusesADecidedReferral() {
            // The rule this plan exists for. Decided is deliberately non-terminal -- the case still holds the
            // referral and may not raise a second -- and it is nonetheless not withdrawable, because a judge
            // has recorded a direction and withdrawing it would discard judicial work the office is obliged
            // to action. Closing it is the office's move.
            Referral referral = stored(ReferralState.Decided);
            referral.setDecision(MasterDecision.Approved);
            referral.setDecidedBy("District Judge Bell");

            assertThatThrownBy(() -> service.withdrawReferral(CASE_ID, REFERRAL_ID.toString(), CASEWORKER))
                .isInstanceOf(ConflictError.class)
                .hasMessageContaining(ReferralState.Decided.name())
                // Not the "already X" wording a terminal refusal gets: the referral is not finished, and a
                // caseworker looking at a returned direction must be told what to do with it rather than that
                // they have already withdrawn it.
                .hasMessageContaining("must be actioned")
                .hasMessageNotContaining("already");

            // Nothing moved, and the direction is untouched: a refusal that had half-written would be worse
            // than the behaviour it replaces.
            assertThat(referral.getState()).isEqualTo(ReferralState.Decided);
            assertThat(referral.getDecision()).isEqualTo(MasterDecision.Approved);
            verify(referrals, never()).save(any());
            verifyNoInteractions(notes);
            // And the case stays in the "returned -- action needed" queue, because the column is not recomputed
            // at all -- a refused transition writes nothing to the case row.
            verify(deedPolls, never()).writeCurrentReferralState(anyString(), any());
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Closed", "Withdrawn"})
        @DisplayName("409s a referral that is already terminal, and leaves it as it was")
        void refusesATerminalReferral(ReferralState terminal) {
            Referral referral = stored(terminal);

            assertThatThrownBy(() -> service.withdrawReferral(CASE_ID, REFERRAL_ID.toString(), CASEWORKER))
                .isInstanceOf(ConflictError.class)
                .hasMessage("Referral " + REFERRAL_ID + " is already " + terminal + " and cannot be withdrawn");

            // Not idempotent-and-silent, and not a rewrite: a closed referral must not be turned into a
            // withdrawn one, which would erase the fact that a direction was actioned.
            assertThat(referral.getState()).isEqualTo(terminal);
            verify(referrals, never()).save(any());
            verifyNoInteractions(notes);
        }

        @ParameterizedTest
        @EnumSource(ReferralState.class)
        @DisplayName("agrees with ReferralState.canBeWithdrawn() about every state there is")
        void agreesWithTheEnumAboutEveryState(ReferralState state) {
            // Exhaustive over values() rather than over the four states named above, so a sixth state added to
            // the enum is answered here by whatever decision was taken on canBeWithdrawn() rather than falling
            // through this class untested.
            stored(state);

            if (state.canBeWithdrawn()) {
                assertThatCode(() -> service.withdrawReferral(CASE_ID, REFERRAL_ID.toString(), CASEWORKER))
                    .doesNotThrowAnyException();
                assertThat(saved().getState()).isEqualTo(ReferralState.Withdrawn);
            } else {
                assertThatThrownBy(() -> service.withdrawReferral(CASE_ID, REFERRAL_ID.toString(), CASEWORKER))
                    .isInstanceOf(ConflictError.class)
                    .hasMessageContaining(state.name());
                verify(referrals, never()).save(any());
            }
        }

        @Test
        @DisplayName("404s a referral addressed through another case, and writes nothing")
        void refusesAReferralAddressedThroughAnotherCase() {
            Referral referral = stored(ReferralState.Open);

            assertThatThrownBy(() -> service.withdrawReferral(OTHER_CASE_ID, REFERRAL_ID.toString(), CASEWORKER))
                .isInstanceOf(NotFoundError.class);

            // The assertion that carries the test: a wrong parent must not be able to withdraw somebody
            // else's referral.
            assertThat(referral.getState()).isEqualTo(ReferralState.Open);
            verify(referrals, never()).save(any());
            verifyNoInteractions(notes);
        }

        @Test
        @DisplayName("is open to a caller holding no roles, as raising one is")
        void isOpenToACallerHoldingNoRoles() {
            stored(ReferralState.Open);

            assertThatCode(() -> service.withdrawReferral(CASE_ID, REFERRAL_ID.toString(), CASEWORKER))
                .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("claiming a referral")
    class Claiming {

        @Test
        @DisplayName("moves an Open referral to Claimed, stamping who holds it and when")
        void movesAnOpenReferralToClaimed() {
            stored(ReferralState.Open);
            final LocalDateTime before = AuditClock.now();

            service.claim(CASE_ID, REFERRAL_ID.toString(), JUDGE);

            Referral written = saved();
            assertThat(written.getState()).isEqualTo(ReferralState.Claimed);
            assertThat(written.getClaimedBy()).isEqualTo("District Judge Bell");
            assertThat(written.getClaimedAt()).isBetween(before, AuditClock.now());
            // The expiry is measured from the last interaction, so the claim itself has to count as one --
            // otherwise a referral picked up and never opened again would never lapse.
            assertThat(written.getLastTouchedAt()).isEqualTo(written.getClaimedAt());
            assertThat(written.getModifiedBy()).isEqualTo("District Judge Bell");
            assertThat(written.getModifiedOn()).isBetween(before, AuditClock.now());
        }

        @Test
        @DisplayName("refuses a caseworker, who has no judicial role, and writes nothing")
        void refusesACaseworker() {
            stored(ReferralState.Open);

            // The gate is the whole reason Role.JUDICIAL exists before IDAM issues it: a caseworker must not
            // be able to pick a referral out of the judicial pool and answer it themselves.
            assertThatThrownBy(() -> service.claim(CASE_ID, REFERRAL_ID.toString(), CASEWORKER))
                .isInstanceOf(ForbiddenError.class)
                .hasMessageContaining(Role.JUDICIAL.claimValue());

            verify(referrals, never()).save(any());
            verifyNoInteractions(notes);
        }

        @Test
        @DisplayName("refuses an administrator too: admin is not judicial")
        void refusesAnAdministrator() {
            stored(ReferralState.Open);

            // Asserted rather than assumed. The admin role is this service's only other privilege, and a gate
            // written as "any privileged caller" would read the same in the happy path and be wrong here.
            assertThatThrownBy(() -> service.claim(CASE_ID, REFERRAL_ID.toString(), ADMIN))
                .isInstanceOf(ForbiddenError.class);

            verify(referrals, never()).save(any());
        }

        @Test
        @DisplayName("refuses the gate before it looks the referral up at all")
        void refusesTheGateBeforeLookingItUp() {
            assertThatThrownBy(() -> service.claim(CASE_ID, REFERRAL_ID.toString(), CASEWORKER))
                .isInstanceOf(ForbiddenError.class);

            // A caller with no judicial role learns nothing about whether the referral exists, and the sweep
            // is not run on their behalf either.
            verify(referrals, never()).findByIdAndDeedPollId(any(), anyString());
            verify(referrals, never()).findStaleClaims(any(), any());
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Claimed", "Decided", "Closed", "Withdrawn"})
        @DisplayName("409s anything that is not Open, and leaves it as it was")
        void refusesAnythingThatIsNotOpen(ReferralState state) {
            Referral referral = stored(state);
            referral.setClaimedBy(state == ReferralState.Claimed ? OTHER_JUDGE.name() : null);
            // Touched a moment ago, so the sweep has no business releasing it.
            referral.setLastTouchedAt(AuditClock.now());
            rowsInTheDatabase(referral);

            assertThatThrownBy(() -> service.claim(CASE_ID, REFERRAL_ID.toString(), JUDGE))
                .isInstanceOf(ConflictError.class)
                .hasMessageContaining(state.name());

            assertThat(referral.getState()).isEqualTo(state);
            verify(referrals, never()).save(any());
            // And no note either: a timeline saying the judiciary claimed a referral it did not claim is worse
            // than a timeline that is silent about a refused click.
            verifyNoInteractions(notes);
        }

        @Test
        @DisplayName("409s a judge trying to claim what they are already holding, rather than re-claiming it")
        void refusesTheHoldingJudgesOwnSecondClaim() {
            Referral referral = storedClaim(JUDGE, Duration.ofMinutes(5));
            rowsInTheDatabase(referral);

            // No silent re-claim: two judges each shown "claimed by me" for different reasons is worse than
            // one of them being told plainly. A judge who is still working keeps it by interacting.
            assertThatThrownBy(() -> service.claim(CASE_ID, REFERRAL_ID.toString(), JUDGE))
                .isInstanceOf(ConflictError.class);

            assertThat(referral.getClaimedBy()).isEqualTo("District Judge Bell");
            assertThat(referral.getLastTouchedAt()).isNotNull();
            verify(referrals, never()).save(any());
            // No second claim note for a claim that did not happen: the note follows the transition, so a
            // refreshed dashboard does not grow a note per click.
            verifyNoInteractions(notes);
        }

        @Test
        @DisplayName("takes back a claim that has lapsed, so the next judge to try gets the referral")
        void takesBackALapsedClaimFirst() {
            Referral lapsed = storedClaim(OTHER_JUDGE, CLAIM_EXPIRY.plusMinutes(1));
            rowsInTheDatabase(lapsed);

            // The defensive sweep at the top of claim(). Without it, a referral abandoned by one judge would
            // be claimable only by whoever next happened to load the pool.
            service.claim(CASE_ID, REFERRAL_ID.toString(), JUDGE);

            Referral written = saved();
            assertThat(written.getState()).isEqualTo(ReferralState.Claimed);
            assertThat(written.getClaimedBy()).isEqualTo("District Judge Bell");
            // And the case says the first judge's claim lapsed, so the timeline explains the handover -- then
            // says the second judge has it, which is this call's own transition.
            assertThat(notesWritten()).hasSize(2);
            assertThat(notesWritten().get(0).text())
                .contains("District Judge Okafor", "lapsed after inactivity");
            assertThat(notesWritten().get(1).text()).contains("claimed by the judiciary");
        }

        @Test
        @DisplayName("does not take back a claim inside the window, so it is still a conflict")
        void doesNotTakeBackAFreshClaim() {
            Referral held = storedClaim(OTHER_JUDGE, CLAIM_EXPIRY.minusMinutes(1));
            rowsInTheDatabase(held);

            // The other half of the sweep: one minute short of the window is a judge still working, and
            // claiming it out from under them would be worse than the 409.
            assertThatThrownBy(() -> service.claim(CASE_ID, REFERRAL_ID.toString(), JUDGE))
                .isInstanceOf(ConflictError.class);

            assertThat(held.getClaimedBy()).isEqualTo("District Judge Okafor");
            verifyNoInteractions(notes);
        }

        @Test
        @DisplayName("404s a referral addressed through another case, and claims nothing")
        void refusesAReferralAddressedThroughAnotherCase() {
            Referral referral = stored(ReferralState.Open);

            assertThatThrownBy(() -> service.claim(OTHER_CASE_ID, REFERRAL_ID.toString(), JUDGE))
                .isInstanceOf(NotFoundError.class);

            assertThat(referral.getState()).isEqualTo(ReferralState.Open);
            verify(referrals, never()).save(any());
        }

        @Test
        @DisplayName("writes one case note attributed to the claiming judge, saying which referral it is")
        void writesACaseNoteAttributedToTheClaimingJudge() {
            stored(ReferralState.Open);

            service.claim(CASE_ID, REFERRAL_ID.toString(), JUDGE);

            NoteWritten note = noteWritten();
            // The judge who claimed it, not the service account and not a caseworker: they are the actor, and
            // createdBy is where their name belongs -- which is why the prose does not name them a second time.
            assertThat(note.author().author()).isEqualTo("District Judge Bell");
            assertThat(note.deedPollId()).isEqualTo(CASE_ID);
            assertThat(note.text()).isEqualTo(
                "Referral raised on 1 September 2026, reasons: Minor, claimed by the judiciary.");
            // A case may carry several referrals over its life, so the note has to say which one -- by the
            // raised date and reasons a caseworker can recognise, never by a uuid they cannot.
            assertThat(note.text()).doesNotContain(REFERRAL_ID.toString());
        }

        @Test
        @DisplayName("writes claimed, lapsed and claimed again, in that order and each to its own actor")
        void writesTheWholeLapseAndReclaimSequence() {
            Referral referral = stored(ReferralState.Open);
            rowsInTheDatabase(referral);

            service.claim(CASE_ID, REFERRAL_ID.toString(), OTHER_JUDGE);
            // The first judge stops working on it, which is what an abandoned claim is: the row is otherwise
            // exactly the one their claim produced.
            referral.setLastTouchedAt(AuditClock.now().minus(CLAIM_EXPIRY.plusMinutes(1)));
            service.claim(CASE_ID, REFERRAL_ID.toString(), JUDGE);

            // There is no "reclaimed" transition, and this is the honest sequence rather than a summary: the
            // next successful claim writes another claim note, so the grid reads claimed, lapsed, claimed.
            assertThat(notesWritten()).satisfiesExactly(
                claimed -> {
                    assertThat(claimed.text()).contains("claimed by the judiciary");
                    assertThat(claimed.author().author()).isEqualTo("District Judge Okafor");
                },
                lapsed -> {
                    assertThat(lapsed.text()).contains("District Judge Okafor", "lapsed after inactivity");
                    // The service released it, not the judge whose claim call happened to notice, so the lapse
                    // stays the service account's note between two human-authored ones.
                    assertThat(lapsed.author()).isEqualTo(NoteAuthor.NONE);
                },
                reclaimed -> {
                    assertThat(reclaimed.text()).contains("claimed by the judiciary");
                    assertThat(reclaimed.author().author()).isEqualTo("District Judge Bell");
                });
        }
    }

    @Nested
    @DisplayName("refreshing a claim")
    class Touching {

        @Test
        @DisplayName("moves lastTouchedAt forward without touching the claim itself")
        void movesLastTouchedAtForward() {
            Referral referral = storedClaim(JUDGE, Duration.ofMinutes(90));
            LocalDateTime claimedAt = referral.getClaimedAt();

            service.touch(CASE_ID, REFERRAL_ID.toString(), JUDGE);

            Referral written = saved();
            assertThat(written.getLastTouchedAt()).isAfter(claimedAt);
            // The claim is not renewed, only its inactivity clock: claimedAt still says when the judge picked
            // it up, which is what a caseworker asking "how long has the judiciary had this" reads.
            assertThat(written.getClaimedAt()).isEqualTo(claimedAt);
            assertThat(written.getClaimedBy()).isEqualTo("District Judge Bell");
            assertThat(written.getState()).isEqualTo(ReferralState.Claimed);
            assertThat(written.getModifiedBy()).isEqualTo("District Judge Bell");
        }

        @Test
        @DisplayName("refuses a caseworker, and writes nothing")
        void refusesACaseworker() {
            storedClaim(JUDGE, Duration.ofMinutes(5));

            assertThatThrownBy(() -> service.touch(CASE_ID, REFERRAL_ID.toString(), CASEWORKER))
                .isInstanceOf(ForbiddenError.class);

            verify(referrals, never()).save(any());
        }

        @Test
        @DisplayName("409s a judge refreshing a claim another judge holds")
        void refusesAnotherJudgesClaim() {
            Referral referral = storedClaim(OTHER_JUDGE, Duration.ofMinutes(5));
            LocalDateTime touched = referral.getLastTouchedAt();

            // Holding a referral is what a judicial write is entitled to, so a second judge must not be able
            // to keep somebody else's claim alive -- nor, in the plans that follow, to write on it.
            assertThatThrownBy(() -> service.touch(CASE_ID, REFERRAL_ID.toString(), JUDGE))
                .isInstanceOf(ConflictError.class)
                .hasMessageContaining("District Judge Bell");

            assertThat(referral.getLastTouchedAt()).isEqualTo(touched);
            verify(referrals, never()).save(any());
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Open", "Decided", "Closed", "Withdrawn"})
        @DisplayName("409s a referral nobody is holding, whatever became of it")
        void refusesAReferralNobodyIsHolding(ReferralState state) {
            // The lapse-and-withdrawal guarantee, from the other side: a judge who left the page open finds
            // their claim is gone rather than silently reviving one the pool has already re-offered.
            stored(state);

            assertThatThrownBy(() -> service.touch(CASE_ID, REFERRAL_ID.toString(), JUDGE))
                .isInstanceOf(ConflictError.class)
                .hasMessageContaining(state.name());

            verify(referrals, never()).save(any());
        }

        @Test
        @DisplayName("404s a referral addressed through another case")
        void refusesAReferralAddressedThroughAnotherCase() {
            storedClaim(JUDGE, Duration.ofMinutes(5));

            assertThatThrownBy(() -> service.touch(OTHER_CASE_ID, REFERRAL_ID.toString(), JUDGE))
                .isInstanceOf(NotFoundError.class);

            verify(referrals, never()).save(any());
        }

        @Test
        @DisplayName("writes no case note, however many times a judge reads the papers")
        void writesNoCaseNote() {
            storedClaim(JUDGE, Duration.ofMinutes(5));

            service.touch(CASE_ID, REFERRAL_ID.toString(), JUDGE);
            service.touch(CASE_ID, REFERRAL_ID.toString(), JUDGE);

            // A touch is not a lifecycle transition -- it moves lastTouchedAt and never state -- and every
            // judicial read of a held referral is one, so a note each would bury the four that matter under a
            // page of "still reading".
            verifyNoInteractions(notes);
        }
    }

    @Nested
    @DisplayName("recording a direction")
    class Deciding {

        @Test
        @DisplayName("records the direction, moves Claimed to Decided and signs it with the judge's name")
        void recordsTheDirection() {
            Referral referral = storedClaim(JUDGE, Duration.ofMinutes(20));
            final LocalDateTime claimedAt = referral.getClaimedAt();
            final LocalDateTime before = AuditClock.now();

            service.decide(CASE_ID, REFERRAL_ID.toString(), direction("Approved", "No publication", false), JUDGE);

            Referral written = saved();
            assertThat(written.getState()).isEqualTo(ReferralState.Decided);
            assertThat(written.getDecision()).isEqualTo(MasterDecision.Approved);
            assertThat(written.getDirectionsText()).isEqualTo("No publication");
            assertThat(written.getPublishToGazette()).isFalse();
            assertThat(written.getDecisionDate()).isBetween(before, AuditClock.now());
            // From the token and the clock, never from the payload: a direction that could name its own author
            // would be a signature anybody could forge.
            assertThat(written.getDecidedBy()).isEqualTo("District Judge Bell");
            assertThat(written.getModifiedBy()).isEqualTo("District Judge Bell");
            // The claim's own columns are left as they were: claimedAt is what answers "how long did the
            // judiciary have this", and rewriting it at the decision would erase the answer.
            assertThat(written.getClaimedAt()).isEqualTo(claimedAt);
            assertThat(written.getClaimedBy()).isEqualTo("District Judge Bell");
            // Refreshed even though the sweep will never look at this row again, so the column keeps meaning
            // "when a judge last did something with this".
            assertThat(written.getLastTouchedAt()).isBetween(before, AuditClock.now());
        }

        @Test
        @DisplayName("stores a Rejected direction with publication just as readily")
        void storesARejection() {
            storedClaim(JUDGE, Duration.ofMinutes(1));

            service.decide(CASE_ID, REFERRAL_ID.toString(),
                           direction("Rejected", "Refused: no consent from the second parent", true), JUDGE);

            Referral written = saved();
            assertThat(written.getDecision()).isEqualTo(MasterDecision.Rejected);
            assertThat(written.getPublishToGazette()).isTrue();
            // Publication is the judge's decision and is not tied to the outcome, so a rejection that directs
            // publication is a real answer rather than a contradiction to normalise away.
            assertThat(written.getState()).isEqualTo(ReferralState.Decided);
        }

        @Test
        @DisplayName("leaves the directions text null rather than an empty string when there is none")
        void leavesTheDirectionsTextNull() {
            storedClaim(JUDGE, Duration.ofMinutes(1));

            service.decide(CASE_ID, REFERRAL_ID.toString(),
                           new DirectionInput(MasterDecision.Approved, Optional.empty(), false), JUDGE);

            assertThat(saved().getDirectionsText()).isNull();
        }

        @Test
        @DisplayName("refuses a caseworker, who has no judicial role, and writes nothing")
        void refusesACaseworker() {
            storedClaim(JUDGE, Duration.ofMinutes(1));

            // A caseworker directing a referral would be the office answering its own question to the
            // judiciary, which is the one thing this feature exists to stop happening informally.
            assertThatThrownBy(() -> service.decide(CASE_ID, REFERRAL_ID.toString(), approval(), CASEWORKER))
                .isInstanceOf(ForbiddenError.class)
                .hasMessageContaining(Role.JUDICIAL.claimValue());

            verify(referrals, never()).save(any());
            verifyNoInteractions(notes);
        }

        @Test
        @DisplayName("refuses an administrator too: admin is not judicial")
        void refusesAnAdministrator() {
            storedClaim(JUDGE, Duration.ofMinutes(1));

            assertThatThrownBy(() -> service.decide(CASE_ID, REFERRAL_ID.toString(), approval(), ADMIN))
                .isInstanceOf(ForbiddenError.class);

            verify(referrals, never()).save(any());
        }

        @Test
        @DisplayName("refuses the gate before it looks the referral up at all")
        void refusesTheGateBeforeLookingItUp() {
            assertThatThrownBy(() -> service.decide(CASE_ID, REFERRAL_ID.toString(), approval(), CASEWORKER))
                .isInstanceOf(ForbiddenError.class);

            verify(referrals, never()).findByIdAndDeedPollId(any(), anyString());
        }

        @Test
        @DisplayName("409s a judge directing a referral another judge holds, and leaves it untouched")
        void refusesAnotherJudgesReferral() {
            Referral referral = storedClaim(OTHER_JUDGE, Duration.ofMinutes(10));

            // The state alone is not enough and neither is the role: two judges both hold JUDICIAL and both see
            // a Claimed referral, so what entitles one of them to write on it is that it is theirs.
            assertThatThrownBy(() -> service.decide(CASE_ID, REFERRAL_ID.toString(), approval(), JUDGE))
                .isInstanceOf(ConflictError.class)
                .hasMessageContaining("District Judge Bell");

            assertThat(referral.getState()).isEqualTo(ReferralState.Claimed);
            assertThat(referral.getDecision()).isNull();
            verify(referrals, never()).save(any());
            // And nothing on the other judge's case timeline either, which would otherwise be the one trace of
            // an attempt the guard refused.
            verifyNoInteractions(notes);
        }

        @Test
        @DisplayName("409s after a caseworker withdrew the referral, so a late submission cannot land")
        void refusesAfterAWithdrawal() {
            // The withdraw-voids-a-claim guarantee, from the judge's side. The judge had the papers open; a
            // caseworker pulled the referral back; the direction they submit afterwards must not be recorded on
            // a referral that is no longer in play.
            Referral referral = storedClaim(JUDGE, Duration.ofMinutes(10));
            service.withdrawReferral(CASE_ID, REFERRAL_ID.toString(), CASEWORKER);

            assertThatThrownBy(() -> service.decide(CASE_ID, REFERRAL_ID.toString(), approval(), JUDGE))
                .isInstanceOf(ConflictError.class)
                .hasMessageContaining(ReferralState.Withdrawn.name());

            assertThat(referral.getState()).isEqualTo(ReferralState.Withdrawn);
            assertThat(referral.getDecision()).isNull();
            assertThat(referral.getDecidedBy()).isNull();
            // The withdrawal's note and no direction note: the timeline ends where the referral did.
            assertThat(notesWritten()).singleElement()
                .satisfies(note -> assertThat(note.text()).contains("withdrawn"));
        }

        @Test
        @DisplayName("409s after the sweep took the claim back, so a lapsed judge cannot direct it")
        void refusesAfterTheClaimLapsed() {
            // The other half of the same guarantee. The claim lapsed while the judge read the papers, the
            // referral went back into the pool, and their direction must not land on it -- another judge may
            // hold it by now, and both of them directing the same referral is the outcome this prevents.
            Referral referral = storedClaim(JUDGE, CLAIM_EXPIRY.plusMinutes(5));
            rowsInTheDatabase(referral);
            assertThat(service.reclaimStaleClaims()).isEqualTo(1);

            assertThatThrownBy(() -> service.decide(CASE_ID, REFERRAL_ID.toString(), approval(), JUDGE))
                .isInstanceOf(ConflictError.class)
                .hasMessageContaining(ReferralState.Open.name());

            assertThat(referral.getDecision()).isNull();
            verify(referrals, never()).save(any());
            // The sweep's lapse note and nothing after it: the direction that arrived too late left no trace
            // suggesting it landed.
            assertThat(notesWritten()).singleElement()
                .satisfies(note -> assertThat(note.text()).contains("lapsed after inactivity"));
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Open", "Decided", "Closed", "Withdrawn"})
        @DisplayName("409s a referral nobody is holding, whatever became of it")
        void refusesAReferralNobodyIsHolding(ReferralState state) {
            Referral referral = stored(state);

            assertThatThrownBy(() -> service.decide(CASE_ID, REFERRAL_ID.toString(), approval(), JUDGE))
                .isInstanceOf(ConflictError.class)
                .hasMessageContaining(state.name());

            // Decided included, which is what stops a judge revising a direction they have already given: a
            // second direction is not an amendment, it is two answers to one question.
            assertThat(referral.getState()).isEqualTo(state);
            verify(referrals, never()).save(any());
        }

        @Test
        @DisplayName("404s a referral addressed through another case, and directs nothing")
        void refusesAReferralAddressedThroughAnotherCase() {
            Referral referral = storedClaim(JUDGE, Duration.ofMinutes(10));

            assertThatThrownBy(() -> service.decide(OTHER_CASE_ID, REFERRAL_ID.toString(), approval(), JUDGE))
                .isInstanceOf(NotFoundError.class);

            assertThat(referral.getState()).isEqualTo(ReferralState.Claimed);
            assertThat(referral.getDecision()).isNull();
            verify(referrals, never()).save(any());
        }

        @Test
        @DisplayName("writes one case note attributed to the deciding judge, summarising the direction")
        void writesACaseNoteAttributedToTheDecidingJudge() {
            storedClaim(JUDGE, Duration.ofMinutes(10));

            service.decide(CASE_ID, REFERRAL_ID.toString(),
                           direction("Approved", "Granted. Publish in the Gazette.", true), JUDGE);

            NoteWritten note = noteWritten();
            assertThat(note.author().author()).isEqualTo("District Judge Bell");
            assertThat(note.deedPollId()).isEqualTo(CASE_ID);
            // The decision and the Gazette answer in full, because those two are what a caseworker scanning the
            // grid needs in order to know whether anything is waiting for them.
            assertThat(note.text()).isEqualTo(
                "Judicial direction recorded for referral raised on 1 September 2026, reasons: Minor."
                    + " Decision: Approved. Publish to the London Gazette: Yes."
                    + " See the referral for the full direction.");
            assertThat(note.text()).doesNotContain(REFERRAL_ID.toString());
        }

        @Test
        @DisplayName("summarises rather than copies the direction, so even the longest one writes its note")
        void doesNotCopyTheDirectionsTextIntoTheNote() {
            storedClaim(JUDGE, Duration.ofMinutes(10));
            String longest = "x".repeat(ReferralSchemas.MAX_DIRECTIONS_LENGTH);

            service.decide(CASE_ID, REFERRAL_ID.toString(), direction("Rejected", longest, false), JUDGE);

            NoteWritten note = noteWritten();
            // The whole reason the note is a summary: a direction may be twice as long as a note may be, so
            // copying it would make a valid direction fail the note it triggers and roll the direction back.
            assertThat(note.text()).doesNotContain("xxx");
            assertThat(NoteForm.validateNoteText(note.text())).isEmpty();
            // And the direction itself is stored in full, which is what the note points at.
            assertThat(saved().getDirectionsText()).isEqualTo(longest);
        }

        @Test
        @DisplayName("writes no note for a direction it refused, whatever became of the referral")
        void writesNoNoteForARefusedDirection() {
            stored(ReferralState.Decided);

            // Decided included, which is what stops a second direction adding a second note that would read as
            // though a judge had directed the referral twice.
            assertThatThrownBy(() -> service.decide(CASE_ID, REFERRAL_ID.toString(), approval(), JUDGE))
                .isInstanceOf(ConflictError.class);

            verifyNoInteractions(notes);
        }

        @Test
        @DisplayName("does not write the case's flat master columns, so the snapshot stays the snapshot")
        void doesNotWriteTheCasesFlatColumns() {
            Referral referral = storedClaim(JUDGE, Duration.ofMinutes(10));
            DeedPoll parent = referral.getDeedPoll();
            // A case whose flat block holds the pre-migration answer, which is the case the rule is about: a
            // referral raised on a case that was referred once before, by email, years ago.
            parent.setMasterDecision(MasterDecision.Approved);
            parent.setMasterDirections("Approved by Master Someone, 2019");
            parent.setPublishToLondonGazette(true);

            service.decide(CASE_ID, REFERRAL_ID.toString(), direction("Rejected", "Refused", false), JUDGE);

            // Untouched. Syncing the live direction back would make the case say two different things over time
            // -- the backfilled snapshot and whichever referral was latest -- and re-introduce the
            // one-referral-per-case model this feature replaced.
            assertThat(parent.getMasterDecision()).isEqualTo(MasterDecision.Approved);
            assertThat(parent.getMasterDirections()).isEqualTo("Approved by Master Someone, 2019");
            assertThat(parent.isPublishToLondonGazette()).isTrue();
            // The one column of the case a transition may write is the denormalised read-model, and it is
            // written by a statement rather than by saving the loaded case -- which is what keeps the flat
            // block above safe even though the entity is in the transaction's session.
            verify(deedPolls).writeCurrentReferralState(CASE_ID, ReferralState.Decided);
            verifyNoMoreInteractions(deedPolls);
        }
    }

    @Nested
    @DisplayName("closing a referral")
    class Closing {

        @Test
        @DisplayName("moves a Decided referral to Closed, keeping the direction exactly as it was")
        void movesADecidedReferralToClosed() {
            Referral referral = stored(ReferralState.Decided);
            referral.setDecision(MasterDecision.Approved);
            referral.setDirectionsText("Approved, publish");
            referral.setPublishToGazette(true);
            referral.setDecidedBy("District Judge Bell");
            LocalDateTime decidedOn = LocalDateTime.parse("2026-09-10T11:00:00");
            referral.setDecisionDate(decidedOn);

            service.close(CASE_ID, REFERRAL_ID.toString(), CASEWORKER);

            Referral written = saved();
            assertThat(written.getState()).isEqualTo(ReferralState.Closed);
            // Closing is an acknowledgement, not an edit: the direction the caseworker actioned has to still be
            // readable afterwards, because it is the record of what they actioned.
            assertThat(written.getDecision()).isEqualTo(MasterDecision.Approved);
            assertThat(written.getDirectionsText()).isEqualTo("Approved, publish");
            assertThat(written.getPublishToGazette()).isTrue();
            assertThat(written.getDecisionDate()).isEqualTo(decidedOn);
            assertThat(written.getDecidedBy()).isEqualTo("District Judge Bell");
            // And the closing caseworker is who last moved it, while decidedBy still says who directed it.
            assertThat(written.getModifiedBy()).isEqualTo("Ada Caseworker");
            assertThat(written.getModifiedOn()).isAfter(decidedOn);
        }

        @Test
        @DisplayName("is open to a caller holding no roles, as raising and withdrawing are")
        void isOpenToACallerHoldingNoRoles() {
            // Asserted rather than assumed: closing is the caseworker's acknowledgement that the direction has
            // been actioned, so there is no "may close" permission any more than there is a "may refer" one.
            stored(ReferralState.Decided);

            assertThatCode(() -> service.close(CASE_ID, REFERRAL_ID.toString(), CASEWORKER))
                .doesNotThrowAnyException();

            assertThat(saved().getState()).isEqualTo(ReferralState.Closed);
        }

        @Test
        @DisplayName("lets a judge's own token close one too, because the gate is authentication only")
        void doesNotRefuseAJudge() {
            // Not an endorsement of a judge closing their own referral -- the caseworker actions the direction,
            // and the judicial surface offers no closure -- but the domain gate is authentication, and pinning
            // that here is what stops a role check being added below the route by accident.
            stored(ReferralState.Decided);

            assertThatCode(() -> service.close(CASE_ID, REFERRAL_ID.toString(), JUDGE))
                .doesNotThrowAnyException();

            assertThat(saved().getModifiedBy()).isEqualTo("District Judge Bell");
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Open", "Claimed", "Closed", "Withdrawn"})
        @DisplayName("409s anything that is not Decided, and leaves it as it was")
        void refusesAnythingThatIsNotDecided(ReferralState state) {
            Referral referral = stored(state);

            // The one guard here written against a single state rather than against terminal-ness, and that is
            // the point of it: closing an Open or Claimed referral would take it out of the pool with no
            // direction on it, and closing a Withdrawn one would relabel an abandoned referral as an actioned
            // one.
            assertThatThrownBy(() -> service.close(CASE_ID, REFERRAL_ID.toString(), CASEWORKER))
                .isInstanceOf(ConflictError.class)
                .hasMessageContaining(state.name());

            assertThat(referral.getState()).isEqualTo(state);
            verify(referrals, never()).save(any());
            verifyNoInteractions(notes);
        }

        @Test
        @DisplayName("404s a referral addressed through another case, and it stays Decided")
        void refusesAReferralAddressedThroughAnotherCase() {
            Referral referral = stored(ReferralState.Decided);

            assertThatThrownBy(() -> service.close(OTHER_CASE_ID, REFERRAL_ID.toString(), CASEWORKER))
                .isInstanceOf(NotFoundError.class);

            assertThat(referral.getState()).isEqualTo(ReferralState.Decided);
            verify(referrals, never()).save(any());
            // And nothing on either case's timeline: a note written by a refused cross-case closure would put
            // the actioning of one case's direction onto another case.
            verifyNoInteractions(notes);
        }

        @Test
        @DisplayName("404s an id that is not an identifier at all, without asking the database")
        void refusesAMalformedId() {
            assertThatThrownBy(() -> service.close(CASE_ID, "not-a-uuid", CASEWORKER))
                .isInstanceOf(NotFoundError.class);

            verify(referrals, never()).findByIdAndDeedPollId(any(), anyString());
            verifyNoInteractions(notes);
        }

        @Test
        @DisplayName("writes one case note attributed to the closing caseworker, saying which referral it is")
        void writesACaseNoteAttributedToTheClosingCaseworker() {
            stored(ReferralState.Decided);

            service.close(CASE_ID, REFERRAL_ID.toString(), CASEWORKER);

            NoteWritten note = noteWritten();
            // The caseworker who actioned it, not the judge who directed it: decidedBy on the row still says who
            // directed it, and createdBy here says who carried it out.
            assertThat(note.author().author()).isEqualTo("Ada Caseworker");
            assertThat(note.deedPollId()).isEqualTo(CASE_ID);
            assertThat(note.text()).isEqualTo(
                "Judicial direction for referral raised on 1 September 2026, reasons: Minor, marked as actioned.");
            // Pairable with the direction note a few rows above it in the same grid, by the raised date and
            // reasons a caseworker can recognise rather than by a uuid they cannot.
            assertThat(note.text()).doesNotContain(REFERRAL_ID.toString());
        }

        @Test
        @DisplayName("acknowledges the direction without quoting it, however long the judge's text was")
        void doesNotQuoteTheDirectionInTheNote() {
            Referral referral = stored(ReferralState.Decided);
            String longest = "x".repeat(ReferralSchemas.MAX_DIRECTIONS_LENGTH);
            referral.setDecision(MasterDecision.Approved);
            referral.setDirectionsText(longest);
            referral.setPublishToGazette(true);
            referral.setDecidedBy("District Judge Bell");

            service.close(CASE_ID, REFERRAL_ID.toString(), CASEWORKER);

            NoteWritten note = noteWritten();
            // The closure note does not repeat the decision either -- the direction note is a few rows up -- so
            // the longest possible direction still writes a note the validator accepts rather than rolling the
            // closure back.
            assertThat(note.text()).doesNotContain("xxx");
            assertThat(NoteForm.validateNoteText(note.text())).isEmpty();
            // And the direction the caseworker actioned is still there in full, which is the record of what
            // they actioned.
            assertThat(saved().getDirectionsText()).isEqualTo(longest);
        }

        @Test
        @DisplayName("writes no second note for a closure it refused, so one actioning is one note")
        void writesNoNoteForASecondClosure() {
            stored(ReferralState.Decided);

            service.close(CASE_ID, REFERRAL_ID.toString(), CASEWORKER);
            // The closure took the referral to Closed, so the same click again is a conflict -- and a timeline
            // saying the direction was actioned twice would read as two pieces of work.
            assertThatThrownBy(() -> service.close(CASE_ID, REFERRAL_ID.toString(), CASEWORKER))
                .isInstanceOf(ConflictError.class);

            assertThat(notesWritten()).singleElement()
                .satisfies(note -> assertThat(note.text()).contains("marked as actioned"));
        }

        @Test
        @DisplayName("does not run the stale-claim sweep, because closing is not a judicial read")
        void doesNotSweep() {
            stored(ReferralState.Decided);

            service.close(CASE_ID, REFERRAL_ID.toString(), CASEWORKER);

            // The mutating read is contained to the pool load and to claim(). A caseworker closing a referral
            // has no business releasing another judge's claim on a different case.
            verify(referrals, never()).findStaleClaims(any(), any());
        }
    }

    @Nested
    @DisplayName("the stale-claim sweep")
    class ReclaimingStaleClaims {

        private static final UUID SECOND_REFERRAL_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

        @Test
        @DisplayName("releases a claim untouched for longer than the window, and says so on the case")
        void releasesAClaimUntouchedForLongerThanTheWindow() {
            Referral lapsed = storedClaim(OTHER_JUDGE, CLAIM_EXPIRY.plusMinutes(30));
            rowsInTheDatabase(lapsed);

            assertThat(service.reclaimStaleClaims()).isEqualTo(1);

            assertThat(lapsed.getState()).isEqualTo(ReferralState.Open);
            assertThat(lapsed.getClaimedBy()).isNull();
            assertThat(lapsed.getClaimedAt()).isNull();
            // Nulled rather than left behind: it is what stops the next sweep matching the same row and
            // writing a second note about one lapse.
            assertThat(lapsed.getLastTouchedAt()).isNull();

            NoteWritten note = noteWritten();
            assertThat(note.deedPollId()).isEqualTo(CASE_ID);
            assertThat(note.text()).contains("District Judge Okafor", "lapsed after inactivity");
            // No person did this, so it is the service account rather than whoever's page load noticed.
            assertThat(note.author()).isEqualTo(NoteAuthor.NONE);
            assertThat(note.author().author()).isEqualTo(CurrentUser.SERVICE_ACCOUNT_NAME);
        }

        @Test
        @DisplayName("leaves a claim touched inside the window alone, and writes no note")
        void leavesAFreshClaimAlone() {
            Referral held = storedClaim(JUDGE, CLAIM_EXPIRY.minusSeconds(30));
            rowsInTheDatabase(held);

            assertThat(service.reclaimStaleClaims()).isZero();

            assertThat(held.getState()).isEqualTo(ReferralState.Claimed);
            assertThat(held.getClaimedBy()).isEqualTo("District Judge Bell");
            verifyNoInteractions(notes);
            verify(referrals, never()).releaseStaleClaim(any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("reads the window from configuration rather than assuming two hours")
        void readsTheWindowFromConfiguration() {
            Referral held = storedClaim(JUDGE, Duration.ofMinutes(45));
            rowsInTheDatabase(held);

            // Untouched for 45 minutes: inside the default two hours and well outside a configured half hour.
            // The same row, swept by two differently configured services, is the claim that the window is not
            // hard-coded -- and it is why correcting the guess is a deployment value rather than a release.
            assertThat(service.reclaimStaleClaims()).isZero();
            assertThat(held.getState()).isEqualTo(ReferralState.Claimed);

            assertThat(serviceWithClaimExpiry(Duration.ofMinutes(30)).reclaimStaleClaims()).isEqualTo(1);
            assertThat(held.getState()).isEqualTo(ReferralState.Open);
        }

        @Test
        @DisplayName("asks for candidates as of now minus the window")
        void asksForCandidatesAsOfNowMinusTheWindow() {
            LocalDateTime before = AuditClock.now();

            service.reclaimStaleClaims();

            // The arithmetic on its own, so a service that swept with the right rows for the wrong reason --
            // an absolute date, or a window applied the other way round -- fails here rather than passing
            // every test whose fixture happens to be far enough in the past.
            assertThat(cutoffAsked())
                .isBetween(before.minus(CLAIM_EXPIRY), AuditClock.now().minus(CLAIM_EXPIRY));
        }

        @Test
        @DisplayName("releases each candidate against the same cutoff it was found with")
        void releasesAgainstTheSameCutoff() {
            rowsInTheDatabase(storedClaim(JUDGE, CLAIM_EXPIRY.plusMinutes(1)));

            service.reclaimStaleClaims();

            ArgumentCaptor<LocalDateTime> released = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(referrals).releaseStaleClaim(eq(REFERRAL_ID), released.capture(), any(), anyString());
            // Recomputing it per row would let a candidate found under one cutoff be released under a later
            // one -- and, an instant either side of the window, release a claim that no longer qualified.
            assertThat(released.getValue()).isEqualTo(cutoffAsked());
        }

        @Test
        @DisplayName("writes no note when the release changed nothing, because another replica got there")
        void writesNoNoteWhenTheReleaseChangedNothing() {
            storedClaim(OTHER_JUDGE, CLAIM_EXPIRY.plusMinutes(1));
            when(referrals.findStaleClaims(eq(ReferralState.Claimed), any()))
                .thenReturn(List.of(new StaleClaim(REFERRAL_ID, CASE_ID, OTHER_JUDGE.name())));
            when(referrals.releaseStaleClaim(any(), any(), any(), anyString())).thenReturn(0);

            // The multi-replica case, which is the whole reason the release is conditional: two pool loads at
            // the same instant both see the candidate, one statement changes the row, and exactly one note is
            // written -- so the count and the note both follow the rows-affected rather than the candidate.
            assertThat(service.reclaimStaleClaims()).isZero();

            verifyNoInteractions(notes);
        }

        @Test
        @DisplayName("sweeps every case at once, one note each, and reports how many it released")
        void sweepsEveryCaseAtOnce() {
            Referral lapsedHere = storedClaim(OTHER_JUDGE, CLAIM_EXPIRY.plusMinutes(5));
            Referral lapsedElsewhere =
                storedClaimOn(OTHER_CASE_ID, SECOND_REFERRAL_ID, JUDGE, CLAIM_EXPIRY.plusHours(9));
            Referral stillHeld = storedClaimOn("case-3", UNKNOWN_ID, JUDGE, Duration.ofMinutes(1));
            rowsInTheDatabase(lapsedHere, lapsedElsewhere, stillHeld);

            assertThat(service.reclaimStaleClaims()).isEqualTo(2);

            assertThat(lapsedHere.getState()).isEqualTo(ReferralState.Open);
            assertThat(lapsedElsewhere.getState()).isEqualTo(ReferralState.Open);
            assertThat(stillHeld.getState()).isEqualTo(ReferralState.Claimed);
            // One note per lapse, each on its own case: the sweep is not scoped to a case, and a note written
            // on the wrong one would be invisible to the caseworkers who need it.
            verify(notes).createNote(eq(CASE_ID), any(), eq(NoteAuthor.NONE));
            verify(notes).createNote(eq(OTHER_CASE_ID), any(), eq(NoteAuthor.NONE));
            verify(notes, times(2)).createNote(anyString(), any(), any());
        }

        @Test
        @DisplayName("does nothing at all when no claim has lapsed")
        void doesNothingWhenNoClaimHasLapsed() {
            assertThat(service.reclaimStaleClaims()).isZero();

            // The ordinary case, and the one every pool load pays for: a sweep with nothing to do costs one
            // query and writes nothing.
            verify(referrals, never()).releaseStaleClaim(any(), any(), any(), anyString());
            verify(referrals, never()).save(any());
            verifyNoInteractions(notes);
        }

        @Test
        @DisplayName("refreshes the case columns of exactly the cases it released, in one set-based statement")
        void refreshesTheColumnsOfTheCasesItReleased() {
            Referral lapsedHere = storedClaim(OTHER_JUDGE, CLAIM_EXPIRY.plusMinutes(5));
            Referral lapsedElsewhere =
                storedClaimOn(OTHER_CASE_ID, SECOND_REFERRAL_ID, JUDGE, CLAIM_EXPIRY.plusHours(9));
            Referral stillHeld = storedClaimOn("case-3", UNKNOWN_ID, JUDGE, Duration.ofMinutes(1));
            rowsInTheDatabase(lapsedHere, lapsedElsewhere, stillHeld);

            assertThat(service.reclaimStaleClaims()).isEqualTo(2);

            // One statement for the whole sweep rather than a recompute per row: the sweep's argument is that a
            // single statement settles a multi-replica race without a lock, and a read-model carried by a loop
            // behind it would be only as atomic as the loop.
            assertThat(casesRefreshed()).containsExactly(CASE_ID, OTHER_CASE_ID);
            verify(deedPolls, never()).writeCurrentReferralState(anyString(), any());
            // The case whose judge is still working is not in the set: its column already reads Claimed, and
            // rewriting it would be a write to a row this call did not change.
            assertThat(casesRefreshed()).doesNotContain("case-3");
        }

        @Test
        @DisplayName("asks the set-based recompute about the non-terminal states, derived from the enum")
        void refreshesAgainstTheNonTerminalStates() {
            Referral lapsed = storedClaim(OTHER_JUDGE, CLAIM_EXPIRY.plusMinutes(5));
            rowsInTheDatabase(lapsed);

            service.reclaimStaleClaims();

            // The same derivation the one-live-referral guard uses. Passed to the statement rather than spelled
            // out in JPQL so that "in play" has one definition -- ReferralState.isTerminal() -- and a sixth
            // state is covered by the recompute the moment it is added.
            assertThat(liveStatesAsked())
                .containsExactlyInAnyOrder(ReferralState.Open, ReferralState.Claimed, ReferralState.Decided);
        }

        @Test
        @DisplayName("writes no case column at all when it released nothing")
        void refreshesNothingWhenItReleasedNothing() {
            Referral fresh = storedClaim(JUDGE, Duration.ofMinutes(1));
            rowsInTheDatabase(fresh);

            assertThat(service.reclaimStaleClaims()).isZero();

            // The ordinary pool load. A sweep with nothing to do must not touch the case table, or the most
            // polled judicial page would write to every case that has ever held a claim.
            verifyNoInteractions(deedPolls);
        }

        /** The cases the one set-based statement was asked to recompute. */
        private Collection<String> casesRefreshed() {
            return refreshAsked().deedPollIds();
        }

        /** The states the set-based statement was told count as "in play". */
        private Collection<ReferralState> liveStatesAsked() {
            return refreshAsked().liveStates();
        }

        /**
         * The arguments of the sweep's single recompute, asserting on the way that there was exactly one.
         */
        @SuppressWarnings("unchecked")
        private RefreshAsked refreshAsked() {
            ArgumentCaptor<Collection<String>> cases = ArgumentCaptor.forClass(Collection.class);
            ArgumentCaptor<Collection<ReferralState>> live = ArgumentCaptor.forClass(Collection.class);
            verify(deedPolls).refreshCurrentReferralStates(cases.capture(), live.capture());
            return new RefreshAsked(cases.getValue(), live.getValue());
        }
    }

    private record RefreshAsked(Collection<String> deedPollIds, Collection<ReferralState> liveStates) {
    }

    @Nested
    @DisplayName("the case's current referral state")
    class CurrentReferralStateColumn {

        /** A referral of another case, for the questions about the recompute reading one case only. */
        private static final UUID OTHER_CASES_REFERRAL_ID =
            UUID.fromString("66666666-6666-6666-6666-666666666666");

        @Test
        @DisplayName("reads Open once a referral is raised")
        void followsACreate() {
            storedCase(CASE_ID);

            service.createReferral(CASE_ID, minimal(), CASEWORKER);

            assertThat(caseColumnWritten(CASE_ID)).isEqualTo(ReferralState.Open);
        }

        @Test
        @DisplayName("reads Claimed once a judge picks the referral up")
        void followsAClaim() {
            stored(ReferralState.Open);

            service.claim(CASE_ID, REFERRAL_ID.toString(), JUDGE);

            assertThat(caseColumnWritten(CASE_ID)).isEqualTo(ReferralState.Claimed);
        }

        @Test
        @DisplayName("reads Decided once the judge records a direction -- the state the dashboard counts")
        void followsADecision() {
            storedClaim(JUDGE, Duration.ofMinutes(10));

            service.decide(CASE_ID, REFERRAL_ID.toString(), approval(), JUDGE);

            // The whole reason the column exists: "Returned -- action needed" is a filtered case list, and this
            // write is what puts the case in it.
            assertThat(caseColumnWritten(CASE_ID)).isEqualTo(ReferralState.Decided);
        }

        @Test
        @DisplayName("goes back to null on close, rather than reading Closed for good")
        void followsAClose() {
            stored(ReferralState.Decided);

            service.close(CASE_ID, REFERRAL_ID.toString(), CASEWORKER);

            // The mistake a per-site push makes. Closed is terminal, so the case has no referral in play and the
            // honest answer is null -- a case left reading Closed would sit in the "action needed" queue after
            // the caseworker actioned it, and every count drawn from the column would be wrong.
            assertThat(caseColumnWritten(CASE_ID)).isNull();
        }

        @Test
        @DisplayName("goes back to null on withdraw, so the case is referable again")
        void followsAWithdrawal() {
            stored(ReferralState.Open);

            service.withdrawReferral(CASE_ID, REFERRAL_ID.toString(), CASEWORKER);

            assertThat(caseColumnWritten(CASE_ID)).isNull();
        }

        @Test
        @DisplayName("reads Open again for a case referred a second time, ignoring its terminal history")
        void ignoresTerminalHistoryWhenACaseIsReferredAgain() {
            storedCase(CASE_ID);
            history(ReferralState.Withdrawn);
            history(ReferralState.Closed);

            service.createReferral(CASE_ID, minimal(), CASEWORKER);

            // The recompute reads every referral of the case and takes the one still in play, so a case with a
            // long history reads the state of its new referral rather than of its last finished one.
            assertThat(caseColumnWritten(CASE_ID)).isEqualTo(ReferralState.Open);
        }

        @Test
        @DisplayName("is null after withdrawing the live one even when the case holds finished referrals")
        void isNullWhenOnlyTerminalReferralsRemain() {
            stored(ReferralState.Claimed);
            history(ReferralState.Closed);

            service.withdrawReferral(CASE_ID, REFERRAL_ID.toString(), CASEWORKER);

            assertThat(caseColumnWritten(CASE_ID)).isNull();
        }

        @Test
        @DisplayName("reads only the addressed case's referrals, never another case's live one")
        void readsOnlyTheAddressedCasesReferrals() {
            stored(ReferralState.Open);
            // Another case with a referral in play at the same time, which is the ordinary state of the service.
            storedClaimOn(OTHER_CASE_ID, OTHER_CASES_REFERRAL_ID, JUDGE, Duration.ofMinutes(1));

            service.withdrawReferral(CASE_ID, REFERRAL_ID.toString(), CASEWORKER);

            // Scoped to the case: a recompute reading across cases would answer Claimed here and put a case with
            // no referral into the judicial pool's counts.
            verify(referrals).findStatesByDeedPollId(CASE_ID);
            verify(referrals, never()).findStatesByDeedPollId(OTHER_CASE_ID);
            assertThat(caseColumnWritten(CASE_ID)).isNull();
        }

        @Test
        @DisplayName("is not written by touch, which moves lastTouchedAt and not the state")
        void isNotWrittenByTouch() {
            storedClaim(JUDGE, Duration.ofMinutes(30));

            service.touch(CASE_ID, REFERRAL_ID.toString(), JUDGE);

            // The seventh mutating method, and the one transition list this deliberately leaves out: a keepalive
            // changes no state, so recomputing the column would be a write to a case row on every judicial read.
            verifyNoInteractions(deedPolls);
        }

        @Test
        @DisplayName("survives the sweep taking a lapsed claim back inside the same claim call")
        void endsAtClaimedWhenTheSweepRanFirst() {
            Referral lapsed = storedClaim(OTHER_JUDGE, CLAIM_EXPIRY.plusMinutes(5));
            rowsInTheDatabase(lapsed);

            service.claim(CASE_ID, REFERRAL_ID.toString(), JUDGE);

            // claim() sweeps before it reads, so this case's column is refreshed twice in one transaction: the
            // set-based statement takes it to Open, and the claim's own recompute takes it on to Claimed. The
            // order matters, and getting it wrong would leave a claimed referral's case reading Open.
            assertThat(lapsed.getState()).isEqualTo(ReferralState.Claimed);
            assertThat(caseColumnWritten(CASE_ID)).isEqualTo(ReferralState.Claimed);
        }

        @Test
        @DisplayName("is not written when the transition was refused")
        void isNotWrittenWhenTheTransitionWasRefused() {
            stored(ReferralState.Closed);

            // Three refusals, each of which leaves the referral table as it was: closing a terminal referral,
            // withdrawing one, and claiming one. A recompute after a refused transition would be harmless today
            // and a write to a case row for every mistyped click.
            assertThatThrownBy(() -> service.close(CASE_ID, REFERRAL_ID.toString(), CASEWORKER))
                .isInstanceOf(ConflictError.class);
            assertThatThrownBy(() -> service.withdrawReferral(CASE_ID, REFERRAL_ID.toString(), CASEWORKER))
                .isInstanceOf(ConflictError.class);
            assertThatThrownBy(() -> service.claim(CASE_ID, REFERRAL_ID.toString(), JUDGE))
                .isInstanceOf(ConflictError.class);

            verify(deedPolls, never()).writeCurrentReferralState(anyString(), any());
        }

        @Test
        @DisplayName("is not written when the case does not exist")
        void isNotWrittenForACaseThatDoesNotExist() {
            assertThatThrownBy(() -> service.createReferral(CASE_ID, minimal(), CASEWORKER))
                .isInstanceOf(NotFoundError.class);

            verify(deedPolls, never()).writeCurrentReferralState(anyString(), any());
        }

        /**
         * A finished referral in the case's history: on the case, in the fake database, and not addressable.
         *
         * <p>Not stubbed into {@code findByIdAndDeedPollId}, because history is what the recompute reads past
         * rather than what a transition acts on.
         */
        private void history(ReferralState state) {
            DeedPoll parent = new DeedPoll();
            parent.setId(CASE_ID);
            Referral referral = new Referral();
            referral.setId(UUID.randomUUID());
            referral.setDeedPoll(parent);
            referral.setState(state);
            rows.add(referral);
        }
    }
}
