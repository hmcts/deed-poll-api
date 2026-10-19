package uk.gov.hmcts.reform.deedpoll.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.util.AntPathMatcher;
import uk.gov.hmcts.reform.deedpoll.api.judicial.controller.JudicialReferralController;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.IdamClaims;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentService;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.DirectionInput;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.JudicialReferralService;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralSchemas;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralService;
import uk.gov.hmcts.reform.deedpoll.domain.witnesses.WitnessService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What the judicial surface refuses, asserted over every one of its operations at once, with no database and no
 * HTTP.
 *
 * <p><strong>The three properties this class exists for are the plan's:</strong> a caseworker token is refused
 * every judicial route, an unauthenticated caller never reaches one, and one judge cannot read, claim or direct a
 * referral another judge holds. Each of those is already true operation by operation --
 * {@code JudicialReferralServiceTest} pins the rules and {@code JudicialReferralControllerTest} proves which status
 * code each becomes over real HTTP with a real judicial token -- and what is missing until this class exists is the
 * statement <em>across</em> the surface: every assertion below is driven from {@link #operations()}, which is
 * asserted to be the whole of it, so a fifth judicial operation is covered by being added to that list rather than
 * by somebody remembering to copy three tests.
 *
 * <p><strong>Container-free, which is the other reason it is a separate class.</strong> The container-backed
 * classes are excluded where CI runs -- {@code INTEGRATION_TESTS_ENABLED=false} on an agent with no Docker -- and
 * the role gate and the visibility rule are the two rules in this feature whose failure is a disclosure rather than
 * a bug. So they are stated where every build runs them, exactly as {@code SavedQuerySecurityTest} and
 * {@code ReferralSecurityTest} state their resources'.
 *
 * <p><strong>The 401 is asserted as a property of the allowlist rather than over HTTP</strong>, for the same
 * reason: {@code SecurityConfigurationTest} and {@code JudicialReferralControllerTest} both boot a context and
 * neither runs in CI, and what makes {@code /judicial/**} authenticated is not a matcher somebody wrote for it but
 * that {@code SecurityConfiguration} authenticates {@code anyRequest()} not on a short infrastructure allowlist.
 * That is a claim about the allowlist, and it can be checked without a server.
 *
 * <p>Four callers, as the resource has: two judges, because a test with one cannot tell a per-judge rule from no
 * rule at all; a caseworker holding no role this surface asks about; and an administrator, because the only other
 * privilege in this service is the admin role and a gate written as "any privileged caller" would pass every test
 * that never tried one.
 */
class JudicialSecurityTest {

    /** A judge: the role IDAM does not issue yet, supplied directly, which is what makes the gate provable. */
    private static final CurrentUser JUDGE =
        CurrentUser.currentUser(List.of(Role.JUDICIAL), "District Judge Bell", "judge-3");

    /** A second judge, for every question that is about which of two judges a referral belongs to. */
    private static final CurrentUser OTHER_JUDGE =
        CurrentUser.currentUser(List.of(Role.JUDICIAL), "District Judge Okafor", "judge-4");

    /**
     * A third judge who shares {@link #JUDGE}'s display name: a different person, indistinguishable by name.
     *
     * <p>The caller the ownership migration exists for, and the one this class is the right place to state: while
     * visibility compared display names, a namesake was admitted to another judge's referral -- and on this
     * surface that is the application and its whole notes timeline, because {@code visibleTo} is the only boundary
     * in front of a finder by referral id.
     */
    private static final CurrentUser NAMESAKE_JUDGE =
        CurrentUser.currentUser(List.of(Role.JUDICIAL), "District Judge Bell", "judge-9");

    /** An ordinary caseworker: authenticated and named, holding no role this surface asks about. */
    private static final CurrentUser CASEWORKER =
        CurrentUser.currentUser(List.of(), "Ada Caseworker", "caseworker-1");

    private static final CurrentUser ADMIN =
        CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "An Admin", "admin-2");

    /**
     * Role strings a token could plausibly carry that are not this service's judicial role.
     *
     * <p>The same list {@code ReferralSecurityTest} puts in front of the transitions, put in front of the surface
     * that exposes them: each is either a claim IDAM issues across the estate today, a different case of the
     * working value, or the near miss a mis-provisioned role would produce. IDAM issues the judicial claim to
     * nobody yet, so what stands between a live token and the judicial surface is that
     * {@link Role#fromClaimValue} matches exactly.
     */
    private static final List<String> NEAR_MISSES = List.of(
        "caseworker-deedpoll-admin",
        "caseworker-deedpoll",
        "caseworker-deedpoll-judiciary",
        "caseworker-deedpoll-judicial-admin",
        "CASEWORKER-DEEDPOLL-JUDICIAL",
        "judicial",
        "caseworker");

    /** The case every fixture referral belongs to, which no judicial URL carries and no judicial caller names. */
    private static final String CASE_ID = "case-1";

    private static final UUID REFERRAL_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    /** An id of the right shape that nothing was stored under: the other half of every 404 comparison. */
    private static final UUID UNKNOWN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    /** A document id, which is only ever accepted under a referral the caller may see. */
    private static final String DOCUMENT_ID = "66666666-6666-6666-6666-666666666666";

    /**
     * How many judicial operations have a route today.
     *
     * <p>{@link #operations()} is the service's surface, and the two now agree: {@code mine} was gated before it was
     * published -- safe for the reason the transitions were before the judicial controller existed, because a gate on
     * a surface nobody can reach refuses everybody -- and publishing its route moved this line. The document download
     * (2026-10-19) moved it again, and so did revising a direction (a {@code PUT} beside the {@code POST}). Written
     * as a number rather than derived, so a handler arriving without a gated service method behind it fails here
     * instead of following whatever the controller happens to declare.
     */
    private static final int ROUTED_OPERATIONS = 7;

    /** Every judicial path, as a caller would type it: what the allowlist must not match. */
    private static final List<String> JUDICIAL_PATHS = List.of(
        "/judicial/referrals",
        // A literal segment, so it is a path in its own right rather than one spelling of the referral id below.
        "/judicial/referrals/mine",
        "/judicial/referrals/" + REFERRAL_ID,
        "/judicial/referrals/" + REFERRAL_ID + "/claim",
        "/judicial/referrals/" + REFERRAL_ID + "/direction",
        "/judicial/referrals/" + REFERRAL_ID + "/documents/" + DOCUMENT_ID + "/file");

    private ReferralRepository referrals;
    private ReferralService transitions;
    private NoteService notes;
    private WitnessService witnesses;
    private DocumentService documents;
    private JudicialReferralService service;

    @BeforeEach
    void createService() {
        referrals = mock(ReferralRepository.class);
        transitions = mock(ReferralService.class);
        notes = mock(NoteService.class);
        witnesses = mock(WitnessService.class);
        documents = mock(DocumentService.class);
        service = new JudicialReferralService(referrals, transitions, notes, witnesses, documents);

        // An empty timeline rather than the mock's null: the read wraps the notes in a view, so a judge admitted
        // by the gate has to be able to get all the way to an answer for the refusals to mean anything.
        when(notes.findNotes(anyString())).thenReturn(List.of());
    }

    /**
     * One judicial operation, named so a failure says which of them let a caller through or refused one wrongly.
     *
     * @param keyedOnAReferral whether it names a referral -- four do, and the other two are the lists they are
     *                         reached from, so the visibility questions below are asked of those four
     */
    private record Operation(String name, boolean keyedOnAReferral, BiConsumer<String, CurrentUser> apply) {

        void by(CurrentUser caller) {
            apply.accept(REFERRAL_ID.toString(), caller);
        }

        void asked(String referralId, CurrentUser caller) {
            apply.accept(referralId, caller);
        }
    }

    /**
     * The whole judicial surface, in one list.
     *
     * <p>{@link JudicialSurface#theseAreEveryJudicialOperation()} is what keeps it honest: the names here are
     * asserted against the service's own public methods and against the controller's handlers, so an operation
     * added to either without being added here fails rather than going unasserted.
     */
    private List<Operation> operations() {
        return List.of(
            new Operation("pool", false,
                          (referralId, caller) -> service.pool(caller, JudicialReferralService.FIRST_POOL_PAGE)),
            // The judge's own outstanding work. Keyed on nothing a caller types: whose list it is comes from the
            // token, which is why it names no referral and takes no owner.
            new Operation("mine", false,
                          (referralId, caller) -> service.mine(caller, JudicialReferralService.FIRST_POOL_PAGE)),
            new Operation("referral", true, (referralId, caller) -> service.referral(referralId, caller)),
            new Operation("claim", true, (referralId, caller) -> service.claim(referralId, caller)),
            new Operation("direct", true, (referralId, caller) -> service.direct(referralId, direction(), caller)),
            // The directing judge replacing their own direction: the same gate and the same visibility.
            new Operation("revise", true, (referralId, caller) -> service.revise(referralId, direction(), caller)),
            // A supporting document, reached through the referral and never through the case: the same gate and
            // the same visibility, so it is asked every question the other three referral operations are.
            new Operation("document", true,
                          (referralId, caller) -> service.document(referralId, DOCUMENT_ID, caller)));
    }

    /** The four operations that name a referral, which are the ones visibility has anything to say about. */
    private List<Operation> referralOperations() {
        return operations().stream().filter(Operation::keyedOnAReferral).toList();
    }

    /** The shortest direction the schema accepts: these tests are about who may record one, not what it says. */
    private static DirectionInput direction() {
        return ReferralSchemas.parseDirection(Map.of(
            "decision", "Approved",
            "directionsText", "Approved",
            "publishToGazette", false));
    }

    /**
     * A referral of {@link #CASE_ID} in the given state, held and decided by the given judge, stored where the
     * judicial read finds it.
     *
     * <p>Through {@code findWithCaseById}, which is the only finder the judicial surface has: a judicial caller
     * has no case id to scope with, so the fixture mirrors that rather than a case-scoped read it cannot make.
     * All four holder columns are set whatever the state, because the question asked below is what the
     * <em>state</em> admits -- a withdrawn referral still naming the judge who had it is the row a rule reading
     * the owner first would leak. The subjects are what the visibility rule compares and the names are the
     * attribution beside them, so a fixture that set only the names would be a row no judge owns.
     */
    private Referral stored(ReferralState state, CurrentUser holder) {
        DeedPoll parent = new DeedPoll();
        parent.setId(CASE_ID);
        Referral referral = new Referral();
        referral.setId(REFERRAL_ID);
        referral.setDeedPoll(parent);
        referral.setState(state);
        referral.setCreatedOn(LocalDateTime.of(2026, 5, 1, 9, 0));
        referral.setLastTouchedAt(LocalDateTime.of(2026, 5, 1, 9, 0));
        if (holder != null) {
            referral.setClaimedById(holder.userId());
            referral.setClaimedBy(holder.name());
            referral.setDecidedById(holder.userId());
            referral.setDecidedBy(holder.name());
        }
        when(referrals.findWithCaseById(REFERRAL_ID)).thenReturn(Optional.of(referral));
        return referral;
    }

    /** Nothing was read, nothing was swept and nothing was written: the strongest form of "refused first". */
    private void verifyNothingHappened() {
        verifyNoInteractions(referrals);
        verifyNoInteractions(transitions);
        verifyNoInteractions(notes, witnesses, documents);
    }

    /** No transition ran, whatever else the request did on the way to being refused. */
    private void verifyNoTransition() {
        verify(transitions, never()).claim(anyString(), anyString(), any());
        verify(transitions, never()).touch(anyString(), anyString(), any());
        verify(transitions, never()).decide(anyString(), anyString(), any(), any());
        verify(transitions, never()).reviseDirection(anyString(), anyString(), any(), any());
        verify(documents, never()).downloadDocument(anyString(), anyString(), any());
    }

    @Nested
    @DisplayName("the judicial surface")
    class JudicialSurface {

        @Test
        @DisplayName("is these operations and no others, so the rules below are asserted over all of it")
        void theseAreEveryJudicialOperation() {
            // The assertion that makes this class more than three more tests. Both halves are needed: the service
            // is where the gate and the visibility check live, and the controller is what a caller can reach, so
            // an operation added to the service without a route is still gated and a route added without going
            // through the service would be the hole.
            List<String> gated = Arrays.stream(JudicialReferralService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                // visibleTo is the predicate the gated methods use rather than an operation a caller performs,
                // and it is static, which is how it says so.
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .toList();

            assertThat(operations().stream().map(Operation::name).toList())
                .as("every gated judicial service method is exercised below")
                .containsExactlyInAnyOrderElementsOf(gated);

            assertThat(Arrays.stream(JudicialReferralController.class.getDeclaredMethods())
                           .filter(method -> !method.isSynthetic())
                           .toList())
                .as("the judicial controller exposes the routed operations and no more")
                .hasSize(ROUTED_OPERATIONS);
            // And every route is one of the gated operations above, which is the half that matters: a handler
            // reaching past this service would be the hole, and a gated method with no route yet is not.
            assertThat(ROUTED_OPERATIONS).isLessThanOrEqualTo(operations().size());
        }

        @Test
        @DisplayName("is authenticated by omission: no public path matches a judicial one")
        void isAuthenticatedByOmission() throws ReflectiveOperationException {
            // The 401 the plan asks for, stated where CI runs it. SecurityConfiguration authenticates
            // anyRequest() that is not on its infrastructure allowlist, so what protects /judicial/** is that the
            // allowlist does not reach it -- not a matcher somebody wrote, which is deliberate and is why there
            // is nothing judicial in that class to assert. A pattern added there later that happened to match a
            // judicial path would make the whole surface anonymous, and this is what notices.
            AntPathMatcher matcher = new AntPathMatcher();
            List<String> publicPaths = publicPaths();

            for (String path : JUDICIAL_PATHS) {
                for (String pattern : publicPaths) {
                    assertThat(matcher.match(pattern, path))
                        .as("%s does not admit %s anonymously", pattern, path)
                        .isFalse();
                }
            }

            // And the matcher really does match, so the loop above is not passing because nothing ever could:
            // /health is on the allowlist precisely because Kubernetes has no credential to present.
            assertThat(publicPaths).contains("/health");
            assertThat(matcher.match("/health/**", "/health/readiness")).isTrue();
        }

        /**
         * The allowlist as {@link SecurityConfiguration} declares it.
         *
         * <p>Read reflectively rather than by widening the field, because the list being private is part of the
         * design -- nothing outside that class decides what is public -- and a test is not a reason to change
         * that. It is a constant array in the same package, so there is no behaviour here to go stale, only a
         * name, and a rename fails this test loudly rather than silently passing it.
         */
        private List<String> publicPaths() throws ReflectiveOperationException {
            Field declared = SecurityConfiguration.class.getDeclaredField("PUBLIC_PATHS");
            declared.setAccessible(true);
            return List.of((String[]) declared.get(null));
        }
    }

    @Nested
    @DisplayName("a caseworker token")
    class RoleGate {

        @Test
        @DisplayName("is refused every judicial operation with a 403 naming the role, and nothing is read")
        void isRefusedEveryJudicialOperation() {
            for (Operation operation : operations()) {
                assertThatThrownBy(() -> operation.by(CASEWORKER))
                    .as("%s refuses a caseworker", operation.name())
                    .isInstanceOf(ForbiddenError.class)
                    .hasMessageContaining(Role.JUDICIAL.claimValue());
            }

            // Refused before the lookup, the sweep and the write alike, so a 403 tells the caller nothing about
            // what exists -- and a caseworker cannot move another judge's claim by being refused often enough,
            // which they could if the gate came after the pool's sweep.
            verifyNothingHappened();
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"TEAM_LEADER", "DELIVERY_MANAGER", "DEEDPOLL_ADMIN"})
        @DisplayName("is refused it holding the administrator role too, because judicial is not seniority")
        void isRefusedHoldingTheAdminRole(Role role) {
            CurrentUser caller = CurrentUser.currentUser(List.of(role), ADMIN.name(), ADMIN.userId());
            for (Operation operation : operations()) {
                assertThatThrownBy(() -> operation.by(caller))
                    .as("%s refuses an administrator", operation.name())
                    .isInstanceOf(ForbiddenError.class);
            }

            verifyNothingHappened();
        }

        @Test
        @DisplayName("is refused it asserting a role string that is not exactly the judicial claim")
        void isRefusedOnANearMissClaim() {
            // From the token end, because that is where the risk is: the judicial surface is reachable only by a
            // claim value IDAM issues to nobody today, so the reduction in IdamClaims matching exactly is the
            // whole of the boundary. A prefix, case-insensitive or "contains" match would let one of these in.
            for (String claim : NEAR_MISSES) {
                CurrentUser caller = IdamClaims.userFromClaims(
                    Map.of("roles", List.of(claim), "name", "Near Miss", "sub", "near-miss-1"));

                assertThat(caller.roles()).as("%s is not the judicial role", claim).doesNotContain(Role.JUDICIAL);
                for (Operation operation : operations()) {
                    assertThatThrownBy(() -> operation.by(caller))
                        .as("%s refuses a token asserting %s", operation.name(), claim)
                        .isInstanceOf(ForbiddenError.class);
                }
            }

            verifyNothingHappened();
        }

        @Test
        @DisplayName("and a judge is admitted every one of them, so the refusals are a rule and not a dead surface")
        void judgeIsAdmittedEveryOperation() {
            // The counterpart that stops all of the above passing against a surface nothing can reach. It goes
            // through IdamClaims rather than constructing the caller, so the string on Role.JUDICIAL is the one
            // asserted end to end: when idam-1 confirms a different subrole, this moves with the enum member.
            CurrentUser judgeFromToken = IdamClaims.userFromClaims(Map.of(
                "roles", List.of(Role.JUDICIAL.claimValue()),
                "name", JUDGE.name(),
                "sub", "judge-3"));
            Referral held = stored(ReferralState.Claimed, judgeFromToken);
            when(referrals.findByState(any(), any())).thenReturn(emptyPool());
            when(referrals.findOwnedBy(anyString(), any(), any(), any())).thenReturn(emptyPool());
            when(transitions.claim(anyString(), anyString(), any())).thenReturn(held);
            when(transitions.decide(anyString(), anyString(), any(), any())).thenReturn(held);

            assertThat(judgeFromToken.isAdmin())
                .as("the judicial role unlocks nothing on the caseworker surface")
                .isFalse();
            for (Operation operation : operations()) {
                assertThatCode(() -> operation.by(judgeFromToken))
                    .as("%s admits a judge", operation.name())
                    .doesNotThrowAnyException();
            }
        }

        private Page<Referral> emptyPool() {
            return new PageImpl<>(List.of());
        }
    }

    @Nested
    @DisplayName("a referral one judge holds, reached by another")
    class CrossJudgeIsolation {

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Claimed", "Decided"})
        @DisplayName("is 404 to read, claim and direct alike, and no transition runs on it")
        void isNotFoundToEveryOperationThatNamesIt(ReferralState theirs) {
            Referral referral = stored(theirs, OTHER_JUDGE);

            for (Operation operation : referralOperations()) {
                assertThatThrownBy(() -> operation.by(JUDGE))
                    .as("%s refuses a referral another judge holds", operation.name())
                    .isInstanceOf(NotFoundError.class);
            }

            // The assertion that carries the test: the other judge's referral is not merely unreadable, it is
            // untouched -- not re-claimed under this judge, not directed by them, and its inactivity clock not
            // refreshed by their reading it, which would keep somebody else's claim alive.
            verifyNoTransition();
            assertThat(referral.getState()).isEqualTo(theirs);
            assertThat(referral.getClaimedBy()).isEqualTo(OTHER_JUDGE.name());
            assertThat(referral.getClaimedById()).isEqualTo(OTHER_JUDGE.userId());
            verify(referrals, never()).save(any());
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Claimed", "Decided"})
        @DisplayName("is 404 to a different judge of the same display name, so isolation is per person")
        void isNotFoundToANamesakeOfTheHolder(ReferralState theirs) {
            Referral referral = stored(theirs, JUDGE);

            // The whole of what the ownership migration bought, at the boundary that used to give it away: these
            // two callers differ in nothing a referral row records except the subject, and that is now the only
            // thing the visibility rule reads. It is the same 404 an unknown id gets, for the same reason.
            for (Operation operation : referralOperations()) {
                assertThatThrownBy(() -> operation.by(NAMESAKE_JUDGE))
                    .as("%s refuses a judge who merely shares the holder's display name", operation.name())
                    .isInstanceOf(NotFoundError.class)
                    .hasMessage("Referral " + REFERRAL_ID + " not found");
            }

            verifyNoTransition();
            assertThat(referral.getState()).isEqualTo(theirs);
            assertThat(referral.getClaimedById()).isEqualTo(JUDGE.userId());
            verify(referrals, never()).save(any());
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Claimed", "Decided"})
        @DisplayName("is refused in the words an id nothing was stored under gets, so it is not discoverable")
        void isRefusedIndistinguishablyFromAnUnknownId(ReferralState theirs) {
            stored(theirs, OTHER_JUDGE);

            // Identical but for the id the caller typed, which is decision 3's whole point: a 403, a different
            // message or a different status would confirm that the referral exists and tell this judge that
            // another holds it. Both are asserted against the same template rather than against each other, so
            // a change that made both messages wrong in the same way fails too.
            for (Operation operation : referralOperations()) {
                assertThatThrownBy(() -> operation.asked(REFERRAL_ID.toString(), JUDGE))
                    .as("%s on another judge's referral", operation.name())
                    .hasMessage("Referral " + REFERRAL_ID + " not found");
                assertThatThrownBy(() -> operation.asked(UNKNOWN_ID.toString(), JUDGE))
                    .as("%s on an id nothing was stored under", operation.name())
                    .hasMessage("Referral " + UNKNOWN_ID + " not found");
            }
        }

        @Test
        @DisplayName("is reachable by the judge who does hold it, so the isolation is per judge and not a refusal")
        void isReachableByItsOwnJudge() {
            // One caller cannot tell a per-judge rule from a service that refuses everybody, which is why both
            // judges are here: the same fixture, the same three operations, and the holder is admitted.
            Referral held = stored(ReferralState.Claimed, JUDGE);
            when(transitions.decide(anyString(), anyString(), any(), any())).thenReturn(held);

            assertThatCode(() -> service.referral(REFERRAL_ID.toString(), JUDGE)).doesNotThrowAnyException();
            assertThatCode(() -> service.direct(REFERRAL_ID.toString(), direction(), JUDGE))
                .doesNotThrowAnyException();

            // Reading their own claim refreshes it, which is decision 7 and is also the difference the test
            // above asserts the absence of for the other judge.
            verify(transitions).touch(CASE_ID, REFERRAL_ID.toString(), JUDGE);
        }

        @Test
        @DisplayName("is not offered in the pool either, because the pool asks for the unclaimed work only")
        void isNotOfferedInThePool() {
            stored(ReferralState.Claimed, OTHER_JUDGE);
            when(referrals.findByState(any(), any())).thenReturn(new PageImpl<>(List.of()));

            service.pool(JUDGE, JudicialReferralService.FIRST_POOL_PAGE);

            // The state is in the query rather than in a filter over its result, so a referral somebody holds is
            // never loaded for another judge at all. A filter afterwards is a step that can be forgotten, and the
            // rows it forgot would already be in memory behind a response.
            verify(referrals).findByState(eq(ReferralState.Open), any());
            verify(referrals, never()).findAll();
        }

        @Test
        @DisplayName("is not on another judge's personal list, which is asked for by subject and never by name")
        void isNotOnAnotherJudgesPersonalList() {
            stored(ReferralState.Claimed, OTHER_JUDGE);
            when(referrals.findOwnedBy(anyString(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

            service.mine(JUDGE, JudicialReferralService.FIRST_POOL_PAGE);

            // The strongest form of the personal list's isolation, and the reason it is stated here rather than
            // only as an outcome: the owner is in the query, taken from the caller's own token, so another judge's
            // referrals are never loaded at all. A list keyed on the display name would have handed this caller a
            // namesake's whole outstanding work rather than requiring them to guess a referral id -- which is why
            // neither name appears in the question asked.
            verify(referrals).findOwnedBy(
                eq(JUDGE.userId()), eq(ReferralState.Claimed), eq(ReferralState.Decided), any());
            verify(referrals, never()).findOwnedBy(eq(OTHER_JUDGE.userId()), any(), any(), any());
            verify(referrals, never()).findOwnedBy(eq(JUDGE.name()), any(), any(), any());
            verify(referrals, never()).findAll();
        }

        @Test
        @DisplayName("and a namesake of the holder gets their own list, not the holder's")
        void namesakeGetsTheirOwnPersonalList() {
            stored(ReferralState.Claimed, JUDGE);
            when(referrals.findOwnedBy(anyString(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

            service.mine(NAMESAKE_JUDGE, JudicialReferralService.FIRST_POOL_PAGE);

            // These two callers differ in nothing a referral row records except the subject, and the subject is the
            // only thing asked for -- so the namesake's list is theirs and empty, rather than the holder's.
            verify(referrals).findOwnedBy(eq(NAMESAKE_JUDGE.userId()), any(), any(), any());
            verify(referrals, never()).findOwnedBy(eq(JUDGE.userId()), any(), any(), any());
        }
    }
}
