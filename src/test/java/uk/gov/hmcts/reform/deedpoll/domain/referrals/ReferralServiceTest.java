package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.notes.CreateNoteInput;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteAuthor;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteForm;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

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

    private DeedPollRepository deedPolls;
    private ReferralRepository referrals;
    private NoteService notes;
    private ReferralService service;

    @BeforeEach
    void createService() {
        deedPolls = mock(DeedPollRepository.class);
        referrals = mock(ReferralRepository.class);
        notes = mock(NoteService.class);
        service = new ReferralService(deedPolls, referrals, notes);

        // The repository hands back what it was given, as a real save does once the id is allocated.
        when(referrals.save(any())).thenAnswer(call -> call.getArgument(0));
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
}
