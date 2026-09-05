package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;

import java.util.List;
import java.util.Optional;

/**
 * Supporting documents, always scoped to their parent case.
 *
 * <p>Both finders carry {@code deedPollId} for the reason {@code DeedPollNoteRepository} gives: it makes an
 * unscoped read unavailable rather than merely discouraged. {@code findById} exists on {@link JpaRepository},
 * but nothing in {@code DocumentService} uses it, so a caller wanting one document has to say which case it
 * belongs to -- and every write, including the delete that removes a blob, goes through an entity loaded
 * that way.
 *
 * <p>The list carries its own sort, ascending, reproducing the {@code orderBy: {createdOn: "asc"}} the
 * reference's {@code findDocuments} declares. Documents are shown in the order they arrived, which is the
 * order the case file is read in.
 *
 * <p>{@link #countByDocumentTypeId} is the one finder here not scoped to a case, because the question it
 * answers is not about one: {@code DocumentTypeService.removeDocumentType} needs to know whether ANY document
 * anywhere still references a type before deleting it.
 */
public interface DeedPollSupportingDocumentRepository extends JpaRepository<DeedPollSupportingDocument, String> {

    /** Every document on one case, oldest first. */
    List<DeedPollSupportingDocument> findByDeedPollIdOrderByCreatedOnAsc(String deedPollId);

    /** One document, only if it belongs to the given case. */
    Optional<DeedPollSupportingDocument> findByIdAndDeedPollId(String id, String deedPollId);

    /** How many documents, across every case, reference the given type. */
    long countByDocumentTypeId(String documentTypeId);
}
