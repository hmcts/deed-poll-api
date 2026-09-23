package uk.gov.hmcts.reform.deedpoll.config;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.jpa.domain.Specification;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CaseDetailResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CaseSummaryResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.ReferralResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.SavedCaseResponse;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseQuery;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseSpecifications;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollFields;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.ListFilters;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ConfirmationInput;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralProperties;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralSchemas;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralService;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ConfirmationOutcome;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
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
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * What the confirmation of an applicant's reply refuses, who it attributes, and what it publishes -- stated
 * where Docker-free CI runs them.
 *
 * <p>{@code CaseworkerReferralControllerTest} proves the same rules over real HTTP against a real Postgres, and
 * CNP excludes that class because the agent has no Docker ({@code INTEGRATION_TESTS_ENABLED=false}). These are
 * the four properties that must not disappear with it, which is the split {@code ReferralSecurityTest} and
 * {@code NotificationSecurityTest} already keep:
 *
 * <ul>
 *   <li><strong>Case scoping.</strong> A referral of one case is not reachable through another's path, and a
 *       recording attempted through the wrong parent writes nothing -- on either case. The second half is new
 *       with the waiting list: a recording now touches a column on the <em>case</em> as well as the referral,
 *       so a cross-case attempt has a second row it must not reach.</li>
 *   <li><strong>Authorship.</strong> {@code confirmationRecordedBy} comes from the token and the date from the
 *       clock, so a body cannot sign an answer as somebody else or backdate one.</li>
 *   <li><strong>Disclosure.</strong> The confirmation columns and the case's wait are caseworker fields: no
 *       judicial or citizen class sees them, and the caseworker DTOs that do publish them are named here so
 *       the rule cannot pass vacuously.</li>
 *   <li><strong>The waiting list narrows, never widens.</strong> Its criterion is a not-null test on the
 *       case's own column with no join and no caller input, and the column is readable but unwritable -- a
 *       caller who could patch it could take a case out of the list nobody had answered.</li>
 * </ul>
 */
class ConfirmationSecurityTest {

    private static final String CASE_ID = "case-1";

    /** A real case that is not the referral's parent: the other end of every cross-case attempt. */
    private static final String OTHER_CASE_ID = "case-2";

    private static final UUID REFERRAL_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    /** When the confirmation email was owed, which is what a recording requires to have happened. */
    private static final LocalDateTime ASKED = LocalDateTime.parse("2026-05-03T09:15:00");

    /** The ordinary caseworker: no role this service asks about, because recording is not role-gated. */
    private static final CurrentUser CASEWORKER =
        CurrentUser.currentUser(List.of(), "Ada Caseworker", "caseworker-1");

