package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocumentType;

import java.util.List;
import java.util.Optional;

/**
 * Supporting-document types: global reference data, not nested under a case.
 *
 * <p>No scoped finders here, and that is not an omission -- a type belongs to the service rather than to any
 * one case, which is why {@code DocumentTypeService} is a separate service from {@code DocumentService}.
 *
 * <p>Both finders carry their sort. The list's is alphabetical, reproducing the {@code orderBy: {name: "asc"}}
 * the reference's {@code findDocumentTypes} declares: these populate a form's options, and a select whose order
 * changes between page loads is one a caseworker cannot learn.
 */
public interface DeedPollSupportingDocumentTypeRepository
    extends JpaRepository<DeedPollSupportingDocumentType, String> {

    /** Every type, alphabetically. */
    List<DeedPollSupportingDocumentType> findAllByOrderByNameAsc();

    /**
     * The type of a given name, for a caller that knows what it is looking for rather than an id.
     *
     * <p>{@code findFirst} rather than a unique lookup, because the column carries no unique index: two rows of
     * the same name are possible, and the caller that needs this -- {@code DeedDocumentService}, finding the
     * type its generated deeds are filed under -- needs a deterministic answer more than it needs there to be
     * exactly one. Ordered by id so that answer does not depend on the plan the database happened to pick.
     */
    Optional<DeedPollSupportingDocumentType> findFirstByNameOrderByIdAsc(String name);
}
