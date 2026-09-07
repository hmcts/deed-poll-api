package uk.gov.hmcts.reform.deedpoll.domain.documents;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DetailView;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentsView.DocumentRow;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocumentType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ports every test block in {@code reference/ts/documents/documents-view.test.ts}.
 *
 * <p>Its fixture is a plain object cast to the Prisma type; here it is a detached entity, built rather than
 * stored, because the view model reads an entity and nothing about formatting needs a database.
 *
 * <p>The reference's "has a heading for every displayed cell" counts {@code Object.keys(row)} less {@code id}
 * and {@code downloadable}. A record has no key set, so this counts its record components instead -- the same
 * claim, and it still fails if a column is added to the row without a heading.
 */
class DocumentsViewTest {

    /** The reference's `document` fixture, field for field. */
    private static DeedPollSupportingDocument document() {
        DeedPollSupportingDocumentType type = new DeedPollSupportingDocumentType();
        type.setId("type-1");
        type.setName("Birth certificate");

        DeedPollSupportingDocument document = new DeedPollSupportingDocument();
        document.setId("doc-1");
        document.setName("Birth certificate");
        document.setDirection(DocumentDirection.Incoming);
        document.setDocumentType(type);
        document.setStorageKey("blob-1");
        document.setOriginalName("birth.pdf");
        document.setMimeType("application/pdf");
        document.setSizeBytes(41);
        document.setCreatedOn(LocalDateTime.parse("2026-03-01T00:00:00"));
        document.setModifiedOn(LocalDateTime.parse("2026-03-01T00:00:00"));
        return document;
    }

    /** The reference's `{ ...document, field: value }`. */
    private static DeedPollSupportingDocument documentWith(Consumer<DeedPollSupportingDocument> change) {
        DeedPollSupportingDocument document = document();
        change.accept(document);
        return document;
    }

    private static DocumentRow rowFor(DeedPollSupportingDocument document) {
        return DocumentsView.buildDocumentsView(List.of(document)).get(0);
    }

    @Test
    @DisplayName("labels the direction rather than showing the enum member")
    void labelsTheDirection() {
        assertThat(rowFor(document()).direction()).isEqualTo("Incoming");
    }

    @Test
    @DisplayName("formats the added date GOV.UK style")
    void formatsTheAddedDate() {
        // The reference titles this "in UTC", because its fixture is a JavaScript Date holding an instant and
        // rendering one depends on a zone. The column maps to a LocalDateTime that Dates has already
        // normalised to UTC, so there is no zone left for the formatter to get wrong and nothing here can
        // assert one.
        assertThat(rowFor(document()).added()).isEqualTo("1 March 2026");
    }

    @Test
    @DisplayName("takes the type name from the loaded relation")
    void takesTheTypeNameFromTheLoadedRelation() {
        assertThat(rowFor(document()).type()).isEqualTo("Birth certificate");
    }

    @Test
    @DisplayName("says the type is not provided when the relation was not loaded")
    void saysTheTypeIsNotProvidedWhenTheRelationWasNotLoaded() {
        // The FK is required, so an absent relation means "not included in the query" rather than "no type" --
        // the placeholder is a claim about the query, not about the data.
        DocumentRow row = rowFor(documentWith(document -> document.setDocumentType(null)));

        assertThat(row.type()).isEqualTo(DetailView.NOT_PROVIDED);
    }

    @Test
    @DisplayName("marks a document with an attached file as downloadable")
    void marksAnAttachedDocumentAsDownloadable() {
        assertThat(rowFor(document()).downloadable()).isTrue();
    }

    @Test
    @DisplayName("does not mark a metadata-only document as downloadable")
    void doesNotMarkAMetadataOnlyDocumentAsDownloadable() {
        // createDocument makes a row with no storageKey, so a link would 404 for a document that is
        // legitimately still pending.
        assertThat(rowFor(documentWith(document -> document.setStorageKey(null))).downloadable()).isFalse();
    }

    @Test
    @DisplayName("does not mark a blank storage key as downloadable either")
    void doesNotMarkABlankStorageKeyAsDownloadable() {
        // Not a reference block, where `Boolean("")` is false and the distinction never arises. In Java an
        // empty string is as truthy as any other, and a blank key resolves to the storage root rather than to
        // a file -- so a link built from one would 404 exactly as a null does.
        assertThat(rowFor(documentWith(document -> document.setStorageKey("  "))).downloadable()).isFalse();
    }

    @Test
    @DisplayName("carries the id so the row can link to the download")
    void carriesTheId() {
        assertThat(rowFor(document()).id()).isEqualTo("doc-1");
    }

    @Test
    @DisplayName("returns an empty list for no documents, so the page renders its own empty state")
    void returnsAnEmptyListForNoDocuments() {
        assertThat(DocumentsView.buildDocumentsView(List.of())).isEmpty();
    }

    @Test
    @DisplayName("has a heading for every displayed cell")
    void hasAHeadingForEveryDisplayedCell() {
        List<String> cells = Arrays.stream(DocumentRow.class.getRecordComponents())
            .map(RecordComponent::getName)
            .filter(name -> !"id".equals(name) && !"downloadable".equals(name))
            .toList();

        assertThat(cells).hasSameSizeAs(DocumentsView.DOCUMENT_COLUMNS);
    }

    @Test
    @DisplayName("preserves the order it was given, since the domain decides the sort")
    void preservesTheOrderItWasGiven() {
        List<DocumentRow> rows = DocumentsView.buildDocumentsView(List.of(
            document(),
            documentWith(document -> document.setId("doc-2"))));

        assertThat(rows).extracting(DocumentRow::id).containsExactly("doc-1", "doc-2");
    }

    @Test
    @DisplayName("names its columns in the order the row lays its cells out")
    void namesItsColumnsInTheOrderTheRowLaysItsCellsOut() {
        // Not a reference block -- it counts headings against cells but never checks they line up, because in
        // its template the pairing is by position too. A row whose cells and headings are the same length but
        // in different orders would render a type under "Direction", which the count above cannot catch.
        assertThat(DocumentsView.DOCUMENT_COLUMNS).containsExactly("Document", "Type", "Direction", "Added");

        DocumentRow row = rowFor(document());
        assertThat(List.of(row.name(), row.type(), row.direction(), row.added()))
            .containsExactly("Birth certificate", "Birth certificate", "Incoming", "1 March 2026");
    }
}
