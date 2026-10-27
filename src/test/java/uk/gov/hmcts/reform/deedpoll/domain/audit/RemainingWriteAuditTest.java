package uk.gov.hmcts.reform.deedpoll.domain.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdAttemptRepairs;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdCreationAttempts;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.PatchField;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.CreateDocumentInput;
import uk.gov.hmcts.reform.deedpoll.domain.documents.CreateDocumentTypeInput;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentTypeService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UpdateDocumentInput;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteSettingsService;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ConfirmationInput;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.CreateReferralInput;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.DirectionInput;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralReason;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralService;
import uk.gov.hmcts.reform.deedpoll.domain.savedqueries.CreateSavedQueryInput;
import uk.gov.hmcts.reform.deedpoll.domain.savedqueries.SavedQueryDefinition;
import uk.gov.hmcts.reform.deedpoll.domain.savedqueries.SavedQueryService;
import uk.gov.hmcts.reform.deedpoll.domain.savedqueries.UpdateSavedQueryInput;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettingsService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ConfirmationOutcome;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The referral, judicial, saved-query, Gazette settings, process settings, CCD repair and document metadata writes,
 * and a case delete, each record one audit event in their own transaction.
 *
 * <p>Rolled back, as {@code CaseWriteAuditTest} is, because V36 refuses the {@code DELETE} any other cleanup would
 * need. The email send and the Gazette settlement commit their own transactions, so {@code SendIdempotencyTest} and
 * {@code GazetteRequestLedgerTest} assert theirs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class RemainingWriteAuditTest extends AbstractPostgresContainerTest {

    private static final String SUBJECT = "remaining-writes-subject";
    private static final String NAME = "A Team Leader";
    private static final CurrentUser TEAM_LEADER =
        CurrentUser.currentUser(List.of(Role.TEAM_LEADER), NAME, SUBJECT);
    private static final CurrentUser DELIVERY_MANAGER =
        CurrentUser.currentUser(List.of(Role.DELIVERY_MANAGER), "A Delivery Manager", "delivery-manager-subject");
    private static final CurrentUser JUDGE =
        CurrentUser.currentUser(List.of(Role.JUDICIAL), "A Judge", "judge-subject");

    @Autowired
    private DeedPollService cases;

    @Autowired
    private ReferralService referrals;

    @Autowired
    private SavedQueryService savedQueries;

    @Autowired
    private GazetteSettingsService gazetteSettings;

    @Autowired
    private ProcessSettingsService processSettings;

    @Autowired
    private CcdCreationAttempts attempts;

    @Autowired
    private CcdAttemptRepairs repairs;

    @Autowired
    private DocumentService documents;

    @Autowired
    private DocumentTypeService documentTypes;

    @Autowired
    private JdbcTemplate jdbc;

    @Nested
    @DisplayName("a referral")
    class Referrals {

        @Test
        @DisplayName("create and withdraw record the caller, the columns set, their values and the transition")
        void createAndWithdraw() {
            String caseId = createdCase();

            String referralId = raised(caseId);
            referrals.withdrawReferral(caseId, referralId, TEAM_LEADER);

            List<Event> events = referralEvents(caseId);
            assertThat(events).extracting(event -> event.row().get("action"))
                .containsExactly("REFERRAL_CREATED", "REFERRAL_WITHDRAWN");
            assertThat(events).allSatisfy(event -> assertThat(event.row())
                .containsEntry("entity_id", referralId)
                .containsEntry("actor_id", SUBJECT)
                .containsEntry("actor_name", NAME));
            assertThat(events.get(0).row())
                .containsEntry("fields", "state,reasons,notes,urgency,urgencyReason")
                .containsEntry("from_state", null)
                .containsEntry("to_state", "Open");
            assertThat(events.get(0).values()).containsExactly(
                Arrays.asList("notes", null, "Needs a Master"),
                Arrays.asList("reasons", null, "Minor,NonPublication"),
                Arrays.asList("state", null, "Open"),
                Arrays.asList("urgency", null, "Normal"));
            assertThat(events.get(1).row())
                .containsEntry("fields", "state")
                .containsEntry("from_state", "Open")
                .containsEntry("to_state", "Withdrawn");
            assertThat(events.get(1).values()).containsExactly(List.of("state", "Open", "Withdrawn"));
        }

        @Test
        @DisplayName("claim and direction are the judge's, and close is the caseworker's")
        void claimDirectAndClose() {
            String caseId = createdCase();
            String referralId = raised(caseId);

            referrals.claim(caseId, referralId, JUDGE);
            referrals.decide(caseId, referralId,
                             new DirectionInput(MasterDecision.Approved, Optional.of("Proceed"), true), JUDGE);
            referrals.close(caseId, referralId, TEAM_LEADER);

            List<Event> events = referralEvents(caseId).subList(1, 4);
            assertThat(events).extracting(event -> event.row().get("action"))
                .containsExactly("REFERRAL_CLAIMED", "REFERRAL_DIRECTED", "REFERRAL_CLOSED");
            assertThat(events).extracting(event -> event.row().get("actor_id"))
                .containsExactly("judge-subject", "judge-subject", SUBJECT);
            assertThat(events.get(0).row()).containsEntry("from_state", "Open").containsEntry("to_state", "Claimed");
            assertThat(events.get(1).row())
                .containsEntry("fields", "state,decision,directionsText,publishToGazette")
                .containsEntry("from_state", "Claimed")
                .containsEntry("to_state", "Decided");
            assertThat(events.get(1).values()).containsExactly(
                Arrays.asList("decision", null, "Approved"),
                Arrays.asList("directionsText", null, "Proceed"),
                Arrays.asList("publishToGazette", null, "true"),
                List.of("state", "Claimed", "Decided"));
            assertThat(events.get(2).row()).containsEntry("from_state", "Decided").containsEntry("to_state", "Closed");
        }

        @Test
        @DisplayName("a revised direction is a new event with old and new values, and the first is left as it was")
        void revisedDirection() {
            String caseId = createdCase();
            String referralId = raised(caseId);
            referrals.claim(caseId, referralId, JUDGE);
            referrals.decide(caseId, referralId,
                             new DirectionInput(MasterDecision.Approved, Optional.of("Proceed"), true), JUDGE);

            referrals.reviseDirection(caseId, referralId,
                                      new DirectionInput(MasterDecision.Rejected, Optional.of("Refuse"), false),
                                      JUDGE);

            List<Event> events = referralEvents(caseId).subList(1, 4);
            assertThat(events).extracting(event -> event.row().get("action"))
                .containsExactly("REFERRAL_CLAIMED", "REFERRAL_DIRECTED", "REFERRAL_DIRECTION_REVISED");
            // The original direction, exactly as it was recorded: nothing in the trail is overwritten.
            assertThat(events.get(1).values()).containsExactly(
                Arrays.asList("decision", null, "Approved"),
                Arrays.asList("directionsText", null, "Proceed"),
                Arrays.asList("publishToGazette", null, "true"),
                List.of("state", "Claimed", "Decided"));
            // The revision names the same four fields decide does, records a value row only where one changed,
            // and makes no transition, because the referral stays Decided.
            assertThat(events.get(2).row())
                .containsEntry("actor_id", "judge-subject")
                .containsEntry("fields", "state,decision,directionsText,publishToGazette")
                .containsEntry("from_state", null)
                .containsEntry("to_state", null);
            assertThat(events.get(2).values()).containsExactly(
                List.of("decision", "Approved", "Rejected"),
                List.of("directionsText", "Proceed", "Refuse"),
                List.of("publishToGazette", "true", "false"));
        }

        @Test
        @DisplayName("a withdrawn direction is a transition back to Claimed, and a new direction is recorded afresh")
        void withdrawnDirection() {
            String caseId = createdCase();
            String referralId = raised(caseId);
            referrals.claim(caseId, referralId, JUDGE);
            referrals.decide(caseId, referralId,
                             new DirectionInput(MasterDecision.Approved, Optional.of("Proceed"), true), JUDGE);

            referrals.withdrawDirection(caseId, referralId, JUDGE);
            referrals.decide(caseId, referralId,
                             new DirectionInput(MasterDecision.Rejected, Optional.of("Refuse"), false), JUDGE);

            List<Event> events = referralEvents(caseId).subList(1, 5);
            assertThat(events).extracting(event -> event.row().get("action"))
                .containsExactly("REFERRAL_CLAIMED", "REFERRAL_DIRECTED", "REFERRAL_DIRECTION_WITHDRAWN",
                                 "REFERRAL_DIRECTED");
            // The original direction, exactly as it was recorded: nothing in the trail is overwritten.
            assertThat(events.get(1).values()).containsExactly(
                Arrays.asList("decision", null, "Approved"),
                Arrays.asList("directionsText", null, "Proceed"),
                Arrays.asList("publishToGazette", null, "true"),
                List.of("state", "Claimed", "Decided"));
            assertThat(events.get(2).row())
                .containsEntry("actor_id", "judge-subject")
                .containsEntry("fields", "state,decision,directionsText,publishToGazette")
                .containsEntry("from_state", "Decided")
                .containsEntry("to_state", "Claimed");
            assertThat(events.get(2).values()).containsExactly(
                Arrays.asList("decision", "Approved", null),
                Arrays.asList("directionsText", "Proceed", null),
                Arrays.asList("publishToGazette", "true", null),
                List.of("state", "Decided", "Claimed"));
            assertThat(events.get(3).values()).containsExactly(
                Arrays.asList("decision", null, "Rejected"),
                Arrays.asList("directionsText", null, "Refuse"),
                Arrays.asList("publishToGazette", null, "false"),
                List.of("state", "Claimed", "Decided"));
        }

        @Test
        @DisplayName("a confirmation records the outcome, and no transition")
        void confirmation() {
            String caseId = createdCase();
            String referralId = raised(caseId);
            jdbc.update("UPDATE deedpoll_referral SET \"confirmationRequestedAt\" = now() WHERE id = ?",
                        UUID.fromString(referralId));

            referrals.recordConfirmation(caseId, referralId,
                                         new ConfirmationInput(ConfirmationOutcome.Confirmed, Optional.empty()),
                                         TEAM_LEADER);

            Event event = referralEvents(caseId).get(1);
            assertThat(event.row())
                .containsEntry("action", "REFERRAL_CONFIRMED")
                .containsEntry("fields", "confirmationOutcome")
                .containsEntry("outcome", "Confirmed")
                .containsEntry("from_state", null)
                .containsEntry("to_state", null);
            assertThat(event.values()).containsExactly(Arrays.asList("confirmationOutcome", null, "Confirmed"));
        }

        private String raised(String caseId) {
            return referrals.createReferral(
                caseId,
                new CreateReferralInput(List.of(ReferralReason.Minor, ReferralReason.NonPublication),
                                        Optional.of("Needs a Master"), Urgency.Normal, Optional.empty()),
                TEAM_LEADER).getId().toString();
        }

        private List<Event> referralEvents(String caseId) {
            return events("case_id = ? AND entity_type = 'Referral'", caseId);
        }
    }

    @Test
    @DisplayName("a saved query's create, update and delete record field names, no case and no values")
    void savedQuery() {
        String id = savedQueries.createSavedQuery(
            new CreateSavedQueryInput("Mine", Optional.empty(), false, SavedQueryDefinition.NONE), TEAM_LEADER)
            .query().getId().toString();
        savedQueries.updateSavedQuery(id, new UpdateSavedQueryInput(
            PatchField.of("Renamed"), PatchField.absent(), PatchField.absent(), PatchField.absent(),
            PatchField.absent(), PatchField.absent(), PatchField.cleared()), TEAM_LEADER);
        savedQueries.removeSavedQuery(id, TEAM_LEADER);

        List<Event> events = events("entity_type = 'SavedQuery' AND entity_id = ?", id);
        assertThat(events).extracting(event -> event.row().get("action"))
            .containsExactly("SAVED_QUERY_CREATED", "SAVED_QUERY_UPDATED", "SAVED_QUERY_DELETED");
        assertThat(events).extracting(event -> event.row().get("fields"))
            .containsExactly("name,shared", "name,searchTerm", "");
        assertThat(events).allSatisfy(event -> {
            assertThat(event.row()).containsEntry("case_id", null).containsEntry("actor_id", SUBJECT);
            assertThat(event.values()).isEmpty();
        });
    }

    @Test
    @DisplayName("a Gazette settings change names the recipient field and never its address")
    void gazetteSettings() {
        int revision = jdbc.queryForObject("SELECT revision FROM deedpoll_gazette_settings WHERE id = 1",
                                           Integer.class);

        gazetteSettings.update(DELIVERY_MANAGER,
                               Map.of("recipientEmail", "audit-" + UUID.randomUUID() + "@example.com",
                                      "revision", revision));

        List<Event> events = events("entity_type = 'GazetteSettings' AND actor_id = ?", "delivery-manager-subject");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.row())
                .containsEntry("action", "GAZETTE_SETTINGS_CHANGED")
                .containsEntry("entity_id", "1")
                .containsEntry("case_id", null)
                .containsEntry("fields", "recipientEmail");
            assertThat(event.row().values()).noneMatch(value -> String.valueOf(value).contains("@"));
            assertThat(event.values()).isEmpty();
        });
    }

    @Test
    @DisplayName("a process settings change names the claim window field and never its minutes")
    void processSettings() {
        int revision = jdbc.queryForObject("SELECT revision FROM deedpoll_process_settings WHERE id = 1",
                                           Integer.class);
        int minutes = jdbc.queryForObject(
            "SELECT referral_claim_expiry_minutes FROM deedpoll_process_settings WHERE id = 1", Integer.class);

        processSettings.update(DELIVERY_MANAGER,
                               Map.of("referralClaimExpiryMinutes", minutes == 45 ? 46 : 45, "revision", revision));

        List<Event> events = events("entity_type = 'ProcessSettings' AND actor_id = ?", "delivery-manager-subject");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.row())
                .containsEntry("action", "PROCESS_SETTINGS_CHANGED")
                .containsEntry("entity_id", "1")
                .containsEntry("case_id", null)
                .containsEntry("fields", "referralClaimExpiryMinutes");
            assertThat(event.values()).isEmpty();
        });
    }

    @Test
    @DisplayName("each CCD repair records the attempt's move against its case, and a refused one records nothing")
    void ccdRepairs() {
        String reconciled = createdCase();
        String retried = createdCase();
        UUID first = needingRepair(reconciled);
        UUID second = needingRepair(retried);

        repairs.reconcileAgain(first.toString(), TEAM_LEADER);
        repairs.retryFromScratch(second.toString(), TEAM_LEADER);
        assertThatThrownBy(() -> repairs.retryFromScratch(second.toString(), TEAM_LEADER))
            .isInstanceOf(ConflictError.class);

        assertThat(events("case_id = ? AND entity_type = 'CcdCreationAttempt'", reconciled))
            .singleElement().satisfies(event -> assertThat(event.row())
                .containsEntry("action", "CCD_ATTEMPT_RECONCILED")
                .containsEntry("entity_id", first.toString())
                .containsEntry("actor_id", SUBJECT)
                .containsEntry("from_state", "NeedsRepair")
                .containsEntry("to_state", "AwaitingConfirmation"));
        assertThat(events("case_id = ? AND entity_type = 'CcdCreationAttempt'", retried))
            .singleElement().satisfies(event -> assertThat(event.row())
                .containsEntry("action", "CCD_ATTEMPT_RETRIED")
                .containsEntry("entity_id", second.toString())
                .containsEntry("from_state", "NeedsRepair")
                .containsEntry("to_state", "Pending"));
    }

    @Test
    @DisplayName("document metadata create, update and delete are the service account's, with metadata values")
    void documentMetadata() {
        String caseId = createdCase();
        String typeId = documentTypes.createDocumentType(CreateDocumentTypeInput.named("Metadata type")).getId();

        DeedPollSupportingDocument document = documents.createDocument(
            caseId, CreateDocumentInput.metadata(DocumentDirection.Incoming, "Expected", typeId));
        documents.updateDocument(caseId, document.getId(), new UpdateDocumentInput(
            PatchField.absent(), PatchField.of("Renamed"), PatchField.absent(), PatchField.absent(),
            PatchField.absent(), PatchField.absent()));
        documents.removeDocument(caseId, document.getId());

        List<Event> events = events("case_id = ? AND entity_type = 'Document'", caseId);
        assertThat(events).extracting(event -> event.row().get("action"))
            .containsExactly("DOCUMENT_CREATED", "DOCUMENT_UPDATED", "DOCUMENT_DELETED");
        assertThat(events).allSatisfy(event -> assertThat(event.row())
            .containsEntry("actor_id", null)
            .containsEntry("actor_name", CurrentUser.SERVICE_ACCOUNT_NAME)
            .containsEntry("document_type_id", typeId));
        assertThat(events.get(1).row()).containsEntry("fields", "name");
        assertThat(events.get(1).values()).containsExactly(List.of("name", "Expected", "Renamed"));
        assertThat(events.get(2).values()).containsExactly(
            Arrays.asList("direction", "Incoming", null),
            Arrays.asList("documentTypeId", typeId, null),
            Arrays.asList("name", "Renamed", null));
    }

    @Test
    @DisplayName("a case delete records that the case went, with no values")
    void caseDelete() {
        String caseId = createdCase();

        cases.removeDeedPoll(caseId);

        List<Event> events = events("case_id = ? AND action = 'CASE_DELETED'", caseId);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.row()).containsEntry("actor_name", CurrentUser.SERVICE_ACCOUNT_NAME);
            assertThat(event.values()).isEmpty();
        });
    }

    private String createdCase() {
        return cases.createDeedPoll(DeedPollPatch.of(Map.of("dateReceived", "2026-07-01T00:00:00.000Z")))
            .deedPoll().getId();
    }

    private UUID needingRepair(String caseId) {
        UUID attempt = attempts.open(caseId).orElseThrow().getId();
        attempts.claim(attempt, "worker-1", Duration.ofMinutes(5));
        attempts.move(attempt, CcdCreationAttemptStatus.Claimed, CcdCreationAttemptStatus.NeedsRepair, "refused");
        return attempt;
    }

    /** The events matching this condition in the order they were written, each with its value rows. */
    private List<Event> events(String condition, Object... arguments) {
        return jdbc.queryForList("""
            SELECT id, actor_id, actor_name, action, entity_type, entity_id, case_id,
                   array_to_string(fields, ',') AS fields, detail ->> 'fromState' AS from_state,
                   detail ->> 'toState' AS to_state, detail ->> 'outcome' AS outcome,
                   detail ->> 'documentTypeId' AS document_type_id
            FROM deedpoll_audit_event WHERE\s""" + condition + " ORDER BY id", arguments)
            .stream().map(row -> new Event(row, values((Long) row.get("id")))).toList();
    }

    private List<List<String>> values(long eventId) {
        return jdbc.query(
            "SELECT field, old_value, new_value FROM deedpoll_audit_event_value WHERE event_id = ? ORDER BY field",
            (results, index) -> Arrays.asList(results.getString(1), results.getString(2), results.getString(3)),
            eventId);
    }

    private record Event(Map<String, Object> row, List<List<String>> values) {
    }
}
