package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gov.hmcts.reform.deedpoll.entity.SavedQuerySelection;
import uk.gov.hmcts.reform.deedpoll.entity.SavedQuerySelectionId;

import java.util.List;
import java.util.UUID;

/**
 * Which queries each user has chosen to see.
 *
 * <p>Every finder carries the user id, which is what makes an unscoped read of somebody else's
 * selections unavailable rather than merely discouraged -- the same reasoning
 * {@code DeedPollNoteRepository} gives for scoping notes to their case. The key is composite, so
 * {@code findById} would need a user id anyway; the named finders exist because the two questions the
 * service asks are "which has this user selected" and "delete this user's selection", neither of which
 * reads a single row by key.
 */
public interface SavedQuerySelectionRepository extends JpaRepository<SavedQuerySelection, SavedQuerySelectionId> {

    /** One user's selections. */
    List<SavedQuerySelection> findByIdUserId(String userId);

    /** Whether one user has selected one query. */
    boolean existsByIdSavedQueryIdAndIdUserId(UUID savedQueryId, String userId);

    /**
     * Unselect, idempotently: deleting a selection that is not there is not a 404, so this returns the
     * number removed rather than throwing as {@code deleteById} would.
     */
    long deleteByIdSavedQueryIdAndIdUserId(UUID savedQueryId, String userId);
}
