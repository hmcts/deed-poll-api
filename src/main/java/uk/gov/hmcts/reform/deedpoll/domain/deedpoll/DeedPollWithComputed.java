package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

/**
 * A case together with its derived day counts.
 *
 * <p>Ported from the reference's {@code WithComputed<T>} / {@code DeedPollWithComputed}. The
 * reference spreads the derived fields onto a copy of the record; a JPA entity must not be copied
 * (the managed instance is the one that gets written back), so the two are paired instead. Nothing
 * is attached to the entity, which is what the reference's "never mutates the input" test asserts.
 *
 * <p>The reference needs a second type, {@code DeedPollWithChildren}, because a plain object either
 * has the child collections or does not, and one type covering both would have to lie about one of
 * them. Here the children are mapped associations on {@link DeedPoll}: {@code findDeedPoll} fetches
 * them and {@code findDeedPolls} leaves them lazy, so the shape is the same and only the fetch
 * differs. Hence one type, not two.
 */
public record DeedPollWithComputed(DeedPoll deedPoll, ComputedFields computed) {
}
