package uk.gov.hmcts.reform.deedpoll.domain.audit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.WriteContext;
import uk.gov.hmcts.reform.deedpoll.domain.documents.CreateDocumentTypeInput;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentTypeService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadFormInput;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadedFile;
import uk.gov.hmcts.reform.deedpoll.domain.notes.CreateNoteInput;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;
import uk.gov.hmcts.reform.deedpoll.domain.notes.UpdateNoteInput;
import uk.gov.hmcts.reform.deedpoll.domain.witnesses.CreateWitnessInput;
import uk.gov.hmcts.reform.deedpoll.domain.witnesses.UpdateWitnessInput;
import uk.gov.hmcts.reform.deedpoll.domain.witnesses.WitnessService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollNote;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollWitness;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The case, note, witness and upload writes each record one audit event, in their own transaction.
 *
 * <p>Every test runs in a transaction that is rolled back, because V36 refuses the {@code DELETE} any other cleanup
 * would need, and every assertion is scoped to a case the test created.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class CaseWriteAuditTest extends AbstractPostgresContainerTest {

    private static final Path STORE_ROOT = temporaryStoreRoot();

    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";
    private static final String SUBJECT = "caseworker-subject";
    private static final String NAME = "A Caseworker";
    private static final WriteContext CASEWORKER = new WriteContext(false, NAME, SUBJECT);
    private static final CurrentUser TEAM_LEADER =
        CurrentUser.currentUser(List.of(Role.TEAM_LEADER), NAME, SUBJECT);

    @Autowired
    private DeedPollService cases;

    @Autowired
    private NoteService notes;

    @Autowired
    private WitnessService witnesses;

    @Autowired
    private DocumentService documents;

    @Autowired
    private DocumentTypeService documentTypes;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void storeRoot(DynamicPropertyRegistry registry) {
        registry.add("deedpoll.documents.local-store-root", STORE_ROOT::toString);
    }

    private static Path temporaryStoreRoot() {
        try {
            return Files.createTempDirectory("deed-poll-audit-documents");
        } catch (IOException failed) {
            throw new UncheckedIOException("Could not create a temporary document store", failed);
        }
    }

    @AfterAll
    static void removeTheStoreRoot() throws IOException {
        try (Stream<Path> left = Files.list(STORE_ROOT)) {
            for (Path blob : left.toList()) {
                Files.deleteIfExists(blob);
            }
        }
        Files.deleteIfExists(STORE_ROOT);
    }

    @Nested
    @DisplayName("a case")
    class Cases {

        @Test
        @DisplayName("create records the caller, the request's fields, a value for each set field and the state")
        void create() {
            String id = createdCase(patch("dateReceived", RECEIVED, "newForename", "Ada", "newSurname", null));

            Event event = onlyEvent(id);
            assertThat(event.row())
                .containsEntry("actor_id", SUBJECT)
                .containsEntry("actor_name", NAME)
                .containsEntry("action", "CASE_CREATED")
                .containsEntry("entity_type", "DeedPoll")
                .containsEntry("entity_id", id)
                .containsEntry("case_id", id)
                .containsEntry("fields", "dateReceived,newForename,newSurname")
                .containsEntry("from_state", null)
                .containsEntry("to_state", "Received");
            assertThat(event.values()).contains(
                Arrays.asList("dateReceived", null, "2026-07-01T00:00:00"),
                Arrays.asList("newForename", null, "Ada"));
            assertThat(event.values()).noneMatch(value -> value.get(0).equals("newSurname"));
        }

        @Test
        @DisplayName("update records the request's fields and values only for the fields that changed")
        void update() {
            String id = createdCase(patch("dateReceived", RECEIVED, "newForename", "Ada", "newSurname", "Wren"));

            cases.updateDeedPoll(id, patch("newForename", "Bea", "newSurname", "Wren", "applicantEmail", null),
                                 CASEWORKER);

            Event event = events(id).get(1);
            assertThat(event.row())
                .containsEntry("action", "CASE_UPDATED")
                .containsEntry("actor_id", SUBJECT)
                .containsEntry("fields", "newForename,newSurname,applicantEmail")
                .containsEntry("from_state", null)
                .containsEntry("to_state", null);
            assertThat(event.values()).containsExactly(List.of("newForename", "Ada", "Bea"));
        }

        @Test
        @DisplayName("update carries the state transition and a cleared field as a null new value")
        void stateTransition() {
            String id = createdCase(patch("dateReceived", RECEIVED, "signatureRequestedDate", RECEIVED));

            cases.updateDeedPoll(id, patch("signatureRequestedDate", null), CASEWORKER);

            Event event = events(id).get(1);
            assertThat(event.row())
                .containsEntry("from_state", "AwaitingSignature")
                .containsEntry("to_state", "Received");
            assertThat(event.values())
                .containsExactly(Arrays.asList("signatureRequestedDate", "2026-07-01T00:00:00", null));
        }

        @Test
        @DisplayName("a write as the service account names it, with no subject")
        void serviceAccount() {
            String id = cases.createDeedPoll(patch("dateReceived", RECEIVED)).deedPoll().getId();

            assertThat(onlyEvent(id).row())
                .containsEntry("actor_id", null)
                .containsEntry("actor_name", CurrentUser.SERVICE_ACCOUNT_NAME);
        }

        @Test
        @DisplayName("a user's write with no subject is refused")
        void refusesAUserWithNoSubject() {
            assertThatThrownBy(() -> cases.createDeedPoll(patch("dateReceived", RECEIVED),
                                                          new WriteContext(false, NAME, null)))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("a note")
    class Notes {

        @Test
        @DisplayName("create, update and delete each record one event with the note's before and after")
        void lifecycle() {
            String id = createdCase(patch("dateReceived", RECEIVED));

            DeedPollNote note = notes.createNote(id, new CreateNoteInput("First"), TEAM_LEADER.noteAuthor());
            notes.updateNote(id, note.getId(), new UpdateNoteInput(Optional.of("Second")), TEAM_LEADER.noteAuthor());
            notes.updateNote(id, note.getId(), new UpdateNoteInput(Optional.of("Second")), TEAM_LEADER.noteAuthor());
            notes.removeNote(id, note.getId(), TEAM_LEADER);

            List<Event> noteEvents = events(id).subList(1, 5);
            assertThat(noteEvents).extracting(event -> event.row().get("action"))
                .containsExactly("NOTE_CREATED", "NOTE_UPDATED", "NOTE_UPDATED", "NOTE_DELETED");
            assertThat(noteEvents).allSatisfy(event -> assertThat(event.row())
                .containsEntry("entity_type", "Note")
                .containsEntry("entity_id", note.getId())
                .containsEntry("actor_id", SUBJECT)
                .containsEntry("actor_name", NAME)
                .containsEntry("fields", "note"));
            assertThat(noteEvents.get(0).values()).containsExactly(Arrays.asList("note", null, "First"));
            assertThat(noteEvents.get(1).values()).containsExactly(List.of("note", "First", "Second"));
            assertThat(noteEvents.get(2).values()).isEmpty();
            assertThat(noteEvents.get(3).values()).containsExactly(Arrays.asList("note", "Second", null));
        }

        @Test
        @DisplayName("a note the service writes on its own behalf names the service account")
        void serviceNote() {
            String id = createdCase(patch("dateReceived", RECEIVED));

            notes.createNote(id, new CreateNoteInput("Automatic"));

            assertThat(events(id).get(1).row())
                .containsEntry("actor_id", null)
                .containsEntry("actor_name", CurrentUser.SERVICE_ACCOUNT_NAME);
        }
    }

    @Nested
    @DisplayName("a witness")
    class Witnesses {

        @Test
        @DisplayName("create, update and delete each record one event with the changed fields' values")
        void lifecycle() {
            String id = createdCase(patch("dateReceived", RECEIVED));

            DeedPollWitness witness = witnesses.createWitness(
                id, new CreateWitnessInput("Sam Doe", "1 High St", "Teacher", false), TEAM_LEADER);
            witnesses.updateWitness(id, witness.getId(), new UpdateWitnessInput(
                Optional.of("Sam Doe"), Optional.empty(), Optional.of("Nurse"), Optional.empty()), TEAM_LEADER);
            witnesses.removeWitness(id, witness.getId(), TEAM_LEADER);

            List<Event> witnessEvents = events(id).subList(1, 4);
            assertThat(witnessEvents).extracting(event -> event.row().get("action"))
                .containsExactly("WITNESS_CREATED", "WITNESS_UPDATED", "WITNESS_DELETED");
            assertThat(witnessEvents).allSatisfy(event -> assertThat(event.row())
                .containsEntry("entity_type", "Witness")
                .containsEntry("entity_id", witness.getId())
                .containsEntry("actor_id", SUBJECT));
            assertThat(witnessEvents.get(0).row())
                .containsEntry("fields", "witnessName,witnessAddress,witnessOccupation,declarant");
            assertThat(witnessEvents.get(0).values()).containsExactlyInAnyOrder(
                Arrays.asList("witnessName", null, "Sam Doe"),
                Arrays.asList("witnessAddress", null, "1 High St"),
                Arrays.asList("witnessOccupation", null, "Teacher"),
                Arrays.asList("declarant", null, "false"));
            assertThat(witnessEvents.get(1).row()).containsEntry("fields", "witnessName,witnessOccupation");
            assertThat(witnessEvents.get(1).values()).containsExactly(List.of("witnessOccupation", "Teacher", "Nurse"));
            assertThat(witnessEvents.get(2).values()).containsExactlyInAnyOrder(
                Arrays.asList("witnessName", "Sam Doe", null),
                Arrays.asList("witnessAddress", "1 High St", null),
                Arrays.asList("witnessOccupation", "Nurse", null),
                Arrays.asList("declarant", "false", null));
        }
    }

    @Nested
    @DisplayName("a document upload")
    class Uploads {

        @Test
        @DisplayName("records the document's metadata and its type, never the file's content")
        void upload() {
            String id = createdCase(patch("dateReceived", RECEIVED));
            String typeId = documentTypes.createDocumentType(CreateDocumentTypeInput.named("Audit type")).getId();
            byte[] content = "the file's own bytes".getBytes(StandardCharsets.UTF_8);

            DeedPollSupportingDocument uploaded = documents.uploadDocument(
                id, new UploadFormInput(typeId, Optional.of("Passport"), Optional.empty()),
                new UploadedFile(content, "passport.pdf", "application/pdf", content.length), TEAM_LEADER);

            Event event = events(id).get(1);
            assertThat(event.row())
                .containsEntry("action", "DOCUMENT_UPLOADED")
                .containsEntry("entity_type", "Document")
                .containsEntry("entity_id", uploaded.getId())
                .containsEntry("actor_id", SUBJECT)
                .containsEntry("document_type_id", typeId)
                .containsEntry("fields", "name,direction,documentTypeId,originalName,mimeType,sizeBytes");
            assertThat(event.values()).containsExactlyInAnyOrder(
                Arrays.asList("name", null, "Passport"),
                Arrays.asList("direction", null, DocumentDirection.Incoming.name()),
                Arrays.asList("documentTypeId", null, typeId),
                Arrays.asList("originalName", null, "passport.pdf"),
                Arrays.asList("mimeType", null, "application/pdf"),
                Arrays.asList("sizeBytes", null, String.valueOf(content.length)));
            assertThat(event.values()).noneMatch(value -> value.contains("the file's own bytes"));
        }
    }

    private String createdCase(DeedPollPatch patch) {
        return cases.createDeedPoll(patch, CASEWORKER).deedPoll().getId();
    }

    private static DeedPollPatch patch(Object... keysAndValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return DeedPollPatch.of(values);
    }

    private Event onlyEvent(String caseId) {
        List<Event> found = events(caseId);
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    /** One case's events in the order they were written, each with its value rows. */
    private List<Event> events(String caseId) {
        return jdbc.queryForList("""
            SELECT id, actor_id, actor_name, action, entity_type, entity_id, case_id,
                   array_to_string(fields, ',') AS fields, detail ->> 'fromState' AS from_state,
                   detail ->> 'toState' AS to_state, detail ->> 'documentTypeId' AS document_type_id
            FROM deedpoll_audit_event WHERE case_id = ? ORDER BY id
            """, caseId).stream().map(row -> new Event(row, values((Long) row.get("id")))).toList();
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
