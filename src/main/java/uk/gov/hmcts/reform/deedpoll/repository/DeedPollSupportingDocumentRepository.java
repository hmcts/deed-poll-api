package uk.gov.hmcts.reform.deedpoll.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Supporting documents, always scoped to their parent case.
 *
 * <p>Every finder but one carries {@code deedPollId} for the reason {@code DeedPollNoteRepository} gives: it makes an
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

    /**
     * The oldest document on one case whose type is one of those named, if there is one.
     *
     * <p>Case-scoped like the rest, and keyed on the type's NAME rather than its id because the caller asking
     * -- {@code DeedDocumentService}, deciding whether this case already has a generated deed -- knows the
     * names it created the rows under and cannot know their ids without creating the rows to find out.
     *
     * <p>Oldest first for the reason that question has: if a case somehow holds two deeds, the one that
     * matters is the first, because that is the one an applicant may already have signed.
     */
    Optional<DeedPollSupportingDocument> findFirstByDeedPollIdAndDocumentTypeNameInOrderByCreatedOnAsc(
        String deedPollId, Collection<String> documentTypeNames);

    /**
     * One document of one case, locked for the rest of the transaction: the CDAM store's state moves (plan 03a).
     *
     * <p>Case-scoped like every other finder. Each move is its own short transaction that reads the row under this
     * lock and nothing else first, so the persistence context holds no earlier snapshot of it -- the caveat
     * {@code ReferralRepository.findByIdAndDeedPollIdForUpdate} records does not arise.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DeedPollSupportingDocument d where d.id = :id and d.deedPoll.id = :deedPollId")
    Optional<DeedPollSupportingDocument> findByIdAndDeedPollIdForUpdate(@Param("id") String id,
                                                                       @Param("deedPollId") String deedPollId);

    /** Every document on one case in the given storage state, oldest first. */
    List<DeedPollSupportingDocument> findByDeedPollIdAndStorageStateOrderByCreatedOnAsc(String deedPollId,
                                                                                        DocumentStorageState state);

    /**
     * What the case's CCD view shows of its documents: the {@code Attached} ones, oldest first (plan 03a, task 5).
     *
     * <p>Only {@code Attached}, so nothing uncertain, failed or on the filesystem store is ever put on the case, and a
     * projection, so the hash token is never read.
     */
    @Query("""
        select new uk.gov.hmcts.reform.deedpoll.repository.CcdDocumentProjection(
                   d.id, d.cdamDocumentId, d.originalName)
          from DeedPollSupportingDocument d
         where d.deedPoll.id = :deedPollId
           and d.storageState = uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState.Attached
         order by d.createdOn asc, d.id asc
        """)
    List<CcdDocumentProjection> findCcdDocumentProjections(@Param("deedPollId") String deedPollId);

    /** How many documents, across every case, reference the given type. */
    long countByDocumentTypeId(String documentTypeId);
}
