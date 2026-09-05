package uk.gov.hmcts.reform.deedpoll.domain.documents;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocumentType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentTypeRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ports every test block in {@code reference/ts/documents/document-types.test.ts}.
 *
 * <p><strong>Against a real database, not a mocked Prisma client</strong>, for the reason
 * {@code DeedPollServiceTest}, {@code NoteServiceTest} and {@code WitnessServiceTest} give: the reference
 * asserts the arguments its mock was called with because that is all a TypeScript unit test can do, and the
 * stored row is the claim those assertions stand for. So each of its shapes translates:
 *
 * <ul>
 *   <li>{@code expect(create).toHaveBeenCalledWith({data: dto})} becomes "the stored type carries those
 *       values".</li>
 *   <li>{@code expect(findMany).toHaveBeenCalledWith({orderBy: {name: "asc"}})} becomes "the types come back
 *       alphabetically", asserted on rows deliberately created out of order.</li>
 *   <li>{@code expect(update).not.toHaveBeenCalled()} becomes "the stored type is unchanged".</li>
 *   <li>{@code expect(count).toHaveBeenCalledWith({where: {documentTypeId}})} becomes "a document of ANOTHER
 *       type does not block the delete" -- which is what scoping the count buys, and something a mock
 *       returning a fixed number cannot check.</li>
 * </ul>
 *
 * <p>Not {@code @Transactional}: the writes must really commit so the assertions read committed rows, and the
 * restrict-on-delete foreign key only bites on a real one. The tables are cleared around each test instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DocumentTypeServiceTest extends AbstractPostgresContainerTest {

    /** createDeedPoll cannot allocate a reference number without a Date Received to derive the year. */
    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";

    @Autowired
    private DocumentTypeService documentTypes;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DeedPollService deedPollService;

    @Autowired
    private DeedPollSupportingDocumentTypeRepository types;

    @Autowired
    private DeedPollSupportingDocumentRepository documents;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private ReferenceCounterRepository referenceCounters;

    @BeforeEach
    @AfterEach
    void clearTables() {
        documents.deleteAll();
        types.deleteAll();
        deedPolls.deleteAll();
        referenceCounters.deleteAll();
    }

    /** The reference's `dto` fixture. */
    private static CreateDocumentTypeInput dto() {
        return new CreateDocumentTypeInput("Birth certificate", Optional.of(List.of(1)), Optional.of(List.of(3)));
    }

    /** The stored type, read back rather than taken from the returned instance. */
    private DeedPollSupportingDocumentType stored(String id) {
        return types.findById(id).orElseThrow();
    }

    /** A case, so a document can exist to block a delete. */
    private String storedCase() {
        return deedPollService.createDeedPoll(DeedPollPatch.of(Map.of("dateReceived", RECEIVED)))
            .deedPoll()
            .getId();
    }

    @Test
    @DisplayName("creates a type from the payload as given, with no parent to scope to")
    void createsATypeFromThePayloadAsGiven() {
        String id = documentTypes.createDocumentType(dto()).getId();

        DeedPollSupportingDocumentType saved = stored(id);
        assertThat(saved.getName()).isEqualTo("Birth certificate");
        assertThat(saved.getApplicableMaritalStatuses()).containsExactly(1);
        assertThat(saved.getApplicableTypes()).containsExactly(3);
    }

    @Test
    @DisplayName("takes the column defaults for applicability lists a payload omits")
    void takesTheColumnDefaultsForOmittedLists() {
        // Not a reference block: absent-versus-empty is the schema's business there, and its mock stores
        // nothing. Here the claim is that an omitted list arrives as [] rather than as null -- the column is
        // NOT NULL, so getting this wrong would be a constraint violation on every minimal create.
        String id = documentTypes.createDocumentType(CreateDocumentTypeInput.named("Passport")).getId();

        DeedPollSupportingDocumentType saved = stored(id);
        assertThat(saved.getApplicableMaritalStatuses()).isEmpty();
        assertThat(saved.getApplicableTypes()).isEmpty();
    }

    @Test
    @DisplayName("lists types alphabetically, since they populate a form's options")
    void listsTypesAlphabetically() {
        documentTypes.createDocumentType(CreateDocumentTypeInput.named("Passport"));
        documentTypes.createDocumentType(CreateDocumentTypeInput.named("Birth certificate"));
        documentTypes.createDocumentType(CreateDocumentTypeInput.named("Marriage certificate"));

        assertThat(documentTypes.findDocumentTypes())
            .extracting(DeedPollSupportingDocumentType::getName)
            .containsExactly("Birth certificate", "Marriage certificate", "Passport");
    }

    @Test
    @DisplayName("404s for a type that does not exist")
    void refusesATypeThatDoesNotExist() {
        assertThatThrownBy(() -> documentTypes.findDocumentType("t1")).isInstanceOf(NotFoundError.class);
    }

    @Test
    @DisplayName("404s updating a type that does not exist, before writing")
    void refusesToUpdateATypeThatDoesNotExist() {
        assertThatThrownBy(() -> documentTypes.updateDocumentType("t1",
            new UpdateDocumentTypeInput(Optional.of("Renamed"), Optional.empty(), Optional.empty())))
            .isInstanceOf(NotFoundError.class);

        assertThat(types.count()).isZero();
    }

    @Test
    @DisplayName("updates an existing type")
    void updatesAnExistingType() {
        String id = documentTypes.createDocumentType(dto()).getId();

        documentTypes.updateDocumentType(id,
            new UpdateDocumentTypeInput(Optional.of("Renamed"), Optional.empty(), Optional.empty()));

        DeedPollSupportingDocumentType saved = stored(id);
        assertThat(saved.getName()).isEqualTo("Renamed");
        // The two fields the patch does not name must come back as they were -- the absent-versus-present
        // distinction reaching the row, where the reference asserts the `data` its mock received.
        assertThat(saved.getApplicableMaritalStatuses()).containsExactly(1);
        assertThat(saved.getApplicableTypes()).containsExactly(3);
    }

    @Test
    @DisplayName("an empty patch changes nothing")
    void anEmptyPatchChangesNothing() {
        // Not a reference block: there the schema test proves an empty patch parses and the service test stops
        // there. A type has no audit columns to move, so an empty patch really is a no-op, and that is worth
        // pinning rather than assuming.
        String id = documentTypes.createDocumentType(dto()).getId();

        documentTypes.updateDocumentType(id, UpdateDocumentTypeInput.EMPTY);

        DeedPollSupportingDocumentType saved = stored(id);
        assertThat(saved.getName()).isEqualTo("Birth certificate");
        assertThat(saved.getApplicableMaritalStatuses()).containsExactly(1);
        assertThat(saved.getApplicableTypes()).containsExactly(3);
    }

    @Test
    @DisplayName("deletes a type nothing references")
    void deletesATypeNothingReferences() {
        String id = documentTypes.createDocumentType(dto()).getId();

        documentTypes.removeDocumentType(id);

        assertThat(types.findById(id)).isEmpty();
    }

    @Test
    @DisplayName("refuses to delete a type documents still reference, with a ConflictError not a 500")
    void refusesToDeleteATypeInUse() {
        // The FK already refuses, but as a constraint violation the reference's route layer did not recognise
        // -- so it answered a generic 500 for what is an ordinary caseworker action.
        String typeId = documentTypes.createDocumentType(dto()).getId();
        documentService.createDocument(storedCase(),
            CreateDocumentInput.metadata(DocumentDirection.Incoming, "Birth certificate", typeId));

        assertThatThrownBy(() -> documentTypes.removeDocumentType(typeId)).isInstanceOf(ConflictError.class);

        assertThat(types.findById(typeId)).isPresent();
    }

    @Test
    @DisplayName("names the number of referencing documents, so the message is actionable")
    void namesTheNumberOfReferencingDocuments() {
        String typeId = documentTypes.createDocumentType(dto()).getId();
        String caseId = storedCase();
        for (int i = 0; i < 3; i++) {
            documentService.createDocument(caseId,
                CreateDocumentInput.metadata(DocumentDirection.Incoming, "Document " + i, typeId));
        }

        assertThatThrownBy(() -> documentTypes.removeDocumentType(typeId))
            .hasMessageContaining("3 document(s)");
    }

    @Test
    @DisplayName("counts only documents of the type being deleted")
    void countsOnlyDocumentsOfTheTypeBeingDeleted() {
        // The reference asserts the `where` its mock was called with. Here the claim is the outcome that
        // scoping buys: a document of another type must not block this delete.
        String deleted = documentTypes.createDocumentType(CreateDocumentTypeInput.named("Unused")).getId();
        String inUse = documentTypes.createDocumentType(CreateDocumentTypeInput.named("Used")).getId();
        documentService.createDocument(storedCase(),
            CreateDocumentInput.metadata(DocumentDirection.Incoming, "Birth certificate", inUse));

        documentTypes.removeDocumentType(deleted);

        assertThat(types.findById(deleted)).isEmpty();
        assertThat(types.findById(inUse)).isPresent();
    }

    @Test
    @DisplayName("404s deleting a type that does not exist, without counting references")
    void refusesToDeleteATypeThatDoesNotExist() {
        assertThatThrownBy(() -> documentTypes.removeDocumentType("t1")).isInstanceOf(NotFoundError.class);
    }

    @Test
    @DisplayName("lets a type be deleted once the documents referencing it have gone")
    void letsATypeBeDeletedOnceItsDocumentsHaveGone() {
        // Not a reference block, and the half of the refusal that makes it a 409 rather than a 400: the request
        // is not wrong, it is merely premature, and it must succeed unchanged once the references are removed.
        String typeId = documentTypes.createDocumentType(dto()).getId();
        String caseId = storedCase();
        String documentId = documentService.createDocument(caseId,
            CreateDocumentInput.metadata(DocumentDirection.Incoming, "Birth certificate", typeId)).getId();

        documentService.removeDocument(caseId, documentId);
        documentTypes.removeDocumentType(typeId);

        assertThat(types.findById(typeId)).isEmpty();
    }
}
