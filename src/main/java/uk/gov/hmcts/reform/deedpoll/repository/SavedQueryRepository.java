package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gov.hmcts.reform.deedpoll.entity.SavedQuery;

import java.util.List;
import java.util.UUID;

/**
 * Saved queries.
 *
 * <p>Unlike the case's children, {@code findById} is used here rather than avoided: a saved query's
 * permission is a property of the entity -- whether it is shared, and whether the caller owns it -- so
 * it cannot be checked before the entity is read, and a scoped finder would only be able to express
 * one of the two halves of "shared or mine". The service resolves and then refuses, answering 404 for
 * "not visible" so it is indistinguishable from an id that never existed.
 *
 * <p>Ordered by name: v1 has no per-user ordering, which is a recorded open question rather than an
 * omission.
 */
public interface SavedQueryRepository extends JpaRepository<SavedQuery, UUID> {

    /** Every query the given user may see -- the shared library plus their own -- by name. */
    List<SavedQuery> findBySharedTrueOrOwnerIdOrderByNameAsc(String ownerId);
}
