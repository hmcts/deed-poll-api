package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Referrals, always scoped to their parent case.
 *
 * <p>Every finder carries {@code deedPollId}, which is the same rule {@link DeedPollNoteRepository}
 * states and for the same reason: with the scope left to the caller, cross-case safety would be a
 * convention one line long that a later method could omit. {@code findById} exists on
 * {@link JpaRepository} and nothing may use it -- <strong>do not add a finder by referral id alone</strong>.
 * A caller wanting one referral has to say which case it belongs to, so a cross-case read or write is
 * not expressible rather than merely discouraged.
 *
 * <p>This differs from {@link SavedQueryRepository}, which does read by id: a saved query's visibility
 * is a property of the entity, so it cannot be checked before the entity is loaded. A referral's scope
 * is its parent, which the caller already named in the URL.
 *
 * <p>The list carries its own sort. The referrals tab shows history newest first -- the current open
 * referral above the ones that finished -- and V6 indexes {@code "deedPollId"} to serve it.
 */
public interface ReferralRepository extends JpaRepository<Referral, UUID> {

    /** Every referral on one case, newest first. */
    List<Referral> findByDeedPollIdOrderByCreatedOnDesc(String deedPollId);

    /** One referral, only if it belongs to the given case. */
    Optional<Referral> findByIdAndDeedPollId(UUID id, String deedPollId);

    /**
     * Whether the case already holds a referral in one of the given states.
     *
     * <p>The one-open-per-case guard, asked as an existence check rather than by loading the list: the
     * answer is a boolean and the caller has no use for the row. The states are passed in rather than
     * baked into the method name so that "non-terminal" stays defined in one place --
     * {@link ReferralState#isTerminal()} -- instead of being spelled out again in a derived query name
     * that a sixth state would silently not cover.
     *
     * @param deedPollId the parent case
     * @param states     the states to look for, normally the non-terminal ones
     * @return true if at least one referral of that case is in one of those states
     */
    boolean existsByDeedPollIdAndStateIn(String deedPollId, Collection<ReferralState> states);
}