    private static final CurrentUser ADMIN =
        CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "An Admin", "admin-2");

    private static final String JUDICIAL = "uk.gov.hmcts.reform.deedpoll.api.judicial";

    private static final String CITIZEN = "uk.gov.hmcts.reform.deedpoll.api.citizen";

    private static final JavaClasses CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("uk.gov.hmcts.reform.deedpoll");

    /**
     * The case column the waiting list is a {@code WHERE} clause over.
     */
    private static final String WAIT_COLUMN = "awaitingConfirmationSince";

    private DeedPollRepository deedPolls;
    private ReferralRepository referrals;
    private NoteService notes;
    private ReferralService service;

    @BeforeEach
    void createService() {
        deedPolls = mock(DeedPollRepository.class);
        referrals = mock(ReferralRepository.class);
        notes = mock(NoteService.class);
        service = new ReferralService(deedPolls, referrals, notes,
                                      new ReferralProperties(Duration.ofHours(2)));

        when(referrals.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    /** Both cases exist, so no refusal below is a missing parent answering instead of the rule under test. */
    private void bothCasesExist() {
        for (String id : List.of(CASE_ID, OTHER_CASE_ID)) {
            DeedPoll deedPoll = new DeedPoll();
            deedPoll.setId(id);
            when(deedPolls.findById(id)).thenReturn(Optional.of(deedPoll));
        }
    }

    /**
     * A referral of {@link #CASE_ID} that was asked to confirm, stored where only that case's id reaches it.
     */
    private Referral asked() {
        DeedPoll parent = new DeedPoll();
        parent.setId(CASE_ID);
        Referral referral = new Referral();
        referral.setId(REFERRAL_ID);
        referral.setDeedPoll(parent);
        referral.setState(ReferralState.Decided);
        referral.setReasons(List.of("NonPublication"));
        referral.setCreatedOn(LocalDateTime.parse("2026-05-01T09:30:00"));
        referral.setModifiedOn(LocalDateTime.parse("2026-05-01T09:30:00"));
        referral.setConfirmationRequestedAt(ASKED);
        when(referrals.findByIdAndDeedPollId(REFERRAL_ID, CASE_ID)).thenReturn(Optional.of(referral));
        return referral;
    }

    /** A confirmation as the schema would have produced one, so no test builds a shape the API could not. */
    private static ConfirmationInput confirmation(String outcome) {
        return ReferralSchemas.parseConfirmation(Map.of("outcome", (Object) outcome));
    }

    @Nested
    @DisplayName("a referral of one case, recorded through another")
    class CrossCaseIsolation {

        @Test
        @DisplayName("is 404 in the same words an unknown id gets, and nothing is written on either case")
        void isNotFoundAndWritesNothing() {
            bothCasesExist();
            Referral referral = asked();

            // Identical to the message an id nothing was stored under gets, so the two cannot be told apart and
            // no referral is discoverable by pointing a case you can see at ids you cannot.
            String expected = "Referral " + REFERRAL_ID + " not found";
            assertThatThrownBy(() -> service.recordConfirmation(
                OTHER_CASE_ID, REFERRAL_ID.toString(), confirmation("Confirmed"), CASEWORKER))
                .isInstanceOf(NotFoundError.class)
                .hasMessage(expected);

            // The referral is untouched: an answer recorded through the wrong parent would be an outcome on
            // somebody else's referral, and this operation is idempotent by refusal precisely because an outcome
            // cannot be corrected once stored.
            assertThat(referral.getConfirmationOutcome()).isNull();
            assertThat(referral.getConfirmationReceivedAt()).isNull();
            assertThat(referral.getConfirmationRecordedBy()).isNull();
            assertThat(referral.getConfirmationRequestedAt()).isEqualTo(ASKED);
            verify(referrals, never()).save(any());
            verifyNoInteractions(notes);

            // And neither case's waiting column moves -- the half that is new with the waiting list. A refused
            // recording that had recomputed the case it was addressed at would take a case out of the list, or
            // put one in it, on the strength of a referral that case does not own.
            verify(deedPolls, never()).writeAwaitingConfirmationSince(anyString(), any());
            verify(referrals, never()).findOutstandingConfirmationRequestsByDeedPollId(anyString());
        }

        @Test
        @DisplayName("does not even report the answer already on it, so a 404 says nothing about its state")
        void doesNotReportAnAnswerAlreadyRecorded() {
            bothCasesExist();
            Referral referral = asked();
            referral.setConfirmationOutcome(ConfirmationOutcome.Confirmed);
            referral.setConfirmationReceivedAt(LocalDateTime.parse("2026-05-10T11:00:00"));
            referral.setConfirmationRecordedBy("Bo Caseworker");

            // 404 and not the 409 the same call would get through the right parent, which is the whole point of
            // the scope being in the lookup: a caller pointing another case at this id learns that the referral
            // is not there, not that somebody has already answered it and when.
            assertThatThrownBy(() -> service.recordConfirmation(
                OTHER_CASE_ID, REFERRAL_ID.toString(), confirmation("Withdrawn"), CASEWORKER))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Referral " + REFERRAL_ID + " not found")
                .hasMessageNotContaining(ConfirmationOutcome.Confirmed.name())
                .hasMessageNotContaining("Bo Caseworker");

            assertThat(referral.getConfirmationOutcome()).isEqualTo(ConfirmationOutcome.Confirmed);
            assertThat(referral.getConfirmationRecordedBy()).isEqualTo("Bo Caseworker");
            verify(referrals, never()).save(any());
        }

        @Test
        @DisplayName("is refused by the lookup carrying the parent, not by a guard after it")
        void theParentIsInTheLookup() {
            bothCasesExist();
            asked();

            service.recordConfirmation(CASE_ID, REFERRAL_ID.toString(), confirmation("Confirmed"), CASEWORKER);

            // The difference is invisible in the answer -- both are a 404 -- and it is the difference between a
            // scope and a check: a check is one forgotten comparison away from reading another case's referral.
            verify(referrals).findByIdAndDeedPollId(REFERRAL_ID, CASE_ID);
            verify(referrals, never()).findById(any());
            verify(referrals, never()).findWithCaseById(any());
        }

        @Test
        @DisplayName("the recompute names the case from the path, and recomputes no other")
        void theRecomputeIsCaseScoped() {
            bothCasesExist();
            asked();
            when(referrals.findOutstandingConfirmationRequestsByDeedPollId(CASE_ID)).thenReturn(List.of());

            service.recordConfirmation(CASE_ID, REFERRAL_ID.toString(), confirmation("Confirmed"), CASEWORKER);

            // The one write in this operation that is not on the referral. It is recomputed from the referrals of
            // the case in the path and written back to that case alone, so an answer on one case's referral can
            // neither read another case's referrals nor change what another case's list row says.
            verify(referrals).findOutstandingConfirmationRequestsByDeedPollId(CASE_ID);
            verify(referrals, never()).findOutstandingConfirmationRequestsByDeedPollId(OTHER_CASE_ID);
            verify(deedPolls).writeAwaitingConfirmationSince(CASE_ID, null);
            verify(deedPolls, never()).writeAwaitingConfirmationSince(eq(OTHER_CASE_ID), any());
        }
    }

    @Nested
    @DisplayName("who the answer is attributed to")
    class Authorship {

        @Test
        @DisplayName("is the token's name, and a body naming an author is stripped rather than honoured")
        void isTheTokensNameAndNeverTheBodys() {
            bothCasesExist();
            Referral referral = asked();

            // Through the schema, because that is what makes the absence structural rather than a rule to
            // remember: ConfirmationInput has nowhere to put an author, a received date or a request date, so a
            // body carrying all three reduces to the outcome the applicant gave.
            ConfirmationInput input = ReferralSchemas.parseConfirmation(payload(
                "outcome", "Confirmed",
                "confirmationRecordedBy", "Somebody Else",
                "confirmationReceivedAt", "2020-01-01T00:00:00",
                "confirmationRequestedAt", "2020-01-01T00:00:00"));

            // AuditClock and not LocalDateTime.now(): the column is stamped from the UTC clock, so a bare
            // now() would read the JVM's default zone and put this fixture an hour AHEAD of the value under
            // test on any machine where TZ is set east of UTC -- a red build in BST for a rule that is right.
            final LocalDateTime before = AuditClock.now().minusSeconds(1);
            service.recordConfirmation(CASE_ID, REFERRAL_ID.toString(), input, CASEWORKER);

            assertThat(referral.getConfirmationRecordedBy()).isEqualTo("Ada Caseworker");
            assertThat(referral.getModifiedBy()).isEqualTo("Ada Caseworker");
            // From the clock, so a body cannot backdate a reply: the stored date is when the caseworker recorded
            // it, and nothing here knows when the applicant actually sent it.
            assertThat(referral.getConfirmationReceivedAt()).isAfter(before);
            // And the request date is the send's, untouched: a payload that could move it would reset the wait
            // the list exists to make visible.
            assertThat(referral.getConfirmationRequestedAt()).isEqualTo(ASKED);
        }

        @Test
        @DisplayName("is the caseworker on the case note too, not the service account")
        void isTheCaseworkerOnTheNote() {
            bothCasesExist();
            asked();

            service.recordConfirmation(CASE_ID, REFERRAL_ID.toString(), confirmation("Withdrawn"), CASEWORKER);

            verify(notes).createNote(
                eq(CASE_ID),
                any(),
                argThat(author -> "Ada Caseworker".equals(author.author())));
        }

        @ParameterizedTest
        @EnumSource(ConfirmationOutcome.class)
        @DisplayName("is whoever holds the token, administrator or not, because recording is not role-gated")
        void isWhoeverHoldsTheToken(ConfirmationOutcome outcome) {
            bothCasesExist();
            Referral referral = asked();

            // Two callers, because one cannot tell "no rule" from "a rule this caller happens to satisfy", and
            // both outcomes, because they mean opposite things and a gate on one of them would be invisible.
            assertThatCode(() -> service.recordConfirmation(
                CASE_ID, REFERRAL_ID.toString(), confirmation(outcome.name()), ADMIN))
                .doesNotThrowAnyException();

            assertThat(referral.getConfirmationRecordedBy()).isEqualTo("An Admin");
            assertThat(referral.getConfirmationOutcome()).isEqualTo(outcome);
        }

        @Test
        @DisplayName("is a name and not a subject, because nothing compares who recorded an answer")
        void isANameAndNotASubject() {
            // Unlike claimedById and decidedById, which V14 added because the holder check compares them. Nothing
            // compares this one: recording is a caseworker act on a case the caller can already read, so a
            // subject column would be an identifier stored for nobody to use -- and a subject stored is a subject
            // that leaks. Exactly the four columns of the plan's decision 2, asserted over the entity's fields so
            // that a fifth has to be a deliberate change here.
            assertThat(Arrays.stream(Referral.class.getDeclaredFields())
                           .map(Field::getName)
                           .filter(name -> name.startsWith("confirmation"))
                           .toList())
                .containsExactlyInAnyOrder("confirmationRequestedAt", "confirmationOutcome",
                                           "confirmationReceivedAt", "confirmationRecordedBy");
        }

        /** A body as a form would have posted it, with keys this service does not accept among them. */
        private static Map<String, Object> payload(String... pairs) {
            Map<String, Object> raw = new HashMap<>();
            for (int index = 0; index < pairs.length; index += 2) {
                raw.put(pairs[index], pairs[index + 1]);
            }
            return raw;
        }
    }

    @Nested
    @DisplayName("who may see a confirmation")
    class Disclosure {

        @Test
        @DisplayName("no judicial or citizen class depends on the confirmation types at all")
        void noOtherSurfaceDependsOnTheConfirmationTypes() {
            // Wider than a field rule, and deliberately: the answer to "do you still want this published?" is
            // correspondence between the office and the applicant. A judge decided the non-publication request
            // and has no part in what the applicant then chose, and the citizen surface -- empty until a later
            // plan -- must not inherit "any authenticated caller may record one", which is a rule about staff.
            noClasses()
                .that().resideInAnyPackage(JUDICIAL + "..", CITIZEN + "..")
                .should().dependOnClassesThat().haveSimpleNameStartingWith("Confirmation")
                .orShould().dependOnClassesThat().haveSimpleNameStartingWith("RecordConfirmation")
                .allowEmptyShould(true)
                .check(CLASSES);
        }

        @Test
        @DisplayName("no judicial or citizen type declares a confirmation or waiting field")
        void noOtherSurfacePublishesAConfirmationField() {
            // The dependency rule above cannot catch a String field called confirmationRecordedBy or a
            // LocalDateTime called awaitingConfirmationSince: neither names a type this service owns. A judicial
            // DTO is a record, so its components are its fields, and this is the rule that keeps the read-through
            // to a case's papers from growing one.
            String forbidden = "(?i).*confirmation.*";

            // The pattern matches the names it is written about, asserted before the rule that uses it: a regex
            // that matched nothing would make the rule below pass whatever a judicial DTO published.
            assertThat(List.of("confirmationRecordedBy", WAIT_COLUMN, "daysAwaitingConfirmation"))
                .allMatch(name -> name.matches(forbidden));

            noFields()
                .that().areDeclaredInClassesThat().resideInAnyPackage(JUDICIAL + "..", CITIZEN + "..")
                .should().haveNameMatching(forbidden)
                .allowEmptyShould(true)
                .check(CLASSES);
        }

        @Test
        @DisplayName("the caseworker DTOs that do publish them are named, so the rules above are not vacuous")
        void theCaseworkerDtosPublishThem() {
            // The counterpart. Two rules that only ever said "nobody publishes this" would stay green if the
            // columns were published nowhere at all, which is a different service: the referrals tab needs the
            // four columns, the case needs to say since when, and the list row needs to say how long.
            assertThat(componentsOf(ReferralResponse.class))
                .contains("confirmationRequestedAt", "confirmationOutcome", "confirmationReceivedAt",
                          "confirmationRecordedBy");
            assertThat(componentsOf(CaseDetailResponse.class)).contains(WAIT_COLUMN);
            assertThat(componentsOf(SavedCaseResponse.class)).contains(WAIT_COLUMN);

            // The row says how long and the case says since when, which is the split the two DTOs keep: a count
            // of days on the case read would be a number that was true when it was serialised.
            assertThat(componentsOf(CaseSummaryResponse.class))
                .contains("daysAwaitingConfirmation")
                .doesNotContain(WAIT_COLUMN);
        }

        private static List<String> componentsOf(Class<?> type) {
            RecordComponent[] components = type.getRecordComponents();
            assertThat(components).as("%s is a record", type.getSimpleName()).isNotNull();
            return Arrays.stream(components).map(RecordComponent::getName).toList();
        }
    }

    @Nested
    @DisplayName("the waiting list")
    class WaitingList {

        @Test
        @DisplayName("narrows the case list by the case's own column, with no join and no subquery")
        @SuppressWarnings("unchecked")
        void narrowsByTheCasesOwnColumn() {
            Root<DeedPoll> root = mock(Root.class);
            CriteriaQuery<?> query = mock(CriteriaQuery.class);
            CriteriaBuilder cb = mock(CriteriaBuilder.class);
            Path<Object> column = mock(Path.class);
            Predicate isNotNull = mock(Predicate.class);
            when(root.get(WAIT_COLUMN)).thenReturn(column);
            when(cb.isNotNull(column)).thenReturn(isNotNull);

            Specification<DeedPoll> criterion =
                CaseSpecifications.criterion(new ListFilters.AwaitingConfirmation());

            assertThat(criterion.toPredicate(root, query, cb)).isSameAs(isNotNull);

            // One column test and nothing else, asserted as the absence of every other interaction. A join onto
            // the referral table would multiply a case by its referrals and make the page disagree with its own
            // count; a correlated subquery would read referrals the page's case does not own. Both are ruled out
            // by the criterion never asking the root or the query for anything but this one column.
            verify(root).get(WAIT_COLUMN);
            verifyNoMoreInteractions(root);
            verify(cb).isNotNull(column);
            verifyNoMoreInteractions(cb);
            verifyNoInteractions(query);
        }

        @Test
        @DisplayName("takes no input, so nothing a caller sends chooses which cases come back")
        void takesNoInput() throws Exception {
            // The unpaged path the saved queries use, which is a named finder rather than a specification. It
            // takes no argument at all: the tab is the whole question, so there is no bound, no owner and no case
            // id a caller could supply to widen it.
            Method finder = DeedPollRepository.class.getMethod(
                "findByAwaitingConfirmationSinceIsNotNullOrderByAwaitingConfirmationSinceAsc");
            assertThat(finder.getParameterCount()).isZero();

            // And the only other statement about the column is the recompute's write, which names one case.
            List<Method> touching = Arrays.stream(DeedPollRepository.class.getDeclaredMethods())
                .filter(method -> method.getName().toLowerCase(Locale.ROOT).contains("awaiting"))
                .toList();
            assertThat(touching).extracting(Method::getName)
                .containsExactlyInAnyOrder(
                    "findByAwaitingConfirmationSinceIsNotNullOrderByAwaitingConfirmationSinceAsc",
                    "writeAwaitingConfirmationSince");
            assertThat(DeedPollRepository.class
                           .getMethod("writeAwaitingConfirmationSince", String.class, LocalDateTime.class)
                           .getParameterTypes())
                .as("the write names the case it is about")
                .containsExactly(String.class, LocalDateTime.class);
        }

        @Test
        @DisplayName("reads the referrals of one named case, and hands back dates rather than referrals")
        void recomputesFromOneCasesReferrals() throws Exception {
            // The read the recompute does. It carries the parent case id like every other caseworker finder, and
            // it projects the dates: an unscoped or referral-returning version of this would be a cross-case read
            // of referrals reached from a case list nobody role-gated.
            Method outstanding = ReferralRepository.class.getMethod(
                "findOutstandingConfirmationRequestsByDeedPollId", String.class);
            assertThat(outstanding.getName()).contains("DeedPollId");
            assertThat(outstanding.getGenericReturnType().getTypeName())
                .as("the recompute reads dates, never referrals")
                .contains(LocalDateTime.class.getName())
                .doesNotContain(Referral.class.getName());
        }

        @Test
        @DisplayName("is readable but not writable, so no caller can leave the list without answering")
        void isReadableButNotWritable() {
            // The read-model rule currentReferralState already has, and the reason is sharper here: a caller who
            // could patch this column could take a case out of the waiting list although nobody answered, or put
            // one into it that was never asked -- which is the single failure the list exists to prevent.
            assertThat(DeedPollFields.readableFields()).contains(WAIT_COLUMN);
            assertThat(DeedPollFields.isWritable(WAIT_COLUMN)).isFalse();
            assertThatThrownBy(() -> DeedPollFields.write(new DeedPoll(), WAIT_COLUMN, ASKED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(WAIT_COLUMN);
        }

        @Test
        @DisplayName("is asked about through the tab and not through where, and refuses the condition by name")
        void isAskedAboutThroughTheTab() {
            // Not a form field, so not queryable -- the decision the reader of DeedPollFields' registry is told
            // about, pinned here. The tab is how the question is asked, and a `where` on the column would be a
            // second way to ask it with its own arithmetic about what "waiting" means.
            assertThatThrownBy(() -> CaseQuery.parse(List.of(WAIT_COLUMN + ":isEmpty")))
                .isInstanceOf(InvalidInputError.class);

            assertThat(ListFilters.filterFor("awaitingConfirmation").where())
                .as("the tab is the one that carries the criterion")
                .contains(new ListFilters.AwaitingConfirmation());
            assertThat(ListFilters.filterFor("awaitingConfirmation").defaultSort())
                .contains(ListFilters.LONGEST_WAIT_FIRST);
        }
    }
}
