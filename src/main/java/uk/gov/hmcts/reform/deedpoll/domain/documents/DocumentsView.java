package uk.gov.hmcts.reform.deedpoll.domain.documents;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DetailView;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.FieldDef;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.FieldKind;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocumentType;

import java.util.List;

/**
 * View model for the documents subgrid on the case detail page.
 *
 * <p>Ported from {@code reference/ts/documents/documents-view.ts}. It exists for the reason
 * {@code ListView} and {@code NotesView} do: the source template formatted these cells with bespoke
 * template filters, and formatting in the view model instead needs no bespoke template environment and can be
 * tested directly. The witnesses subgrid needs nothing -- its three fields are plain strings a template
 * prints as they are.
 *
 * <p>Both formatted columns go through {@link DetailView#formatValue}, so the subgrid renders a direction and
 * a date exactly as every other page does.
 */
public final class DocumentsView {

    /** The subgrid's column headings, in order. */
    public static final List<String> DOCUMENT_COLUMNS = List.of("Document", "Type", "Direction", "Added");

    /**
     * The two columns that need formatting, declared here rather than looked up in {@code FormFields}.
     *
     * <p>That registry covers the case's own form fields and contains neither of these: a document is a
     * different entity, and adding them to it would make them editable on a case form.
     */
    private static final FieldDef DIRECTION_FIELD =
        FieldDef.enumOf("direction", "Direction", "documentDirection", null);

    private static final FieldDef ADDED_FIELD =
        FieldDef.of("createdOn", "Added", FieldKind.DATE, null);

    private DocumentsView() {
    }

    /**
     * A document as the subgrid renders it, with every cell already a display string.
     *
     * @param id the document id, so the row can link to the download
     * @param name the document's name as recorded on the case
     * @param type the type's name, or the placeholder when the relation was not loaded
     * @param direction the labelled direction, not the enum member
     * @param added the creation date, GOV.UK style
     * @param downloadable whether a file is actually attached -- the row links only when there is something
     *     to fetch
     */
    public record DocumentRow(
        String id,
        String name,
        String type,
        String direction,
        String added,
        boolean downloadable) {
    }

    /**
     * Builds the documents view model, preserving the order it was given.
     *
     * <p>The sort is the domain's: {@code DocumentService.findDocuments} orders oldest-first and the case
     * detail page's own collection carries {@code @OrderBy}, so re-sorting here could only disagree with one
     * of them.
     */
    public static List<DocumentRow> buildDocumentsView(List<DeedPollSupportingDocument> documents) {
        return documents.stream().map(DocumentsView::row).toList();
    }

    private static DocumentRow row(DeedPollSupportingDocument document) {
        return new DocumentRow(
            document.getId(),
            document.getName(),
            typeName(document.getDocumentType()),
            DetailView.formatValue(DIRECTION_FIELD, document.getDirection()),
            DetailView.formatValue(ADDED_FIELD, document.getCreatedOn()),
            // A metadata row can exist before its file arrives -- createDocument makes one with no storage
            // key -- so a link would 404 for a document that is legitimately still pending.
            document.getStorageKey() != null && !document.getStorageKey().isBlank());
    }

    /**
     * The type's name, or the placeholder when the relation is absent.
     *
     * <p>The foreign key is required, so an absent relation means "not loaded" rather than "no type". Saying
     * "Not provided" would be a claim about the data; this is a claim about the query -- which is why it is the
     * same placeholder rather than a different word. A stored row read through
     * {@code DocumentService.findDocuments} always has it; an entity built in memory, as a caller assembling
     * a preview might, may not.
     */
    private static String typeName(DeedPollSupportingDocumentType type) {
        return type == null || type.getName() == null ? DetailView.NOT_PROVIDED : type.getName();
    }
}
