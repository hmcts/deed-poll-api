package uk.gov.hmcts.reform.deedpoll.config;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.controller.CaseworkerReferralController;
import uk.gov.hmcts.reform.deedpoll.api.judicial.controller.JudicialReferralController;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.IdamClaims;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.CreateReferralInput;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.DirectionInput;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.JudicialReferralService;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralProperties;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralSchemas;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
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
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
 * <p><strong>The four properties this class exists for are the plan's:</strong> namespace separation (the
 * citizen surface stays empty of referrals), a caller with no roles is still a caseworker and may refer, the
 * judicial transitions fail closed for every token IDAM issues today, and a referral of one case is never
 * reachable through another case's path -- closing it included. Each is asserted <em>through
 * {@link ReferralService} or over the declarations that constrain it</em> rather than over HTTP, which is the
 * gap between this class and {@code CaseworkerReferralControllerTest}: that class proves which status code each
 * refusal becomes, against a real token and a real database, and is excluded on the Jenkins agent --
 * {@code INTEGRATION_TESTS_ENABLED=false}, because it has no Docker. These are the same refusals stated where
 * CI runs them, following {@code SavedQuerySecurityTest}.
 *
 * <p><strong>The absence of a role gate is asserted, not assumed.</strong> Decision 18: there is no "may refer"
 * permission and no "may close" one, because in the process this replaces anybody who works a case can email a
 * Master and action the reply. A test suite that only ever checked refusals would let somebody add a gate here
 * and see nothing go red, so every one of the five caseworker operations is exercised by a caller holding no
 * roles at all, and by an administrator alongside -- two callers, because a single one cannot tell "no rule"
 * from "a rule this caller happens to satisfy".
 *
 * <p><strong>The judicial gate is the one refusal this resource does have, and it is asserted three ways.</strong>
 * {@code claim}, {@code touch} and {@code decide} demand {@link Role#JUDICIAL}; every other caller is refused,
 * an administrator included, before the case or the referral is looked up at all. It is asserted over all three
 * transitions from one list rather than one test each, so a fourth judicial transition is gated by being added
 * to that list rather than by somebody remembering to copy a test; and it is asserted from the token end too --
 * a claims document naming a near-miss role string reduces to no roles, which is what makes "fails closed for
 * every token IDAM currently issues" a statement about the reduction rather than a hope about IDAM. The
 * transitions have no route on any controller yet, which is checked here as well: a gate on a surface nobody can
 * reach is safe, and a route added ahead of the judicial-surface plan must not inherit the caseworker surface's
 * "any authenticated caller" reading by accident.
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
     * The two statements of the inactivity sweep, which name no parent case -- because a sweep is about every
     * case at once and there is no case to name.
     */
    private static final Set<String> SWEEP = Set.of("findStaleClaims", "releaseStaleClaim");

    /**
     * The judicial pool, which names no parent case either -- because the shared pool <em>is</em> the cross-case
     * list, and a judge reaches it without naming any case, which is what stops them naming one at all.
     *
     * <p>Listed separately from {@link #SWEEP} because it cannot make the sweep's promise: it hands back
     * referrals. What it promises instead is asserted below -- it can only ever answer "a page of the referrals
     * in this state", never "this referral" -- and the scoping it does without is replaced by
     * {@code JudicialReferralService.visibleTo} on every read of an individual referral.
     */
    private static final Set<String> POOL = Set.of("findByState");

    /**
     * The judicial read of one referral, which is the finder by referral id alone that the interface's own rule
     * forbids -- admitted here because the judicial surface has no case id to scope with.
     *
     * <p>A judicial URL is {@code /judicial/referrals/{referralId}} precisely so that a judge cannot name an
     * application, so the safety boundary moves rather than disappearing: where a caseworker read is scoped by
     * its parent, a judicial read is scoped by {@code JudicialReferralService.visibleTo}. That makes this the
     * most dangerous declaration in the repository, and it is admitted with two rules rather than a comment --
     * nothing but {@code JudicialReferralService} may call it, and the visibility check that replaces the scope
     * is pinned state by state in {@code JudicialReferralServiceTest}.
     */
    private static final Set<String> JUDICIAL_READ = Set.of("findWithCaseById");

    /**
     * The judge's personal list, which names no parent case either -- and is the one unscoped finder that carries
     * a scope of its own.
     *
     * <p>Listed separately from {@link #POOL} because what makes it safe is different: the pool's rows are the
     * shared work every judge may see, whereas these are one judge's, so the filter has to be an identity. It is
     * the caller's IDAM subject, which comes from their token and not from the request, so a judge cannot widen it
     * to another's -- and the states are the two {@code JudicialReferralService.visibleTo} admits for an owner,
     * which is what keeps the list and the boundary from disagreeing.
     */
    private static final Set<String> PERSONAL_LIST = Set.of("findOwnedBy");

    /** Every finder that names no parent case, for the declaration rule that admits each one deliberately. */
    private static final Set<String> UNSCOPED = Stream.of(SWEEP, POOL, PERSONAL_LIST, JUDICIAL_READ)
        .flatMap(Set::stream)
        .collect(Collectors.toUnmodifiableSet());

    /** A caller with no role this service asks about: the ordinary caseworker, and the fail-closed case. */
    private static final CurrentUser NO_ROLES = CurrentUser.currentUser(List.of(), "Otto Other", "other-1");

    private static final CurrentUser ADMIN =
        CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "An Admin", "admin-2");

    /**
     * A judge: the role IDAM does not issue yet, supplied directly, which is what makes the gate provable today.
     */
    private static final CurrentUser JUDGE =
        CurrentUser.currentUser(List.of(Role.JUDICIAL), "District Judge Bell", "judge-3");

    /**
     * Role strings a token could plausibly carry that are <em>not</em> this service's judicial role.
     *
     * <p>Every one of them is either a real claim IDAM issues across the estate today, a different case of the
     * working value, or the kind of near miss a mis-provisioned role would produce. None may pass the gate:
     * {@link Role#fromClaimValue} matches exactly, and the point of stating it here is that the judicial
     * transitions are gated by a claim value IDAM does not issue at all yet, so the only thing standing between
     * a live token and a claimed referral is that this match is exact.
     */
    private static final List<String> NEAR_MISSES = List.of(
        "caseworker-deedpoll-admin",
        "caseworker-deedpoll",
        "caseworker-deedpoll-judiciary",
        "caseworker-deedpoll-judicial-admin",
        "CASEWORKER-DEEDPOLL-JUDICIAL",
        "judicial",
        "caseworker");

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
                                      new ReferralProperties(Duration.ofHours(2)),
                                      mock(EntityManager.class));

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
        // Through the locked lookup as well, because that is the one every write reads -- and only ever for this
        // case's id, so a cross-case write is refused by the finder here exactly as it is by the SQL.
        when(referrals.findByIdAndDeedPollIdForUpdate(REFERRAL_ID, CASE_ID)).thenReturn(Optional.of(referral));
        return referral;
    }

    private static CreateReferralInput minimal() {
        return ReferralSchemas.parseCreateReferral(Map.of("reasons", List.of("Minor")));
    }

    /** The shortest direction the schema accepts: these tests are about who may record one, not what it says. */
    private static DirectionInput approval() {
        return ReferralSchemas.parseDirection(Map.of(
            "decision", "Approved",
            "directionsText", "Approved",
            "publishToGazette", false));
    }

    /**
     * A referral of {@link #CASE_ID} with a direction on it, as the closing caseworker finds one.
     *
     * <p>The direction is populated rather than left null so that a close reached through the wrong parent can be
     * shown to have changed nothing at all -- not merely to have left the state alone.
     */
    private Referral storedDecision() {
        Referral referral = stored(ReferralState.Decided);
        referral.setDecision(MasterDecision.Approved);
        referral.setDirectionsText("Granted, publication as usual");
        referral.setPublishToGazette(true);
        referral.setDecisionDate(LocalDateTime.parse("2026-09-02T11:00:00"));
        referral.setDecidedById(JUDGE.userId());
        referral.setDecidedBy(JUDGE.name());
        return referral;
    }

    /**
     * The transitions that demand {@link Role#JUDICIAL}, in one list.
     *
     * <p>Written once and used by every refusal below, so the gate is asserted over the whole judicial surface
     * rather than over whichever transition somebody remembered. A fourth one is covered by being added here.
     */
    private List<Transition> judicialTransitions() {
        return List.of(
            new Transition("claim", (caseId, caller) -> service.claim(caseId, REFERRAL_ID.toString(), caller)),
            new Transition("touch", (caseId, caller) -> service.touch(caseId, REFERRAL_ID.toString(), caller)),
            new Transition("decide",
                           (caseId, caller) -> service.decide(caseId, REFERRAL_ID.toString(), approval(), caller)));
    }

    /**
     * One judicial transition, named so a failure says which of them let a caller through.
     *
     * <p>The case id is a parameter rather than baked in, because the two questions asked of these transitions
     * are "who may" and "through which parent", and the second one needs the same call addressed at a case that
     * is not the referral's.
     */
    private record Transition(String name, BiConsumer<String, CurrentUser> apply) {

        /** Addressed at the referral's own case: every question about who may act. */
        void by(CurrentUser caller) {
            apply.accept(CASE_ID, caller);
        }

        /** Addressed at a real case that is not the referral's parent: the scoping question. */
        void throughAnotherCase(CurrentUser caller) {
            apply.accept(OTHER_CASE_ID, caller);
        }
    }

    @Nested
    @DisplayName("namespace separation")
    class NamespaceSeparation {

        private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("uk.gov.hmcts.reform.deedpoll");

        /**
         * The unscoped finder the judicial pool needs is reachable from the judicial service and nowhere else.
         *
         * <p>This is what keeps decision 2 from being a hole. {@code findByState} carries no case id, so a
         * caseworker path that called it would have a cross-case read of referrals with none of the caseworker
         * surface's scoping and none of the judicial surface's role gate -- and the declaration rule above
         * cannot catch that, because it admits the method deliberately. The gate lives on
         * {@code JudicialReferralService.pool}, so the rule is that nothing else calls the finder at all.
         */
        @Test
        @DisplayName("only the judicial service reaches the unscoped pool finder")
        void onlyTheJudicialServiceReachesThePoolFinder() {
            noClasses()
                .that().doNotHaveFullyQualifiedName(JudicialReferralService.class.getName())
                .should().callMethodWhere(DescribedPredicate.describe(
                    "a call to the unscoped pool finder",
                    (JavaMethodCall call) -> POOL.contains(call.getName())
                        && call.getTargetOwner().isAssignableTo(ReferralRepository.class)))
                .check(CLASSES);
        }

        /**
         * And the same rule for the personal list, which is a cross-case read keyed on an identity.
         *
         * <p>{@code findOwnedBy} takes the subject whose referrals to hand back, so a caller that chose it would be
         * choosing whose work to read -- which is exactly what {@code /judicial/referrals/mine} refuses to let a
         * request do. The gate is that {@code JudicialReferralService.mine} passes
         * {@code CurrentUser.requireUserId()} and nothing else, so the rule is that nothing else calls the finder
         * at all: a second caller would be a second place that could pass an owner a caller had named.
         */
        @Test
        @DisplayName("only the judicial service reaches the owner-scoped personal finder")
        void onlyTheJudicialServiceReachesThePersonalFinder() {
            noClasses()
                .that().doNotHaveFullyQualifiedName(JudicialReferralService.class.getName())
                .should().callMethodWhere(DescribedPredicate.describe(
                    "a call to the owner-scoped personal finder",
                    (JavaMethodCall call) -> PERSONAL_LIST.contains(call.getName())
                        && call.getTargetOwner().isAssignableTo(ReferralRepository.class)))
                .check(CLASSES);
        }

        /**
         * And the same rule for the finder by referral id alone, where it matters most.
         *
         * <p>{@code findWithCaseById} answers with a referral -- and with its whole application attached -- for
         * any id at all, with no parent to scope it and nothing in the declaration to stop a caller. It is safe
         * only because the single place that calls it checks {@code visibleTo} first and 404s otherwise, so a
         * second caller is not a duplication of that check, it is a route around it: a caseworker path calling
         * this would have a cross-case read of every referral and every case behind them with neither the
         * caseworker surface's parent id nor the judicial surface's role gate in front of it.
         */
        @Test
        @DisplayName("only the judicial service reaches the finder by referral id alone")
        void onlyTheJudicialServiceReachesTheByIdFinder() {
            noClasses()
                .that().doNotHaveFullyQualifiedName(JudicialReferralService.class.getName())
                .should().callMethodWhere(DescribedPredicate.describe(
                    "a call to the unscoped by-id finder",
                    (JavaMethodCall call) -> JUDICIAL_READ.contains(call.getName())
                        && call.getTargetOwner().isAssignableTo(ReferralRepository.class)))
                .check(CLASSES);
        }

        /**
         * Neither judicial route names a case, which is the other half of decision 2.
         *
         * <p>The repository rules above are about what can be <em>asked</em>; this is about what can be
         * <em>said</em>. A judicial handler taking a case id would let a judge name an application directly, and
         * the visibility check -- which is about a referral -- would have nothing to say about it.
         */
        @Test
        @DisplayName("every judicial route is keyed on the referral, and no judicial handler takes a case id")
        void everyJudicialRouteIsKeyedOnTheReferral() {
            RequestMapping mapping = JudicialReferralController.class.getAnnotation(RequestMapping.class);

            assertThat(mapping).isNotNull();
            assertThat(mapping.value()).containsExactly("/judicial/referrals");

            for (Method handler : judicialHandlers()) {
                List<String> pathVariables = Arrays.stream(handler.getParameters())
                    .map(parameter -> parameter.getAnnotation(PathVariable.class))
                    .filter(Objects::nonNull)
                    .map(PathVariable::value)
                    .toList();

                assertThat(pathVariables)
                    .as("%s names a referral or nothing, and never a case", handler.getName())
                    .isSubsetOf("referralId");
            }
        }

        @Test
        @DisplayName("the judicial controller neither returns nor accepts an entity")
        void theJudicialControllerNeitherReturnsNorAcceptsAnEntity() {
            // The same rule as the caseworker controller's, and the stakes are higher: the referral the judicial
            // read resolves has its application join-fetched onto it, so a handler that returned the entity would
            // publish the whole case -- every payment reference, every workflow date, the privacy field -- to the
            // one audience the narrow projection exists for.
            for (Method method : judicialHandlers()) {
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
        @DisplayName("may close a returned direction, because closing is not role-gated either")
        void mayCloseAReturnedDirection() {
            bothCasesExist();
            Referral referral = storedDecision();

            // The fifth operation, and the one most likely to acquire a gate by analogy: it is the transition
            // that says the office actioned the direction, so a reviewer could reasonably expect a permission on
            // it. Decision 4 is that there is none -- whoever works the case actions the Master's reply -- and
            // that has to be asserted, because a gate added here would refuse every caseworker in existence.
            assertThatCode(() -> service.close(CASE_ID, REFERRAL_ID.toString(), NO_ROLES))
                .doesNotThrowAnyException();

            assertThat(referral.getState()).isEqualTo(ReferralState.Closed);
            assertThat(referral.getModifiedBy()).isEqualTo("Otto Other");
        }

        @Test
        @DisplayName("and so may a judge's own token, because the caseworker/judge split is by surface, not by gate")
        void soMayAJudgesToken() {
            bothCasesExist();
            Referral referral = storedDecision();

            // Stated because it is the honest reading of decision 4 and the surprising half of it. "A judge does
            // not close a referral" is true of the process and of the surfaces -- there is no closure route under
            // /judicial/** and there will not be one -- but it is NOT enforced by a role check, so a token that
            // happens to hold JUDICIAL and reaches the caseworker route is admitted like any other authenticated
            // caller. Writing that down is what stops the next reader assuming a refusal that is not there, and
            // what makes adding one a deliberate change to this test rather than a silent tightening.
            assertThatCode(() -> service.close(CASE_ID, REFERRAL_ID.toString(), JUDGE))
                .doesNotThrowAnyException();

            assertThat(referral.getState()).isEqualTo(ReferralState.Closed);
            assertThat(referral.getDecidedBy())
                .as("closing leaves the direction and its author exactly as the judge recorded them")
                .isEqualTo(JUDGE.name());
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
        @EnumSource(value = ReferralState.class, names = {"Decided", "Closed", "Withdrawn"})
        @DisplayName("is refused an unwithdrawable referral exactly as an administrator is, and nothing is written")
        void isRefusedAnUnwithdrawableReferralAsAnAdministratorIs(ReferralState unwithdrawable) {
            Referral referral = stored(unwithdrawable);

            // Decided belongs in this set and not in the permitted one, which is the rule the completion plan
            // added: a direction the office is obliged to action must not be discardable, by an administrator
            // least of all -- an escape hatch that only admins had would be the version of this rule that gets
            // used. The two terminal states are refused for the older reason below.
            for (CurrentUser caller : List.of(NO_ROLES, ADMIN)) {
                assertThatThrownBy(() -> service.withdrawReferral(CASE_ID, REFERRAL_ID.toString(), caller))
                    .isInstanceOf(ConflictError.class);
            }

            // The refusal has to come before the write, not after it: a 409 that had already withdrawn a closed
            // referral would erase the fact that a direction was actioned, and the status code cannot tell the
            // two apart.
            assertThat(referral.getState()).isEqualTo(unwithdrawable);
            verify(referrals, never()).save(any());
            verifyNoInteractions(notes);
        }

        @Test
        @DisplayName("and those are exactly the states ReferralState.canBeWithdrawn() refuses, with none left out")
        void theRefusedStatesAreTheEnumsOwn() {
            // The list above is named rather than derived, because @EnumSource takes names -- so this is what
            // stops the pair drifting. A sixth state is then a decision taken on the enum and reflected here,
            // rather than a state no caller is ever asked about.
            assertThat(Arrays.stream(ReferralState.values()).filter(state -> !state.canBeWithdrawn()))
                .as("every unwithdrawable state is exercised by the parameterised refusal above")
                .containsExactly(ReferralState.Decided, ReferralState.Closed, ReferralState.Withdrawn);
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Open", "Claimed", "Withdrawn", "Closed"})
        @DisplayName("is refused a close with no direction to action, exactly as an administrator is")
        void isRefusedACloseWithNoDirectionAsAnAdministratorIs(ReferralState state) {
            Referral referral = stored(state);

            // The other half of the pair: both callers permitted where there is no rule, both refused where the
            // rule is about the referral rather than about them. A conflict that an administrator could step past
            // would make closing a privilege, and closing an Open referral takes it out of the pool with no
            // direction on it -- which is the outcome the guard exists to prevent, for everybody.
            for (CurrentUser caller : List.of(NO_ROLES, ADMIN)) {
                assertThatThrownBy(() -> service.close(CASE_ID, REFERRAL_ID.toString(), caller))
                    .isInstanceOf(ConflictError.class);
            }

            assertThat(referral.getState()).isEqualTo(state);
            verify(referrals, never()).save(any());
            verifyNoInteractions(notes);
        }
    }

    @Nested
    @DisplayName("the judicial transitions")
    class JudicialGate {

        @Test
        @DisplayName("refuse a caseworker on every one of them, naming the role, and write nothing")
        void refuseACaseworker() {
            bothCasesExist();
            Referral referral = stored(ReferralState.Open);

            // The one refusal this resource has. Everything else here is open to any authenticated staff caller,
            // so a gate that had been dropped would look exactly like the rest of the service and be the only
            // place it mattered: a caseworker who could claim a referral out of the pool could also answer it.
            for (Transition transition : judicialTransitions()) {
                assertThatThrownBy(() -> transition.by(NO_ROLES))
                    .as("%s refuses a caller with no roles", transition.name())
                    .isInstanceOf(ForbiddenError.class)
                    .hasMessageContaining(Role.JUDICIAL.claimValue());
            }

            assertThat(referral.getState()).isEqualTo(ReferralState.Open);
            verify(referrals, never()).save(any());
            verifyNoInteractions(notes);
        }

        @Test
        @DisplayName("refuse an administrator too, because admin is a different role and not a wider one")
        void refuseAnAdministrator() {
            bothCasesExist();
            Referral referral = stored(ReferralState.Open);

            // Asserted rather than assumed, for the reason the permitted operations are asserted with two
            // callers: this service's only other privilege is the admin role, and a gate written as "any
            // privileged caller" would pass every test that only ever tried an unprivileged one.
            for (Transition transition : judicialTransitions()) {
                assertThatThrownBy(() -> transition.by(ADMIN))
                    .as("%s refuses an administrator", transition.name())
                    .isInstanceOf(ForbiddenError.class);
            }

            assertThat(referral.getState()).isEqualTo(ReferralState.Open);
            verify(referrals, never()).save(any());
        }

        @Test
        @DisplayName("refuse before the case or the referral is read, so a 403 says nothing about what exists")
        void refuseBeforeAnythingIsRead() {
            // Nothing is stored, and the refusal is the same one: the gate answers before the lookups, so a
            // caller who may not act learns neither whether the case exists nor whether the referral does. The
            // sweep is not run on their behalf either -- it writes notes, and a refused caller must not be able
            // to move another judge's claim by being refused often enough.
            for (Transition transition : judicialTransitions()) {
                assertThatThrownBy(() -> transition.by(NO_ROLES))
                    .as("%s refuses before the lookup", transition.name())
                    .isInstanceOf(ForbiddenError.class);
            }

            verifyNoInteractions(deedPolls, notes);
            // Through neither finder: the transitions write, so they read under the lock, and an assertion naming
            // only the unlocked one would be about a call they never make.
            verify(referrals, never()).findByIdAndDeedPollIdForUpdate(any(), anyString());
            verify(referrals, never()).findByIdAndDeedPollId(any(), anyString());
            verify(referrals, never()).findStaleClaims(any(), any());
            verify(referrals, never()).save(any());
        }

        @Test
        @DisplayName("admit a judge, so the refusals above are a rule and not a service that refuses everyone")
        void admitAJudge() {
            bothCasesExist();
            Referral referral = stored(ReferralState.Open);

            assertThatCode(() -> service.claim(CASE_ID, REFERRAL_ID.toString(), JUDGE))
                .doesNotThrowAnyException();
            assertThat(referral.getState()).isEqualTo(ReferralState.Claimed);
            assertThat(referral.getClaimedBy()).isEqualTo(JUDGE.name());
            // And the claim is owned by their subject, which is what the two transitions below then compare.
            assertThat(referral.getClaimedById()).isEqualTo(JUDGE.userId());

            // And the rest of the surface follows, on the referral this judge now holds: the gate is about the
            // role, so a claimed referral is touchable and directable by its holder and by nobody else.
            for (Transition transition : judicialTransitions().subList(1, judicialTransitions().size())) {
                assertThatCode(() -> transition.by(JUDGE))
                    .as("%s admits the judge holding it", transition.name())
                    .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("are unreachable by a token asserting a role string that is not exactly the judicial claim")
        void areUnreachableByANearMissClaim() {
            bothCasesExist();
            stored(ReferralState.Open);

            // From the token end, because that is where the risk actually is. IDAM does not issue the judicial
            // claim at all yet, so what stands between a live token and a claimed referral is that the reduction
            // in IdamClaims matches the claim value exactly and drops everything else. A prefix match, a
            // case-insensitive one or a "contains" would let one of these through -- and the roles below are the
            // ones a real or a mis-provisioned token would plausibly carry.
            for (String claim : NEAR_MISSES) {
                CurrentUser caller = IdamClaims.userFromClaims(
                    Map.of("roles", List.of(claim), "name", "Near Miss", "sub", "near-miss-1"));

                assertThat(caller.roles())
                    .as("%s is not the judicial role", claim)
                    .doesNotContain(Role.JUDICIAL);
                for (Transition transition : judicialTransitions()) {
                    assertThatThrownBy(() -> transition.by(caller))
                        .as("%s refuses a token asserting %s", transition.name(), claim)
                        .isInstanceOf(ForbiddenError.class);
                }
            }

            verify(referrals, never()).save(any());
            verifyNoInteractions(notes);
        }

        @Test
        @DisplayName("are admitted by the claim value IDAM will issue, spelt exactly once in the Role enum")
        void areAdmittedByTheExactClaim() {
            bothCasesExist();
            stored(ReferralState.Open);

            // The counterpart, and what keeps the near-miss list from passing vacuously against a gate nothing
            // can satisfy. It goes through IdamClaims rather than constructing the caller, so the string on
            // Role.JUDICIAL is the one asserted end to end: when the idam plan confirms a different subrole, this
            // test moves with the enum member and nothing else has to.
            CurrentUser judgeFromToken = IdamClaims.userFromClaims(Map.of(
                "roles", List.of(Role.JUDICIAL.claimValue()),
                "name", "District Judge Bell",
                "sub", "judge-3"));

            assertThat(judgeFromToken.roles()).containsExactly(Role.JUDICIAL);
            assertThat(judgeFromToken.isAdmin())
                .as("the judicial role is not an administrator, so it unlocks nothing on the case surface")
                .isFalse();
            assertThatCode(() -> service.claim(CASE_ID, REFERRAL_ID.toString(), judgeFromToken))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("have no route on any controller yet, so the gate is not the only thing keeping them closed")
        void haveNoRouteYet() {
            // The judicial surface is a later plan, and until it exists the transitions are reachable only from
            // a test. Stated so that a route added here -- on the caseworker controller, under /caseworker/** --
            // fails rather than quietly inheriting this surface's "any authenticated caller may" reading, which
            // is written all over its javadoc and is true of everything else on it.
            List<String> paths = Arrays.stream(CaseworkerReferralController.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();

            assertThat(paths)
                .as("the judicial transitions are exposed by the judicial surface, not by this one")
                .isNotEmpty()
                .doesNotContain("claim", "touch", "decide", "direction");
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
        @DisplayName("is 404 to close, and its direction survives exactly as the judge recorded it")
        void isNotFoundToClose() {
            bothCasesExist();
            Referral referral = storedDecision();

            assertThatThrownBy(() -> service.close(OTHER_CASE_ID, REFERRAL_ID.toString(), NO_ROLES))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Referral " + REFERRAL_ID + " not found");

            // 404 and not 409, which is the whole point of the scope being in the lookup: a caller pointing
            // another case at this id is told the referral does not exist there, not told what state it is in.
            // And nothing is written -- a close through the wrong parent would mark somebody else's direction
            // actioned, which is the one thing about a referral that cannot be undone by raising another.
            assertThat(referral.getState()).isEqualTo(ReferralState.Decided);
            assertThat(referral.getDecision()).isEqualTo(MasterDecision.Approved);
            assertThat(referral.getDecidedBy()).isEqualTo(JUDGE.name());
            verify(referrals, never()).save(any());
            verifyNoInteractions(notes);
        }

        @Test
        @DisplayName("is 404 to claim, touch or direct, so the judicial half is scoped as the caseworker half is")
        void isNotFoundToTheJudicialTransitions() {
            bothCasesExist();
            Referral referral = storedDecision();
            referral.setState(ReferralState.Claimed);
            // Both, as a real claim writes both: the subject is what the holder check compares.
            referral.setClaimedById(JUDGE.userId());
            referral.setClaimedBy(JUDGE.name());
            referral.setLastTouchedAt(LocalDateTime.parse("2026-09-02T10:00:00"));

            // Held by this very judge, and still unreachable through another case: the transitions are scoped by
            // the same two-id lookup rather than by the claim they check, so holding a referral does not let its
            // judge address it through whatever case id they happen to have. A 404 rather than the conflict the
            // holder check would give, because the scope answers first.
            for (Transition transition : judicialTransitions()) {
                assertThatThrownBy(() -> transition.throughAnotherCase(JUDGE))
                    .as("%s is 404 through the wrong parent", transition.name())
                    .isInstanceOf(NotFoundError.class);
            }

            assertThat(referral.getState()).isEqualTo(ReferralState.Claimed);
            verify(referrals, never()).save(any());
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
        @DisplayName("could not be addressed by mistake: every finder carries the case but the admitted few")
        void cannotBeAddressedByIdAlone() {
            // The behavioural half of this rule is in ReferralServiceTest; this is the half that stops it being
            // re-introduced. `findById` is inherited from JpaRepository and cannot be removed, so what is
            // asserted is that this interface declares nothing that reaches a referral without naming its case --
            // except the exceptions below, each of which is admitted with a rule rather than a comment.
            List<String> declared = Arrays.stream(ReferralRepository.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();
            // The exceptions are real, so they are named rather than pattern-matched: a further unscoped method
            // has to be admitted here deliberately, and a rename that made these lists vacuous fails too.
            assertThat(declared).containsAll(UNSCOPED);

            for (Method finder : ReferralRepository.class.getDeclaredMethods()) {
                if (JUDICIAL_READ.contains(finder.getName())) {
                    // The one that can make no promise about its answer: it hands back a referral, by id, for any
                    // caller, with the whole application attached. What is asserted instead is the shape that
                    // makes the rule above sufficient -- an id and nothing else, so there is no second question
                    // this finder could be asked and no filter a caller could think it was applying.
                    assertThat(finder.getParameterTypes())
                        .as("%s takes a referral id and nothing else", finder.getName())
                        .containsExactly(UUID.class);
                    continue;
                }
                if (POOL.contains(finder.getName())) {
                    // The pool cannot make the sweep's promise, because handing back referrals is the point of
                    // it. Its own promise is that the question it can be asked has no referral in it: a state
                    // and a page, so the answer is always "the referrals in this state" and never "this
                    // referral". The state is JudicialReferralService's -- Open -- rather than a caller's, and
                    // the individual reads the judicial surface does are guarded by visibleTo instead.
                    assertThat(finder.getParameterTypes())
                        .as("%s takes a state and a page, and no referral id", finder.getName())
                        .containsExactly(ReferralState.class, Pageable.class);
                    assertThat(finder.getReturnType())
                        .as("%s answers a page rather than one referral", finder.getName())
                        .isEqualTo(Page.class);
                    continue;
                }
                if (PERSONAL_LIST.contains(finder.getName())) {
                    // The personal list hands back referrals like the pool, so its promise is also about the
                    // question it can be asked: an owner, the two states a judge's own work is in, and a page --
                    // and no referral id, so the answer is always "this owner's referrals" and never "this
                    // referral". The owner is a subject rather than a case id, which is why this is admitted here
                    // instead of satisfying the DeedPollId rule below: it is scoped by identity, and the identity
                    // is the caller's own token.
                    assertThat(finder.getParameterTypes())
                        .as("%s takes an owner, two states and a page, and no referral id", finder.getName())
                        .containsExactly(String.class, ReferralState.class, ReferralState.class, Pageable.class);
                    assertThat(finder.getReturnType())
                        .as("%s answers a page rather than one referral", finder.getName())
                        .isEqualTo(Page.class);
                    continue;
                }
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

    /** The judicial controller's own handler methods, likewise. */
    private static List<Method> judicialHandlers() {
        return Arrays.stream(JudicialReferralController.class.getDeclaredMethods())
            .filter(method -> !method.isSynthetic())
            .toList();
    }
}
