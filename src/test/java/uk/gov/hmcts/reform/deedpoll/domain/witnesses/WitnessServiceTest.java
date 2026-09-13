package uk.gov.hmcts.reform.deedpoll.domain.witnesses;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollWitness;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollWitnessRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ports every test block in {@code reference/ts/witnesses/service.test.ts}.
 *
 * <p><strong>Against a real database, not a mocked Prisma client</strong>, for the reason
 * {@code NoteServiceTest} and {@code DeedPollServiceTest} give: the reference asserts the arguments its
 * mock was called with because that is all a TypeScript unit test can do, and the stored row is the claim
 * those assertions stand for. So each of its shapes translates:
 *
 * <ul>
 *   <li>{@code expect(create).toHaveBeenCalledWith({data: {...witness, deedPollId}})} becomes "the stored
 *       witness carries those values, on that parent".</li>
 *   <li>{@code expect(update).toHaveBeenCalledWith({where: {id, deedPollId}, data})} becomes "the patch
 *       reached the row that belongs to that case, and no other row moved" -- the scoping the reference
 *       asserts by inspecting the {@code where} is here a property of which entity was loaded.</li>
 *   <li>{@code expect(update).not.toHaveBeenCalled()} becomes "the stored witness is unchanged".</li>
 * </ul>
 *
 * <p>Not {@code @Transactional}: the writes must really commit so the assertions read committed rows. The
 * tables are cleared around each test instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WitnessServiceTest extends AbstractPostgresContainerTest {

    /** createDeedPoll cannot allocate a reference number without a Date Received to derive the year. */
    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";

    /** An Administrator: the only caller a delete admits. */
    private static final CurrentUser ADMIN =
        CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "An Admin");

    /**
     * A Basic User: authenticated and named, holding no role this service recognises.
     *
     * <p>{@link Role} has one member, so today this differs from {@link CurrentUser#ANONYMOUS} only in its
     * name -- an ordinary caseworker's IDAM roles all belong to other services and are dropped by
     * {@code IdamClaims}. Both are asserted anyway, for the reason {@code NoteServiceTest} records: they are
     * different claims, and they stop being the same object the moment the enum grows a second role.
     */
    private static final CurrentUser CASEWORKER = CurrentUser.currentUser(List.of(), "A Caseworker");

    /** The reference's `witness` fixture. */
    private static final Map<String, Object> WITNESS = Map.of(
        "witnessName", "Sam Doe",
        "witnessAddress", "1 High St",
        "witnessOccupation", "Teacher",
        "declarant", false);

    @Autowired
    private WitnessService witnessService;

    @Autowired
    private DeedPollService deedPollService;

    @Autowired
    private DeedPollWitnessRepository witnesses;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private ReferenceCounterRepository referenceCounters;

    @BeforeEach
    @AfterEach
    void clearTables() {
        witnesses.deleteAll();
        deedPolls.deleteAll();
        referenceCounters.deleteAll();
    }

    /** A stored case to hang witnesses off. */
    private String storedCase() {
        return deedPollService.createDeedPoll(DeedPollPatch.of(Map.of("dateReceived", RECEIVED)))
            .deedPoll()
            .getId();
    }

    private static CreateWitnessInput create() {
        return WitnessSchemas.parseCreateWitness(WITNESS);
    }

    private static CreateWitnessInput create(String name) {
        Map<String, Object> raw = new LinkedHashMap<>(WITNESS);
        raw.put("witnessName", name);
        return WitnessSchemas.parseCreateWitness(raw);
    }

    private static UpdateWitnessInput update(Object... keysAndValues) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            raw.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return WitnessSchemas.parseUpdateWitness(raw);
    }

    /** The stored witness, read back rather than taken from the returned instance. */
    private DeedPollWitness stored(String id) {
        return witnesses.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("creates a witness scoped to its parent")
    void createsAWitnessScopedToItsParent() {
        String caseId = storedCase();

        DeedPollWitness created = witnessService.createWitness(caseId, create());

        DeedPollWitness saved = stored(created.getId());
        assertThat(saved.getWitnessName()).isEqualTo("Sam Doe");
        assertThat(saved.getWitnessAddress()).isEqualTo("1 High St");
        assertThat(saved.getWitnessOccupation()).isEqualTo("Teacher");
        assertThat(saved.isDeclarant()).isFalse();
        assertThat(saved.getDeedPoll().getId()).isEqualTo(caseId);
    }

    @Test
    @DisplayName("404s creating a witness on a missing case")
    void refusesToCreateAWitnessOnAMissingCase() {
        assertThatThrownBy(() -> witnessService.createWitness("nope", create()))
            .isInstanceOf(NotFoundError.class);

        assertThat(witnesses.count()).isZero();
    }

    @Test
    @DisplayName("lists witnesses for a case that exists, oldest first")
    void listsWitnessesForACaseThatExists() {
        String caseId = storedCase();
        DeedPollWitness first = witnessService.createWitness(caseId, create("First Witness"));
        DeedPollWitness second = witnessService.createWitness(caseId, create("Second Witness"));
        // Written explicitly rather than relying on two creates landing in different microseconds: the
        // claim under test is the order the query applies, not the clock's resolution.
        setCreatedOn(first.getId(), LocalDateTime.parse("2026-08-01T09:00:00"));
        setCreatedOn(second.getId(), LocalDateTime.parse("2026-08-09T09:00:00"));

        assertThat(witnessService.findWitnesses(caseId))
            .extracting(DeedPollWitness::getWitnessName)
            .containsExactly("First Witness", "Second Witness");
    }

    @Test
    @DisplayName("404s listing witnesses for a case that does not exist, rather than answering an empty list")
    void refusesToListWitnessesForACaseThatDoesNotExist() {
        // An empty list would say "this case has no witnesses", which is a different fact from "there is no
        // such case" -- and the wrong one for a mistyped id.
        assertThatThrownBy(() -> witnessService.findWitnesses("nope")).isInstanceOf(NotFoundError.class);
    }

    @Test
    @DisplayName("returns an empty list for a real case with no witnesses")
    void returnsAnEmptyListForARealCaseWithNoWitnesses() {
        // The other half of the pair: the 404 above must not swallow the legitimately-empty case.
        assertThat(witnessService.findWitnesses(storedCase())).isEmpty();
    }

    @Test
    @DisplayName("findWitness 404s across parents")
    void findWitnessRefusesAWitnessBelongingToAnotherCase() {
        String owner = storedCase();
        String other = storedCase();
        String witnessId = witnessService.createWitness(owner, create()).getId();

        assertThatThrownBy(() -> witnessService.findWitness(other, witnessId)).isInstanceOf(NotFoundError.class);
        assertThat(witnessService.findWitness(owner, witnessId).getId()).isEqualTo(witnessId);
    }

    @Test
    @DisplayName("removes after an existence check, scoped to the parent case")
    void removesAfterAnExistenceCheck() {
        String caseId = storedCase();
        String witnessId = witnessService.createWitness(caseId, create()).getId();

        witnessService.removeWitness(caseId, witnessId, ADMIN);

        assertThat(witnesses.findById(witnessId)).isEmpty();
    }

    @Test
    @DisplayName("404s updating a witness that belongs to another case, before writing")
    void refusesToUpdateAWitnessThatBelongsToAnotherCase() {
        String owner = storedCase();
        String other = storedCase();
        String witnessId = witnessService.createWitness(owner, create()).getId();

        assertThatThrownBy(() -> witnessService.updateWitness(other, witnessId, update("declarant", true)))
            .isInstanceOf(NotFoundError.class);

        assertThat(stored(witnessId).isDeclarant()).isFalse();
    }

    @Test
    @DisplayName("scopes the update to the parent case, so the write does not rely on the guard above it")
    void scopesTheUpdateToTheParentCase() {
        // The reference asserts deedPollId in the update's own `where`. Here the scope is not repeated at
        // the write: the write goes through the entity the guard loaded by id AND parent, and the
        // repository has no finder that omits the parent -- so an unscoped write is not expressible. What
        // is left to assert is the outcome that scoping buys: the right row moved and the other did not.
        String owner = storedCase();
        String other = storedCase();
        String mine = witnessService.createWitness(owner, create("Mine")).getId();
        String theirs = witnessService.createWitness(other, create("Theirs")).getId();

        witnessService.updateWitness(owner, mine, update("declarant", true));

        assertThat(stored(mine).isDeclarant()).isTrue();
        assertThat(stored(theirs).isDeclarant()).isFalse();
        assertThat(stored(theirs).getDeedPoll().getId()).isEqualTo(other);
    }

    @Test
    @DisplayName("writes only the fields the patch names")
    void writesOnlyTheFieldsThePatchNames() {
        // Not a reference block, where the assertion is on the `data` the mock received. Here it is the
        // absent-versus-present distinction reaching the row: the two fields the patch does not name must
        // come back as they were.
        String caseId = storedCase();
        String witnessId = witnessService.createWitness(caseId, create()).getId();

        witnessService.updateWitness(caseId, witnessId, update("witnessOccupation", "Nurse", "declarant", true));

        DeedPollWitness saved = stored(witnessId);
        assertThat(saved.getWitnessOccupation()).isEqualTo("Nurse");
        assertThat(saved.isDeclarant()).isTrue();
        assertThat(saved.getWitnessName()).isEqualTo("Sam Doe");
        assertThat(saved.getWitnessAddress()).isEqualTo("1 High St");
    }

    @Test
    @DisplayName("an empty patch leaves the four fields alone but still moves modifiedOn")
    void anEmptyPatchChangesNothingButTheTimestamp() throws InterruptedException {
        // Not a reference block: there the schema test proves an empty patch parses and the service test
        // stops there. A witness has no author column to move, so an empty patch touches no field of its
        // own -- but the reference's `prisma.update` writes @updatedAt on every call, so modifiedOn moves
        // regardless. Leaving that to @PreUpdate would not: Hibernate only fires it when its dirty check
        // finds a changed property, so the whole UPDATE would silently be skipped.
        String caseId = storedCase();
        String witnessId = witnessService.createWitness(caseId, create()).getId();
        final LocalDateTime createdOn = stored(witnessId).getCreatedOn();
        // The column is timestamp(3), so two writes inside the same millisecond would store the same value
        // and the assertion below would be a coin toss rather than a test.
        Thread.sleep(2);

        witnessService.updateWitness(caseId, witnessId, UpdateWitnessInput.EMPTY);

        DeedPollWitness saved = stored(witnessId);
        assertThat(saved.getWitnessName()).isEqualTo("Sam Doe");
        assertThat(saved.getWitnessAddress()).isEqualTo("1 High St");
        assertThat(saved.getWitnessOccupation()).isEqualTo("Teacher");
        assertThat(saved.isDeclarant()).isFalse();
        assertThat(saved.getCreatedOn()).isEqualTo(createdOn);
        assertThat(saved.getModifiedOn()).isAfter(createdOn);
    }

    @Test
    @DisplayName("refuses to remove a witness through another case, and leaves it stored")
    void refusesToRemoveAWitnessThroughAnotherCase() {
        // Not a reference block -- there, the delete's own `where` carries deedPollId and the guard is
        // asserted only on the read paths. Here the scope is what the delete is given, so it is worth
        // asserting that a wrong parent cannot delete somebody else's witness.
        String owner = storedCase();
        String other = storedCase();
        String witnessId = witnessService.createWitness(owner, create()).getId();

        assertThatThrownBy(() -> witnessService.removeWitness(other, witnessId, ADMIN))
            .isInstanceOf(NotFoundError.class);

        assertThat(witnesses.findById(witnessId)).isPresent();
    }

    @Test
    @DisplayName("refuses a delete by a caller without the Administrator role, and leaves the witness stored")
    void refusesADeleteByACallerWithoutTheAdministratorRole() {
        // Not a reference block: the reference has no authorisation at all in this service. The business
        // splits editing from deleting -- both roles may add and amend a witness, and "Delete witnesses" is
        // marked for Administrators alone -- and the rule lives here rather than on the route so that a caller
        // which is not that route is held to it too.
        String caseId = storedCase();
        String witnessId = witnessService.createWitness(caseId, create()).getId();

        assertThatThrownBy(() -> witnessService.removeWitness(caseId, witnessId, CASEWORKER))
            .isInstanceOf(ForbiddenError.class);

        // The assertion that carries the test. The refusal alone would also pass against an implementation
        // that threw *after* deleting, which is the failure that could not be undone.
        assertThat(witnesses.findById(witnessId)).isPresent();
    }

    @Test
    @DisplayName("refuses a delete by a caller with no roles at all, rather than falling open")
    void refusesADeleteByACallerWithNoRolesAtAll() {
        // The fail-closed case. requireAnyRole is open when *nothing* is required, which is why removeWitness
        // names the role explicitly; this asserts the other axis -- a caller holding nothing, the state a bug
        // in authentication or a token whose roles all belong to other services produces.
        String caseId = storedCase();
        String witnessId = witnessService.createWitness(caseId, create()).getId();

        assertThatThrownBy(() -> witnessService.removeWitness(caseId, witnessId, CurrentUser.ANONYMOUS))
            .isInstanceOf(ForbiddenError.class);

        assertThat(witnesses.findById(witnessId)).isPresent();
    }

    @Test
    @DisplayName("404s an Administrator deleting a witness that does not exist, so the check did not swallow it")
    void administratorDeletingAMissingWitnessStillGets404() {
        // The role check runs first, so it has to be shown not to have absorbed the not-found: an admin
        // addressing a witness that was never stored still gets the absence, not a success.
        assertThatThrownBy(() -> witnessService.removeWitness(storedCase(), "nope", ADMIN))
            .isInstanceOf(NotFoundError.class);
    }

    @Test
    @DisplayName("refuses first and resolves second, so a missing witness is a refusal rather than a 404")
    void refusesBeforeResolving() {
        // Order is the point, and this is the test that pins it: without it a later refactor can swap the two
        // lines and nothing complains. Resolving first would let a caller without the role tell an existing
        // witness from a non-existent one by which error came back -- an existence oracle over rows they may
        // not touch. Paired with the test above: the same missing witness is a 404 for an Administrator and a
        // refusal for everybody else.
        assertThatThrownBy(() -> witnessService.removeWitness(storedCase(), "nope", CASEWORKER))
            .isInstanceOf(ForbiddenError.class);
    }

    @Test
    @DisplayName("deletes the last witness on a case, since no minimum is enforced")
    void deletesTheLastWitnessOnACase() {
        // No artefact states a minimum number of witnesses -- checked DeedPollValidation, the reference's
        // deed-poll-validation.ts and the Dataverse business rules, none of which mention witnesses -- so the
        // absence of a rule is asserted rather than left to be discovered. If the business says there is a
        // minimum, this is the test that has to change, which is what makes the decision visible.
        String caseId = storedCase();
        String only = witnessService.createWitness(caseId, create()).getId();

        witnessService.removeWitness(caseId, only, ADMIN);

        assertThat(witnessService.findWitnesses(caseId)).isEmpty();
    }

    @Test
    @DisplayName("a case's witnesses collection sees a witness added through the service")
    void theCasesCollectionSeesAWitnessAddedThroughTheService() {
        // Not a reference block: Prisma has no managed-collection equivalent. The case detail page reads
        // witnesses off DeedPoll.witnesses rather than through this service, so the two paths must agree --
        // and a mapping that wrote the foreign key without the relation being navigable would pass every
        // other test here.
        String caseId = storedCase();
        witnessService.createWitness(caseId, create());

        assertThat(deedPollService.findDeedPoll(caseId).deedPoll().getWitnesses())
            .extracting(DeedPollWitness::getWitnessName)
            .containsExactly("Sam Doe");
    }

    /** Backdates a stored witness, so an ordering assertion does not depend on the clock's resolution. */
    private void setCreatedOn(String witnessId, LocalDateTime createdOn) {
        DeedPollWitness witness = stored(witnessId);
        witness.setCreatedOn(createdOn);
        witnesses.saveAll(List.of(witness));
        witnesses.flush();
    }
}
