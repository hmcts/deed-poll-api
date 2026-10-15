package uk.gov.hmcts.reform.deedpoll.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollWitness;

import java.util.List;
import java.util.Optional;

/**
 * Witnesses and declarants, always scoped to their parent case.
 *
 * <p>Both finders carry {@code deedPollId} for the reason {@code DeedPollNoteRepository} gives: it makes
 * an unscoped read unavailable rather than merely discouraged. {@code findById} exists on
 * {@link JpaRepository}, but nothing in {@code WitnessService} uses it, so a caller wanting one witness
 * has to say which case it belongs to -- and every write goes through an entity loaded that way.
 *
 * <p>The list carries its own sort, ascending, reproducing the {@code orderBy: {createdOn: "asc"}} the
 * reference's {@code findWitnesses} declares. Witnesses are ordered as they were entered, which is the
 * order they appear on the declaration; unlike the notes grid there is nothing newest-first about them.
 */
public interface DeedPollWitnessRepository extends JpaRepository<DeedPollWitness, String> {

    /** Every witness on one case, oldest first. */
    List<DeedPollWitness> findByDeedPollIdOrderByCreatedOnAsc(String deedPollId);

    /** One witness, only if it belongs to the given case. */
    Optional<DeedPollWitness> findByIdAndDeedPollId(String id, String deedPollId);

    /**
     * One witness, only if it belongs to the given case, locked for the rest of the transaction.
     *
     * <p>A write that records the values it replaces must read them under this lock: the entity has no version, so an
     * unlocked read lets two edits both start from the same old value and the append-only history record a
     * transition that never happened.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from DeedPollWitness e where e.id = :id and e.deedPoll.id = :deedPollId")
    Optional<DeedPollWitness> findByIdAndDeedPollIdForUpdate(@Param("id") String id, @Param("deedPollId") String deedPollId);
}
