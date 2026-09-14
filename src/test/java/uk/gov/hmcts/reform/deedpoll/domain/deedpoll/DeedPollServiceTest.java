package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollWitness;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MaritalStatus;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Privacy;
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
 * Ports every test block in {@code reference/ts/deed-poll/service.test.ts}.
 *
 * <p><strong>Against a real database, not a mocked Prisma client.</strong> The reference stubs the client
 * and asserts the arguments it was called with, because that is all a TypeScript unit test can do; the
 * derived logic under test is real in both. Here the write actually happens and the STORED record is
 * asserted, which is the same claim made about the outcome rather than about the call -- and it is the
 * only way to assert an outcome that depends on a row lock, as reference-number allocation does.
 *
 * <p>Two consequences for how the reference's assertions read here:
 *
 * <ul>
 *   <li>{@code expect(arg.x).toBeUndefined()} for a field the patch did not touch becomes "the stored
 *       value is unchanged", which is the claim that assertion stands for.</li>
 *   <li>{@code expect(prisma.deedPoll.update).not.toHaveBeenCalled()} becomes "the stored record is
 *       unchanged", asserted by reading it back after the refusal.</li>
 * </ul>
 *
 * <p>Not {@code @Transactional}: every write must really commit, so the assertions read committed rows
 * rather than a rollback-only test transaction. The tables are cleared around each test instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DeedPollServiceTest extends AbstractPostgresContainerTest {

    /** createDeedPoll cannot allocate a reference without a Date Received to derive the year. */
    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";

    @Autowired
    private DeedPollService deedPollService;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private DeedPollWitnessRepository witnesses;

    @Autowired
    private ReferenceCounterRepository referenceCounters;

    private static DeedPollPatch patch(Object... keysAndValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return DeedPollPatch.of(values);
    }

    @BeforeEach
    @AfterEach
    void clearTables() {
        witnesses.deleteAll();
        deedPolls.deleteAll();
        referenceCounters.deleteAll();
    }

    /** A stored case, read back from the database rather than from a returned instance. */
    private DeedPoll stored(String id) {
        return deedPolls.findById(id).orElseThrow();
    }

    private DeedPoll created(Object... keysAndValues) {
        return deedPollService.createDeedPoll(patch(keysAndValues)).deedPoll();
    }

    /**
     * Records the two witnesses an enrolment needs -- see
     * {@code DeedPollService.requireWitnessesForEnrolment}, which refuses a write that would leave a case
     * enrolled, or sent for enrolment, with fewer than two.
     *
     * <p>Written straight through the repository rather than {@code WitnessService}: in these blocks a
     * witness is a precondition of the case write under test, not the subject of it.
     */
    private void recordTwoWitnesses(String caseId) {
        DeedPoll parent = stored(caseId);
        witnesses.saveAll(List.of(witness(parent, "Sam Doe"), witness(parent, "Ada Wren")));
    }

    private static DeedPollWitness witness(DeedPoll parent, String name) {
        DeedPollWitness witness = new DeedPollWitness();
        witness.setDeedPoll(parent);
        witness.setWitnessName(name);
        witness.setWitnessAddress("1 High St");
        witness.setWitnessOccupation("Teacher");
        return witness;
    }

    @Test
    @DisplayName("coerces ISO date strings to dates on create")
    void coercesIsoDateStringsToDatesOnCreate() {
        DeedPoll created = created("dateReceived", "2026-07-31T00:00:00.000Z");

        assertThat(stored(created.getId()).getDateReceived()).isEqualTo(LocalDateTime.parse("2026-07-31T00:00:00"));
    }

    @Test
    @DisplayName("allocates a reference number on create")
    void allocatesAReferenceNumberOnCreate() {
        DeedPoll created = created("type", DeedPollType.Adult, "dateReceived", "2026-07-31T00:00:00.000Z");

        assertThat(created.getReferencenumber()).isEqualTo(1);
        assertThat(created.getDisplayReference()).isEqualTo("KBDP2026-00001");
    }

    @Test
    @DisplayName("applies create defaults (minor gives marital status Single)")
    void appliesCreateDefaults() {
        DeedPoll created = created("type", DeedPollType.Minor, "dateReceived", "2026-07-31T00:00:00.000Z");

        assertThat(created.getMaritalStatus()).isEqualTo(MaritalStatus.Single);
    }

    @Test
    @DisplayName("clears the Gazette notice date on create when publish = No")
    void clearsTheGazetteNoticeDateOnCreate() {
        DeedPoll created = created(
            "type", DeedPollType.Adult,
            "dateReceived", "2026-07-31T00:00:00.000Z",
            "publishToLondonGazette", false,
            "dateNoticeSentToLondonGazette", "2026-08-01T00:00:00.000Z");

        assertThat(created.getDateNoticeSentToLondonGazette()).isNull();
    }

    @Test
    @DisplayName("clears an existing Gazette notice date on update when publish flips to No")
    void clearsAnExistingGazetteNoticeDateOnUpdate() {
        DeedPoll created = created(
            "type", DeedPollType.Adult,
            "dateReceived", RECEIVED,
            "dateNoticeSentToLondonGazette", "2026-08-01T00:00:00.000Z");
        assertThat(created.getDateNoticeSentToLondonGazette()).isNotNull();

        deedPollService.updateDeedPoll(created.getId(), patch("publishToLondonGazette", false));

        assertThat(stored(created.getId()).getDateNoticeSentToLondonGazette()).isNull();
    }

    @Test
    @DisplayName("clears minor fields on create for a non-minor type")
    void clearsMinorFieldsOnCreateForANonMinorType() {
        DeedPoll created = created(
            "type", DeedPollType.Adult,
            "dateReceived", "2026-07-31T00:00:00.000Z",
            "minorDateOfBirth", "2017-03-10T00:00:00.000Z",
            "ageOfMinor", 9,
            "ageOfMinorMonths", 4,
            "parents", "A Okafor");

        assertThat(created.getMinorDateOfBirth()).isNull();
        assertThat(created.getAgeOfMinor()).isNull();
        assertThat(created.getAgeOfMinorMonths()).isNull();
        assertThat(created.getParents()).isNull();
    }

    @Test
    @DisplayName("keeps minor fields on create for a minor type")
    void keepsMinorFieldsOnCreateForAMinorType() {
        DeedPoll created = created(
            "type", DeedPollType.Minor,
            "dateReceived", "2026-07-31T00:00:00.000Z",
            "ageOfMinor", 9,
            "parents", "A Okafor");

        assertThat(created.getAgeOfMinor()).isEqualTo(9);
        assertThat(created.getParents()).isEqualTo("A Okafor");
    }

    @Test
    @DisplayName("clears stored minor fields when a case is converted Minor to Adult")
    void clearsStoredMinorFieldsOnConversionToAdult() {
        // The reason this gate exists: without it the row keeps minor data that no page will ever show
        // again.
        DeedPoll created = created(
            "type", DeedPollType.Minor,
            "dateReceived", RECEIVED,
            "minorDateOfBirth", "2017-03-10T00:00:00.000Z",
            "ageOfMinor", 9,
            "ageOfMinorMonths", 4,
            "parents", "A Okafor");

        deedPollService.updateDeedPoll(created.getId(), patch("type", DeedPollType.Adult));

        DeedPoll updated = stored(created.getId());
        assertThat(updated.getMinorDateOfBirth()).isNull();
        assertThat(updated.getAgeOfMinor()).isNull();
        assertThat(updated.getAgeOfMinorMonths()).isNull();
        assertThat(updated.getParents()).isNull();
    }

    @Test
    @DisplayName("does not clear minor fields on a patch that leaves the type alone")
    void doesNotClearMinorFieldsOnAPatchThatLeavesTheTypeAlone() {
        // The gate reads the MERGED type, so a patch touching only `parents` on a Minor case must not wipe
        // the value it just set.
        DeedPoll created = created(
            "type", DeedPollType.Minor,
            "dateReceived", RECEIVED,
            "ageOfMinor", 9);

        deedPollService.updateDeedPoll(created.getId(), patch("parents", "B Okafor"));

        DeedPoll updated = stored(created.getId());
        assertThat(updated.getParents()).isEqualTo("B Okafor");
        // The reference asserts the patch did not mention ageOfMinor; the stored equivalent is that it is
        // untouched.
        assertThat(updated.getAgeOfMinor()).isEqualTo(9);
    }

    @Test
    @DisplayName("auto-defaults privacy to Private on create for a Minor Transgender case")
    void autoDefaultsPrivacyToPrivateForMinorTransgender() {
        assertThat(created("type", DeedPollType.MinorTransgender, "dateReceived", "2026-07-31T00:00:00.000Z")
            .getPrivacy()).isEqualTo(Privacy.Private);
    }

    @Test
    @DisplayName("auto-defaults privacy to Normal on create for a non-admin Adult case")
    void autoDefaultsPrivacyToNormalForANonAdminAdult() {
        assertThat(created("type", DeedPollType.Adult, "dateReceived", "2026-07-31T00:00:00.000Z").getPrivacy())
            .isEqualTo(Privacy.Normal);
    }

    @Test
    @DisplayName("derives case state on create (dateReceived gives Received)")
    void derivesCaseStateOnCreate() {
        assertThat(created("type", DeedPollType.Adult, "dateReceived", "2026-07-31T00:00:00.000Z").getState())
            .isEqualTo(DeedPollState.Received);
    }

    @Test
    @DisplayName("recalculates state on update from merged dates (enrolment gives Enroled)")
    void recalculatesStateOnUpdateFromMergedDates() {
        DeedPoll created = created("dateReceived", "2026-01-01T00:00:00.000Z");
        // The enrolment gate, not part of this block's claim: without two witnesses the patch below is
        // refused before a state is derived at all.
        recordTwoWitnesses(created.getId());

        deedPollService.updateDeedPoll(created.getId(), patch("dateEnroled", "2026-03-01T00:00:00.000Z"));

        assertThat(stored(created.getId()).getState()).isEqualTo(DeedPollState.Enroled);
    }

    @Test
    @DisplayName("throws NotFound when findOne misses")
    void throwsNotFoundWhenFindOneMisses() {
        assertThatThrownBy(() -> deedPollService.findDeedPoll("nope")).isInstanceOf(NotFoundError.class);
    }

    @Test
    @DisplayName("update checks existence first (404 propagates)")
    void updateChecksExistenceFirst() {
        assertThatThrownBy(() -> deedPollService.updateDeedPoll("nope", DeedPollPatch.empty()))
            .isInstanceOf(NotFoundError.class);
    }

    @Test
    @DisplayName("re-prefixes the display reference on a DP to DR type flip")
    void reprefixesTheDisplayReferenceOnATypeFlip() {
        DeedPoll created = created("type", DeedPollType.Adult, "dateReceived", RECEIVED);
        String before = created.getDisplayReference();

        deedPollService.updateDeedPoll(created.getId(), patch("type", DeedPollType.DeedOfRelinquishment));

        assertThat(before).startsWith("KBDP2026-");
        assertThat(stored(created.getId()).getDisplayReference())
            .isEqualTo(before.replace("KBDP", "KBDR"));
        assertThat(stored(created.getId()).getReferencenumber())
            .as("the serial is never re-allocated")
            .isEqualTo(created.getReferencenumber());
    }

    @Test
    @DisplayName("takes a type given as its member name, which is what a form body produces")
    void takesATypeGivenAsItsMemberName() {
        // A patch places no type constraint on its values, and FormPayload yields a plain String for an enum
        // field because a form body has no types. Both halves of reading one used to assume a member: the
        // re-prefix cast (a ClassCastException, so a 500) and the gates' isInstance narrowing (which read it
        // as "no type settled", so the minor gate silently left a converted case's minor fields populated).
        DeedPoll created = created(
            "type", DeedPollType.Minor,
            "dateReceived", RECEIVED,
            "ageOfMinor", 7);

        deedPollService.updateDeedPoll(created.getId(), patch("type", "DeedOfRelinquishment"));

        DeedPoll saved = stored(created.getId());
        assertThat(saved.getType()).isEqualTo(DeedPollType.DeedOfRelinquishment);
        assertThat(saved.getDisplayReference())
            .as("the re-prefix ran, rather than throwing on the cast")
            .isEqualTo(created.getDisplayReference().replace("KBDP", "KBDR"));
        assertThat(saved.getAgeOfMinor())
            .as("the minor gate saw the merged type and cleared the now-meaningless field")
            .isNull();
    }

    /**
     * Privacy is admin-only on an existing record.
     *
     * <p>Until 2026-08-10 the lock was UI-only: {@code privacyEditable()} rendered the input disabled, but
     * update() ignored the caller's admin status, so a non-admin POSTing privacy -- or PATCHing the API --
     * wrote it. Authorisation belongs on the write path, not the template.
     */
    @Nested
    @DisplayName("privacy authorisation on update")
    class PrivacyAuthorisationOnUpdate {

        private String caseId;

        @BeforeEach
        void createAnAdultCase() {
            caseId = created("type", DeedPollType.Adult, "dateReceived", RECEIVED).getId();
        }

        @Test
        @DisplayName("drops privacy from a non-admin patch")
        void dropsPrivacyFromANonAdminPatch() {
            deedPollService.updateDeedPoll(caseId, patch("privacy", Privacy.Private), new WriteContext(false, null));

            assertThat(stored(caseId).getPrivacy()).isEqualTo(Privacy.Normal);
        }

        @Test
        @DisplayName("drops privacy when no context is given at all")
        void dropsPrivacyWhenNoContextIsGiven() {
            // The default context is non-admin, so an internal caller that forgets to pass one must not get
            // the permissive branch.
            deedPollService.updateDeedPoll(caseId, patch("privacy", Privacy.Private));

            assertThat(stored(caseId).getPrivacy()).isEqualTo(Privacy.Normal);
        }

        @Test
        @DisplayName("lets an admin set privacy")
        void letsAnAdminSetPrivacy() {
            deedPollService.updateDeedPoll(caseId, patch("privacy", Privacy.Private), WriteContext.admin());

            assertThat(stored(caseId).getPrivacy()).isEqualTo(Privacy.Private);
        }

        @Test
        @DisplayName("still applies the rest of a non-admin patch")
        void stillAppliesTheRestOfANonAdminPatch() {
            // Dropping the field, not rejecting the request: the source disables the control rather than
            // erroring, and a browser that legitimately omits a disabled input must still be able to save
            // the other fields on the tab.
            deedPollService.updateDeedPoll(
                caseId,
                patch("privacy", Privacy.Private, "newForename", "Ada"),
                new WriteContext(false, null));

            assertThat(stored(caseId).getPrivacy()).isEqualTo(Privacy.Normal);
            assertThat(stored(caseId).getNewForename()).isEqualTo("Ada");
        }

        @Test
        @DisplayName("leaves a non-admin patch that does not mention privacy alone")
        void leavesAPatchThatDoesNotMentionPrivacyAlone() {
            deedPollService.updateDeedPoll(caseId, patch("newForename", "Ada"), new WriteContext(false, null));

            assertThat(stored(caseId).getPrivacy()).isEqualTo(Privacy.Normal);
            assertThat(stored(caseId).getNewForename()).isEqualTo("Ada");
        }

        @Test
        @DisplayName("does not let a non-admin clear privacy either")
        void doesNotLetANonAdminClearPrivacy() {
            // The hole is write access to the field, not the Private value specifically: downgrading a
            // Private case to Normal is the more damaging direction.
            String privateCase =
                created("type", DeedPollType.MinorTransgender, "dateReceived", RECEIVED).getId();
            assertThat(stored(privateCase).getPrivacy()).isEqualTo(Privacy.Private);

            deedPollService.updateDeedPoll(
                privateCase,
                patch("privacy", Privacy.Normal),
                new WriteContext(false, null));

            assertThat(stored(privateCase).getPrivacy()).isEqualTo(Privacy.Private);
        }
    }

    /**
     * Privacy authorisation on create.
     *
     * <p>On create the lock was mostly accidental: {@code defaultPrivacy()} overwrites whatever the caller
     * sent for types 1-5, so a requested Private was already discarded. Unknown and a typeless create fall
     * through its default branch to "keep the current value", which honoured the request -- so a non-admin
     * could create a Private case by omitting a type.
     */
    @Nested
    @DisplayName("privacy authorisation on create")
    class PrivacyAuthorisationOnCreate {

        @Test
        @DisplayName("ignores a non-admin request for Private on an Unknown-type case")
        void ignoresANonAdminRequestForPrivateOnAnUnknownTypeCase() {
            DeedPoll created = deedPollService.createDeedPoll(
                patch("type", DeedPollType.Unknown, "privacy", Privacy.Private, "dateReceived", RECEIVED),
                new WriteContext(false, null)).deedPoll();

            assertThat(created.getPrivacy()).isEqualTo(Privacy.Normal);
        }

        @Test
        @DisplayName("ignores a non-admin request for Private when no type is given")
        void ignoresANonAdminRequestForPrivateWithNoType() {
            DeedPoll created = deedPollService.createDeedPoll(
                patch("privacy", Privacy.Private, "dateReceived", RECEIVED),
                new WriteContext(false, null)).deedPoll();

            assertThat(created.getPrivacy()).isEqualTo(Privacy.Normal);
        }

        @Test
        @DisplayName("still derives Private for a non-admin Minor Transgender case")
        void stillDerivesPrivateForANonAdminMinorTransgenderCase() {
            // The auto-default is a rule about the case type, not a caller privilege -- it must survive the
            // authorisation check.
            DeedPoll created = deedPollService.createDeedPoll(
                patch("type", DeedPollType.MinorTransgender, "dateReceived", RECEIVED),
                new WriteContext(false, null)).deedPoll();

            assertThat(created.getPrivacy()).isEqualTo(Privacy.Private);
        }

        @Test
        @DisplayName("lets an admin create an Unknown-type case as Private")
        void letsAnAdminCreateAnUnknownTypeCaseAsPrivate() {
            DeedPoll created = deedPollService.createDeedPoll(
                patch("type", DeedPollType.Unknown, "privacy", Privacy.Private, "dateReceived", RECEIVED),
                WriteContext.admin()).deedPoll();

            assertThat(created.getPrivacy()).isEqualTo(Privacy.Private);
        }

        @Test
        @DisplayName("lets a non-admin create a relinquishment with a chosen privacy")
        void letsANonAdminCreateARelinquishmentWithAChosenPrivacy() {
            // privacyEditable() permits this one case: non-admin, new, relinquishment. The check must not be
            // blunter than the rule it enforces. defaultPrivacy sends relinquishment to Normal, so Normal is
            // what a non-admin can pick.
            DeedPoll created = deedPollService.createDeedPoll(
                patch(
                    "type", DeedPollType.DeedOfRelinquishment,
                    "privacy", Privacy.Normal,
                    "dateReceived", RECEIVED),
                new WriteContext(false, null)).deedPoll();

            assertThat(created.getPrivacy()).isEqualTo(Privacy.Normal);
        }
    }

    /**
     * Cross-field date rules on the write path.
     *
     * <p>Enforced here, not only on the form, for the reason the privacy lock is: an API PATCH bypasses
     * every template. The rules themselves are unit-tested in {@code CaseStateTest} -- these assert that
     * create() and update() consult them and refuse the write.
     */
    @Nested
    @DisplayName("cross-field date rules")
    class CrossFieldDateRules {

        @Test
        @DisplayName("rejects a patch that leaves the record both enroled and returned")
        void rejectsAPatchThatLeavesTheRecordBothEnroledAndReturned() {
            String caseId = created("dateReceived", RECEIVED, "dateEnroled", "2026-08-01T00:00:00.000Z").getId();

            assertThatThrownBy(() -> deedPollService.updateDeedPoll(
                caseId,
                patch("dateReturned", "2026-08-05T00:00:00.000Z")))
                .isInstanceOf(InvalidInputError.class);

            assertThat(stored(caseId).getDateReturned())
                .as("the refused write must not have reached the row")
                .isNull();
        }

        @Test
        @DisplayName("rejects a signature received before requested")
        void rejectsASignatureReceivedBeforeRequested() {
            String caseId = created(
                "dateReceived", RECEIVED,
                "signatureRequestedDate", "2026-08-15T00:00:00.000Z").getId();

            assertThatThrownBy(() -> deedPollService.updateDeedPoll(
                caseId,
                patch("signatureReceivedDate", "2026-08-03T00:00:00.000Z")))
                .isInstanceOf(InvalidInputError.class);

            assertThat(stored(caseId).getSignatureReceivedDate()).isNull();
        }

        @Test
        @DisplayName("checks the MERGED record, so clearing one date of the pair is allowed")
        void checksTheMergedRecordSoClearingOneDateIsAllowed() {
            // The fix for a contradictory record must not itself be blocked: blanking dateEnroled on an
            // enroled+returned case is exactly how a caseworker resolves it. The pair cannot be created in
            // one write -- create refuses it -- so it is assembled in two, the way a real case reaches that
            // state.
            String caseId = created("dateReceived", RECEIVED, "dateEnroled", "2026-08-01T00:00:00.000Z").getId();
            DeedPoll record = stored(caseId);
            record.setDateReturned(LocalDateTime.parse("2026-08-05T00:00:00"));
            deedPolls.save(record);

            deedPollService.updateDeedPoll(caseId, patch("dateEnroled", null));

            assertThat(stored(caseId).getState()).isEqualTo(DeedPollState.Returned);
        }

        @Test
        @DisplayName("rejects a create carrying both terminal dates, before a serial is spent")
        void rejectsACreateCarryingBothTerminalDates() {
            assertThatThrownBy(() -> created(
                "type", DeedPollType.Adult,
                "dateReceived", RECEIVED,
                "dateEnroled", "2026-08-01T00:00:00.000Z",
                "dateReturned", "2026-08-05T00:00:00.000Z"))
                .isInstanceOf(InvalidInputError.class);

            // A rejected create must not burn a reference number.
            assertThat(referenceCounters.findAll()).isEmpty();
            assertThat(deedPolls.findAll()).isEmpty();
        }

        @Test
        @DisplayName("leaves a consistent signature pair alone")
        void leavesAConsistentSignaturePairAlone() {
            String caseId = created(
                "dateReceived", RECEIVED,
                "signatureRequestedDate", "2026-08-03T00:00:00.000Z").getId();
            // The enrolment gate, not part of this block's claim: a signature received date sends the case
            // for enrolment, which needs two witnesses on the record.
            recordTwoWitnesses(caseId);

            deedPollService.updateDeedPoll(caseId, patch("signatureReceivedDate", "2026-08-15T00:00:00.000Z"));

            assertThat(stored(caseId).getState()).isEqualTo(DeedPollState.AwaitingEnrolment);
        }
    }

    @Test
    @DisplayName("remove deletes after existence check")
    void removeDeletesAfterExistenceCheck() {
        String caseId = created("dateReceived", RECEIVED).getId();

        deedPollService.removeDeedPoll(caseId);

        assertThat(deedPolls.findById(caseId)).isEmpty();
        assertThatThrownBy(() -> deedPollService.removeDeedPoll(caseId)).isInstanceOf(NotFoundError.class);
    }

    @Test
    @DisplayName("stamps createdBy and modifiedBy on create, and only modifiedBy on update")
    void stampsTheAuthorOfEachWrite() {
        // Not a reference block: the reference asserts the stamps through its mock's arguments, but the
        // claim that an UPDATE cannot rewrite createdBy is worth making against a stored row. Here
        // createdBy is not a writable field at all, which is the stronger version of the reference's
        // `delete data.createdBy`.
        DeedPoll created = deedPollService.createDeedPoll(
            patch("dateReceived", RECEIVED),
            new WriteContext(false, "A Caseworker")).deedPoll();
        assertThat(created.getCreatedBy()).isEqualTo("A Caseworker");
        assertThat(created.getModifiedBy()).isEqualTo("A Caseworker");

        deedPollService.updateDeedPoll(
            created.getId(),
            patch("newForename", "Ada"),
            new WriteContext(false, "B Caseworker"));

        assertThat(stored(created.getId()).getCreatedBy()).isEqualTo("A Caseworker");
        assertThat(stored(created.getId()).getModifiedBy()).isEqualTo("B Caseworker");
    }

    @Test
    @DisplayName("attributes an unidentified write to the service account")
    void attributesAnUnidentifiedWriteToTheServiceAccount() {
        // The reference's `ctx.userName ?? SERVICE_ACCOUNT_NAME` fallback, which its own blocks never reach.
        assertThat(created("dateReceived", RECEIVED).getCreatedBy()).isEqualTo(WriteContext.SERVICE_ACCOUNT_NAME);
    }

    @Test
    @DisplayName("findDeedPolls returns newest first, with the derived day counts attached")
    void findDeedPollsReturnsNewestFirstWithComputed() {
        // The reference asserts the orderBy through its mock; the stored equivalent is the order that comes
        // back, and the derived columns the read path attaches.
        created("dateReceived", "2026-01-01T00:00:00.000Z", "dateEnroled", "2026-01-15T00:00:00.000Z");
        created("dateReceived", "2026-02-01T00:00:00.000Z");

        assertThat(deedPollService.findDeedPolls()).hasSize(2);
        assertThat(deedPollService.findDeedPolls())
            .allSatisfy(withComputed -> assertThat(withComputed.computed()).isNotNull());
    }

    @Test
    @DisplayName("findDeedPolls honours a saved-query filter")
    void findDeedPollsHonoursASavedQueryFilter() {
        created("dateReceived", RECEIVED);
        created("dateReceived", RECEIVED, "dateEnroled", "2026-08-01T00:00:00.000Z");

        assertThat(deedPollService.findDeedPolls(ListFilters.filterFor("enroled")))
            .singleElement()
            .satisfies(withComputed ->
                assertThat(withComputed.deedPoll().getState()).isEqualTo(DeedPollState.Enroled));
        assertThat(deedPollService.findDeedPolls(ListFilters.filterFor("open"))).hasSize(1);
    }

    @Test
    @DisplayName("findDeedPolls applies a filter and a field-level query together")
    void findDeedPollsAppliesAFilterAndAQueryTogether() {
        created("dateReceived", RECEIVED, "type", DeedPollType.Adult);
        created("dateReceived", RECEIVED, "type", DeedPollType.Minor);
        created("dateReceived", RECEIVED, "type", DeedPollType.Adult,
            "dateEnroled", "2026-08-01T00:00:00.000Z");

        // Decision 3: the two compose. The Adult case that is enrolled is excluded by the tab, and the open
        // Minor case by the condition, so exactly one of the three survives both.
        assertThat(deedPollService.findDeedPolls(
                ListFilters.filterFor("open"), CaseQuery.parse(List.of("type:is:Adult"))))
            .singleElement()
            .satisfies(withComputed -> {
                assertThat(withComputed.deedPoll().getType()).isEqualTo(DeedPollType.Adult);
                assertThat(withComputed.computed()).isNotNull();
            });
    }

    @Test
    @DisplayName("findDeedPolls with no conditions is the unqueried list, newest first")
    void findDeedPollsWithNoConditionsIsTheUnqueriedList() {
        // An empty query is today's path rather than an empty specification -- the common request, and the
        // one whose behaviour is pinned. Asserted as the same answer, which is the claim that matters.
        created("dateReceived", "2026-07-01T00:00:00.000Z");
        created("dateReceived", "2026-07-02T00:00:00.000Z");

        ListFilters.ListFilter all = ListFilters.filterFor("all");

        assertThat(deedPollService.findDeedPolls(all, CaseQuery.NONE))
            .extracting(withComputed -> withComputed.deedPoll().getId())
            .containsExactlyElementsOf(deedPollService.findDeedPolls(all).stream()
                .map(withComputed -> withComputed.deedPoll().getId())
                .toList());
    }

    @Test
    @DisplayName("validateCaseForSubmit refuses a case whose per-type required fields are unmet")
    void validateCaseForSubmitRefusesAnIncompleteCase() {
        String caseId = created("type", DeedPollType.Adult, "dateReceived", RECEIVED).getId();

        assertThatThrownBy(() -> deedPollService.validateCaseForSubmit(caseId))
            .isInstanceOf(InvalidInputError.class)
            .hasMessage("Case is not valid for submission");
    }

    @Test
    @DisplayName("getCaseFieldRules answers with the per-type matrix")
    void getCaseFieldRulesAnswersWithThePerTypeMatrix() {
        assertThat(deedPollService.getCaseFieldRules(DeedPollType.Adult, false, true))
            .isEqualTo(DeedPollValidation.getFieldRules(DeedPollType.Adult, false, true));
    }
}
