package uk.gov.hmcts.reform.deedpoll.config;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.controller.CaseworkerReferralController;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.CreateReferralInput;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralProperties;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralSchemas;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What the referral resource refuses, and what it deliberately does not, with no database and no HTTP.
 *
 * <p><strong>The three properties this class exists for are the plan's:</strong> namespace separation (the
 * citizen surface stays empty of referrals), a caller with no roles is still a caseworker and may refer, and a
 * referral of one case is never reachable through another case's path. Each is asserted <em>through
 * {@link ReferralService} or over the declarations that constrain it</em> rather than over HTTP, which is the
 * gap between this class and {@code CaseworkerReferralControllerTest}: that class proves which status code each
 * refusal becomes, against a real token and a real database, and is excluded on the Jenkins agent --
 * {@code INTEGRATION_TESTS_ENABLED=false}, because it has no Docker. These are the same refusals stated where
 * CI runs them, following {@code SavedQuerySecurityTest}.
 *
 * <p><strong>The absence of a role gate is asserted, not assumed.</strong> Decision 18: there is no "may refer"
 * permission, because in the process this replaces anybody who works a case can email a Master. A test suite
 * that only ever checked refusals would let somebody add a gate here and see nothing go red, so every one of
 * the four operations is exercised by a caller holding no roles at all, and by an administrator alongside --
 * two callers, because a single one cannot tell "no rule" from "a rule this caller happens to satisfy".
 *
 * <p><strong>The scoping claims are made twice on purpose.</strong> {@code ReferralServiceTest} states them as
 * behaviour; here they are stated over the declarations that make the behaviour unavoidable -- the repository
 * declares no finder by referral id alone, and no handler addresses a referral without also taking the case id
 * -- because behaviour can be re-implemented and a missing scope is then one forgotten argument away.
 */
class ReferralSecurityTest {

    private static final String CITIZEN = "uk.gov.hmcts.reform.deedpoll.api.citizen";

    private static final String CASE_ID = "case-1";

    /** A real case that is not the referral's parent: the other end of every cross-case attempt. */
    private static final String OTHER_CASE_ID = "case-2";

    private static final UUID REFERRAL_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    /**
     * The two statements of the inactivity sweep, which are the only ones on {@code ReferralRepository} that
     * name no parent case -- because a sweep is about every case at once and there is no case to name.
     */
    private static final Set<String> SWEEP = Set.of("findStaleClaims", "releaseStaleClaim");

    /** A caller with no role this service asks about: the ordinary caseworker, and the fail-closed case. */
    private static final CurrentUser NO_ROLES = CurrentUser.currentUser(List.of(), "Otto Other", "other-1");

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
        service = new ReferralService(deedPolls, referrals, notes,
                                      new ReferralProperties(Duration.ofHours(2)));

