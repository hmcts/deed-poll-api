package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocumentType;

import java.util.List;

/**
 * Supporting-document types: global reference data, not nested under a case.
 *
 * <p>No scoped finders here, and that is not an omission -- a type belongs to the service rather than to any
 * one case, which is why {@code DocumentTypeService} is a separate service from {@code DocumentService}.
 *
 * <p>The one finder carries its sort, alphabetical, reproducing the {@code orderBy: {name: "asc"}} the
 * reference's {@code findDocumentTypes} declares: these populate a form's options, and a select whose order
 * changes between page loads is one a caseworker cannot learn.
 */
public interface DeedPollSupportingDocumentTypeRepository
    extends JpaRepository<DeedPollSupportingDocumentType, String> {

    /** Every type, alphabetically. */
    List<DeedPollSupportingDocumentType> findAllByOrderByNameAsc();
}
