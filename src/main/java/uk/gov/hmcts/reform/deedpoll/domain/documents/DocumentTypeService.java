package uk.gov.hmcts.reform.deedpoll.domain.documents;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocumentType;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentTypeRepository;

import java.util.List;

/**
 * CRUD for supporting-document <em>types</em> -- global reference data, not nested under a case.
 *
 * <p>Ported from {@code reference/ts/documents/document-types.ts}. Documents reference a type by foreign
 * key, and a type still in use cannot be deleted.
 *
 * <p><strong>Only {@link #findDocumentTypes} has a consumer in this plan.</strong> The caseworker surface is
 * eleven operations and {@code GET /caseworker/document-types} is the only one of them here; there are
 * deliberately no document-type writes. The other four are ported because the reference has them and the
 * source form does, so a later consumer finds an implementation that already refuses the delete rather than
 * inventing one that 500s -- stated here so this does not read as dead code someone should remove.
 */
@Service
public class DocumentTypeService {

    private final DeedPollSupportingDocumentTypeRepository types;
    private final DeedPollSupportingDocumentRepository documents;

    public DocumentTypeService(DeedPollSupportingDocumentTypeRepository types,
                              DeedPollSupportingDocumentRepository documents) {
        this.types = types;
        this.documents = documents;
    }

    /** Creates a type from the payload as given. There is no parent to scope it to. */
    @Transactional
    public DeedPollSupportingDocumentType createDocumentType(CreateDocumentTypeInput dto) {
        DeedPollSupportingDocumentType type = new DeedPollSupportingDocumentType();
        type.setName(dto.name());
        dto.applicableMaritalStatuses().ifPresent(values -> type.setApplicableMaritalStatuses(toArray(values)));
        dto.applicableTypes().ifPresent(values -> type.setApplicableTypes(toArray(values)));
        return types.save(type);
    }

    /** Every type, alphabetically, since they populate a form's options. */
    @Transactional(readOnly = true)
    public List<DeedPollSupportingDocumentType> findDocumentTypes() {
        return types.findAllByOrderByNameAsc();
    }

    /**
     * One type.
     *
     * @throws NotFoundError if there is no such type
     */
    @Transactional(readOnly = true)
    public DeedPollSupportingDocumentType findDocumentType(String id) {
        return types.findById(id)
            .orElseThrow(() -> new NotFoundError("Document type " + id + " not found"));
    }

    /**
     * Amends a type. Only the fields the patch names are written.
     *
     * @throws NotFoundError if there is no such type
     */
    @Transactional
    public DeedPollSupportingDocumentType updateDocumentType(String id, UpdateDocumentTypeInput dto) {
        DeedPollSupportingDocumentType type = findDocumentType(id);
        dto.name().ifPresent(type::setName);
        dto.applicableMaritalStatuses().ifPresent(values -> type.setApplicableMaritalStatuses(toArray(values)));
        dto.applicableTypes().ifPresent(values -> type.setApplicableTypes(toArray(values)));
        return types.save(type);
    }

    /**
     * Deletes a type, refusing if any document still references it.
     *
     * <p><strong>The reference check is explicit rather than left to the foreign key.</strong> The
     * {@code documentType} relation is required and declares no {@code onDelete}, so V3 gave it
     * {@code ON DELETE RESTRICT} and Postgres already refuses -- but it refuses as a constraint violation,
     * which is not a domain error. In the reference that reached the route layer unrecognised and became a
     * generic 500: an ordinary caseworker deleting a type that happens to be in use was told the server had
     * broken.
     *
     * <p>Checking here means the answer is a 409 naming the number of documents, which is both true and
     * actionable, and it does not depend on the driver's error shape -- that would be fragile across an
     * upgrade, and would fail by silently reverting to 500.
     *
     * <p>A document created between this count and the delete would still hit the constraint and produce
     * that 500. The window is tiny, the database refuses either way so integrity holds, and closing it would
     * mean the driver-internals coupling this check exists to avoid.
     *
     * @throws NotFoundError if there is no such type
     * @throws ConflictError if documents still reference it
     */
    @Transactional
    public void removeDocumentType(String id) {
        DeedPollSupportingDocumentType type = findDocumentType(id);

        long inUse = documents.countByDocumentTypeId(id);
        if (inUse > 0) {
            throw new ConflictError(
                "Cannot delete this document type: " + inUse + " document(s) still reference it");
        }

        types.delete(type);
    }

    /**
     * A validated option-set list as the column's {@code integer[]}.
     *
     * <p>The entity holds {@code Integer[]} rather than {@code List<Integer>} because that is what Hibernate
     * maps a Postgres array to; the domain speaks in lists, so the conversion belongs here rather than in
     * every caller.
     */
    private static Integer[] toArray(List<Integer> values) {
        return values.toArray(new Integer[0]);
    }
}