        when(referrals.save(any())).thenAnswer(call -> call.getArgument(0));
        when(referrals.findStaleClaims(any(), any())).thenReturn(List.of());
    }

    /** Both cases exist, so no refusal below is the parent check answering instead of the rule under test. */
    private void bothCasesExist() {
        for (String id : List.of(CASE_ID, OTHER_CASE_ID)) {
            DeedPoll deedPoll = new DeedPoll();
            deedPoll.setId(id);
            when(deedPolls.findById(id)).thenReturn(Optional.of(deedPoll));
        }
    }

    /**
     * A referral of {@link #CASE_ID}, stored where only that case's id can reach it.
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

    private static CreateReferralInput minimal() {
        return ReferralSchemas.parseCreateReferral(Map.of("reasons", List.of("Minor")));
    }

    @Nested
    @DisplayName("namespace separation")
    class NamespaceSeparation {

        private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("uk.gov.hmcts.reform.deedpoll");

        @Test
        @DisplayName("every referral route is a caseworker route nested under a case, by its mapping")
        void everyRouteIsACaseworkerRouteUnderACase() {
            RequestMapping mapping = CaseworkerReferralController.class.getAnnotation(RequestMapping.class);

            // Both halves matter. Outside /caseworker the routes would still be authenticated, and
            // authenticated for the wrong audience; outside /cases/{id} they would be a flat referral resource,
            // and the scoping rule would have nowhere to come from -- the parent id is the whole of it.
            assertThat(mapping).isNotNull();
            assertThat(mapping.value()).containsExactly("/caseworker/cases/{id}/referrals");
        }

        /**
         * Vacuous today and deliberately written anyway, exactly as {@link NamespaceSeparationSecurityTest}
         * says of its three rules: the citizen namespace is empty until a later plan, so the rule checks an
         * empty set and becomes load-bearing the moment a class is added to it. The point of writing it now is
         * that nobody has to remember to.
         */
        @Test
        @DisplayName("no citizen class reaches the referral domain, its entity or its DTOs")
        void noCitizenClassReachesReferrals() {
            // Wider than the DTO rule in NamespaceSeparationSecurityTest, and deliberately: a referral
            // publishes the caseworkers who raised and moved it, free text written for the judiciary, and an
            // urgency the applicant was never told about. A citizen route that reused the domain service would
            // inherit "any authenticated caller may refer, read and withdraw" -- a rule written about staff.
            noClasses()
                .that().resideInAPackage(CITIZEN + "..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("uk.gov.hmcts.reform.deedpoll.domain.referrals..")
                .orShould().dependOnClassesThat().haveSimpleNameStartingWith("Referral")
                .orShould().dependOnClassesThat().haveSimpleNameStartingWith("CreateReferral")
                .allowEmptyShould(true)
                .check(CLASSES);
        }

        @Test
        @DisplayName("the controller neither returns nor accepts an entity")
        void theControllerNeitherReturnsNorAcceptsAnEntity() {
            // The stored row carries its parent case, which carries the applicant's every field, so a handler
            // that returned it would publish the whole case through a referral read. Asserted over the mappings
            // rather than read once, so the transitions the directions plan adds have to make the same choice.
            for (Method method : handlers()) {
                assertThat(packageOf(method.getReturnType()))
                    .as("%s does not return an entity", method.getName())
                    .doesNotContain(".entity");
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertThat(packageOf(parameter))
                        .as("%s does not bind an entity", method.getName())
                        .doesNotContain(".entity");
                }
            }
        }

        /** The declaring package of a type, unwrapping a collection's element only as far as the raw type. */
        private static String packageOf(Class<?> type) {
            return type.getPackage() == null ? "" : type.getPackage().getName();
        }
    }

    @Nested
    @DisplayName("a caller with no roles")
    class NoRoles {

        @Test
        @DisplayName("may raise a referral, because referring is not role-gated")
        void mayRaiseAReferral() {
            bothCasesExist();

            // The half a suite of refusals would quietly break: gating this because the note and witness
            // deletes are gated would take a capability away from every caseworker who has it today.
            assertThatCode(() -> service.createReferral(CASE_ID, minimal(), NO_ROLES))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("may list, read and withdraw one too, so the whole resource is open and not just the create")
        void mayListReadAndWithdraw() {
            bothCasesExist();
            Referral referral = stored(ReferralState.Open);
            when(referrals.findByDeedPollIdOrderByCreatedOnDesc(CASE_ID)).thenReturn(List.of(referral));

            assertThatCode(() -> service.findReferrals(CASE_ID)).doesNotThrowAnyException();
            assertThatCode(() -> service.findReferral(CASE_ID, REFERRAL_ID.toString()))
                .doesNotThrowAnyException();
            assertThatCode(() -> service.withdrawReferral(CASE_ID, REFERRAL_ID.toString(), NO_ROLES))
                .doesNotThrowAnyException();

            assertThat(referral.getState()).isEqualTo(ReferralState.Withdrawn);
        }

        @Test
        @DisplayName("is attributed by name, so the caller is used for authorship and not for permission")
        void isAttributedByName() {
            bothCasesExist();
            stored(ReferralState.Open);

            service.createReferral(CASE_ID, minimal(), NO_ROLES);
            service.withdrawReferral(CASE_ID, REFERRAL_ID.toString(), NO_ROLES);

            // Which is why passing a CurrentUser into a service that gates nothing is not a loose end: a name
            // reaching the audit columns and the timeline note is the reason it is there.
            verify(notes, times(2))
                .createNote(anyString(), any(), argThat(author -> "Otto Other".equals(author.author())));
        }

        @Test
        @DisplayName("gets the same answers an administrator does, on all four operations")
        void getsTheSameAnswersAsAnAdministrator() {
            // Two callers, because one cannot tell "no rule" from "a rule this caller happens to satisfy". The
            // saved-query resource has an administrator override; this one deliberately has none, so the pair
            // must agree everywhere -- both permitted below, and both refused in equalsAdminOnATerminalOne.
            bothCasesExist();
            stored(ReferralState.Open);

            assertThatCode(() -> service.createReferral(CASE_ID, minimal(), ADMIN)).doesNotThrowAnyException();
            assertThatCode(() -> service.createReferral(OTHER_CASE_ID, minimal(), NO_ROLES))
                .doesNotThrowAnyException();
            assertThat(service.findReferral(CASE_ID, REFERRAL_ID.toString())).isNotNull();
            assertThatCode(() -> service.withdrawReferral(CASE_ID, REFERRAL_ID.toString(), ADMIN))
                .doesNotThrowAnyException();
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Closed", "Withdrawn"})
        @DisplayName("is refused a terminal referral exactly as an administrator is, and nothing is written")
        void isRefusedATerminalReferralAsAnAdministratorIs(ReferralState terminal) {
            Referral referral = stored(terminal);

            for (CurrentUser caller : List.of(NO_ROLES, ADMIN)) {
                assertThatThrownBy(() -> service.withdrawReferral(CASE_ID, REFERRAL_ID.toString(), caller))
                    .isInstanceOf(ConflictError.class);
            }

            // The refusal has to come before the write, not after it: a 409 that had already withdrawn a closed
            // referral would erase the fact that a direction was actioned, and the status code cannot tell the
            // two apart.
            assertThat(referral.getState()).isEqualTo(terminal);
            verify(referrals, never()).save(any());
            verifyNoInteractions(notes);
        }
    }

    @Nested
    @DisplayName("a referral of one case, reached through another")
    class CrossCaseIsolation {

        @Test
        @DisplayName("is 404 to read, in the same words as an id nothing was stored under")
        void isNotFoundToRead() {
            bothCasesExist();
            stored(ReferralState.Open);

            // Identical to the message an unknown id gets, so the two cannot be told apart and no referral is
            // discoverable by pointing a case you can see at ids you cannot.
            String expected = "Referral " + REFERRAL_ID + " not found";
            assertThatThrownBy(() -> service.findReferral(OTHER_CASE_ID, REFERRAL_ID.toString()))
                .isInstanceOf(NotFoundError.class).hasMessage(expected);

            when(referrals.findByIdAndDeedPollId(REFERRAL_ID, OTHER_CASE_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.findReferral(OTHER_CASE_ID, REFERRAL_ID.toString()))
                .isInstanceOf(NotFoundError.class).hasMessage(expected);
        }

        @Test
        @DisplayName("is 404 to withdraw, and survives untouched")
        void isNotFoundToWithdraw() {
            bothCasesExist();
            Referral referral = stored(ReferralState.Open);

            assertThatThrownBy(() -> service.withdrawReferral(OTHER_CASE_ID, REFERRAL_ID.toString(), ADMIN))
                .isInstanceOf(NotFoundError.class);

            // The assertion that carries the test: the wrong parent must not be able to pull back somebody
            // else's referral, and an administrator is no more able to than anyone else.
            assertThat(referral.getState()).isEqualTo(ReferralState.Open);
            verify(referrals, never()).save(any());
            verifyNoInteractions(notes);
        }

        @Test
        @DisplayName("is not in the other case's list, because the parent id is in the read")
        void isNotInTheOtherCasesList() {
            bothCasesExist();
            stored(ReferralState.Open);
            when(referrals.findByDeedPollIdOrderByCreatedOnDesc(OTHER_CASE_ID)).thenReturn(List.of());

            assertThat(service.findReferrals(OTHER_CASE_ID)).isEmpty();

            // Stated as an interaction as well as an outcome: the case id is in the finder rather than in a
            // filter over its result, so another case's referrals are never loaded at all. A finder taking no
            // parent would have had to be trimmed afterwards, and a trim is a step that can be forgotten.
            verify(referrals).findByDeedPollIdOrderByCreatedOnDesc(OTHER_CASE_ID);
            verify(referrals, never()).findByDeedPollIdOrderByCreatedOnDesc(CASE_ID);
            verify(referrals, never()).findAll();
        }

        @Test
        @DisplayName("could not be addressed even by mistake: no lookup by referral id alone is declared")
        void cannotBeAddressedByIdAlone() {
            // The behavioural half of this rule is in ReferralServiceTest; this is the half that stops it being
            // re-introduced. `findById` is inherited from JpaRepository and cannot be removed, so what is
            // asserted is that this interface declares nothing that reaches a referral without naming its case.
            List<String> declared = Arrays.stream(ReferralRepository.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();
            // The exception is real, so it is named rather than pattern-matched: a third unscoped method has
            // to be admitted here deliberately, and a rename that made this list vacuous fails too.
            assertThat(declared).containsAll(SWEEP);

            for (Method finder : ReferralRepository.class.getDeclaredMethods()) {
                if (SWEEP.contains(finder.getName())) {
                    // What keeps the sweep inside the rule instead of beside it: neither statement hands back
                    // a referral. The candidate query returns a three-field projection and the release returns
                    // a row count, so an unscoped query cannot become an unscoped read of case data.
                    assertThat(finder.getGenericReturnType().getTypeName())
                        .as("%s hands back no referral", finder.getName())
                        .doesNotContain(Referral.class.getName());
                    continue;
                }
                assertThat(finder.getName())
                    .as("%s carries the parent case id", finder.getName())
                    .contains("DeedPollId");
            }
        }

        @Test
        @DisplayName("no handler addresses a referral without also taking the case id")
        void noHandlerAddressesAReferralWithoutTheCaseId() {
            // Where the scope would actually be lost: the repository can be right and a handler still pass the
            // referral id on its own. Every route that names a referral takes both path variables, so the
            // domain is never asked a question that has no parent in it.
            for (Method handler : handlers()) {
                List<String> pathVariables = Arrays.stream(handler.getParameters())
                    .map(parameter -> parameter.getAnnotation(PathVariable.class))
                    .filter(Objects::nonNull)
                    .map(PathVariable::value)
                    .toList();

                assertThat(pathVariables)
                    .as("%s takes the case id", handler.getName())
                    .contains("id");
                if (pathVariables.contains("referralId")) {
                    assertThat(pathVariables)
                        .as("%s scopes the referral to its case", handler.getName())
                        .containsExactly("id", "referralId");
                }
            }
        }

        @Test
        @DisplayName("the parent named in the path is the one the lookup carries, not a guard after it")
        void theParentIsInTheLookup() {
            bothCasesExist();
            stored(ReferralState.Open);

            // The declarations above prove both ids reach the domain; this proves the domain puts the parent
            // into the query rather than loading the referral and comparing afterwards. The difference is
            // invisible in the answer -- both are a 404 -- and it is the difference between a scope and a check.
            service.findReferral(CASE_ID, REFERRAL_ID.toString());

            verify(referrals).findByIdAndDeedPollId(REFERRAL_ID, CASE_ID);
            verify(referrals, never()).findById(any());
        }
    }

    /** The controller's own handler methods, without the synthetic ones the compiler adds. */
    private static List<Method> handlers() {
        return Arrays.stream(CaseworkerReferralController.class.getDeclaredMethods())
            .filter(method -> !method.isSynthetic())
            .toList();
    }
}
