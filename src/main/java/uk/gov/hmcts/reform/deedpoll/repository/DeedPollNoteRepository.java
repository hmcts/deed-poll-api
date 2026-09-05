package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollNote;

import java.util.List;
import java.util.Optional;

/**
 * Notes, always scoped to their parent case.
 *
 * <p>Both finders carry {@code deedPollId} rather than leaving the scope to the caller, which is what
 * makes an unscoped read of a note unavailable rather than merely discouraged: {@code findById} exists on
 * {@link JpaRepository}, but nothing in {@code NoteService} uses it, and a caller wanting one note has to
 * say which case it belongs to.
 *
 * <p>The list carries its own sort for the same reason {@code DeedPollRepository}'s finders do -- the
 * reference orders notes by {@code createdOn} descending and the notes tab depends on it.
 */
public interface DeedPollNoteRepository extends JpaRepository<DeedPollNote, String> {

    /** Every note on one case, newest first. */
    List<DeedPollNote> findByDeedPollIdOrderByCreatedOnDesc(String deedPollId);

    /** One note, only if it belongs to the given case. */
    Optional<DeedPollNote> findByIdAndDeedPollId(String id, String deedPollId);
}
