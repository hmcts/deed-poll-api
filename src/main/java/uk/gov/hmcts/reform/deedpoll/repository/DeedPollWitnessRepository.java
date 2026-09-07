package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
