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
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;
import uk.gov.hmcts.reform.deedpoll.repository.StaleClaim;

import java.time.Duration;
import java.time.LocalDateTime;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Raising, reading and withdrawing a referral, with no database and no HTTP.
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
 * <p>The note service is mocked for the same reason: the claim is that create and withdraw each write one
 * note attributed to the acting caseworker, and the author is an argument rather than something the stored
 * note distinguishes from a service-account write except by its text.
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

    @BeforeEach
    void createService() {
        deedPolls = mock(DeedPollRepository.class);
        referrals = mock(ReferralRepository.class);
        notes = mock(NoteService.class);
        service = serviceWithClaimExpiry(CLAIM_EXPIRY);

        // The repository hands back what it was given, as a real save does once the id is allocated.
        when(referrals.save(any())).thenAnswer(call -> call.getArgument(0));
        // No stale claims unless a test says otherwise, so the sweep at the top of claim() is a no-op.
        when(referrals.findStaleClaims(any(), any())).thenReturn(List.of());
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
        @DisplayName("withdraws a referral a judge is already holding")
        void withdrawsAReferralAJudgeIsAlreadyHolding() {
            // The guard is written against "not terminal" rather than "== Open", so the states the directions
            // plan adds need no change here. Claimed is not reachable yet, which is exactly why the guard has
            // to be right about it before anything can produce one.
            stored(ReferralState.Claimed);

            assertThatCode(() -> service.withdrawReferral(CASE_ID, REFERRAL_ID.toString(), CASEWORKER))
                .doesNotThrowAnyException();

            assertThat(saved().getState()).isEqualTo(ReferralState.Withdrawn);
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Closed", "Withdrawn"})
        @DisplayName("409s a referral that is already terminal, and leaves it as it was")
        void refusesATerminalReferral(ReferralState terminal) {
            Referral referral = stored(terminal);

            assertThatThrownBy(() -> service.withdrawReferral(CASE_ID, REFERRAL_ID.toString(), CASEWORKER))
                .isInstanceOf(ConflictError.class)
                .hasMessageContaining(terminal.name());

            // Not idempotent-and-silent, and not a rewrite: a closed referral must not be turned into a
            // withdrawn one, which would erase the fact that a direction was actioned.
            assertThat(referral.getState()).isEqualTo(terminal);
            verify(referrals, never()).save(any());
            verifyNoInteractions(notes);
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
            // And the case says the first judge's claim lapsed, so the timeline explains the handover.
            assertThat(noteWritten().text()).contains("District Judge Okafor", "lapsed after inactivity");
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
        @DisplayName("writes no case note, because the claim is on the referral's own row")
        void writesNoCaseNote() {
            stored(ReferralState.Open);

            service.claim(CASE_ID, REFERRAL_ID.toString(), JUDGE);

            // claimedBy and claimedAt say who has it, so a note would be a second copy of a recorded fact.
            // A lapse is different -- it erases the fact -- which is why only the sweep writes one.
            verifyNoInteractions(notes);
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
    }
}
