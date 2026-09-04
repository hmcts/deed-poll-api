package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;

import java.time.LocalDateTime;

/**
 * Allocates reference numbers.
 *
 * <p>Ported from the allocating half of {@code reference/ts/deed-poll/reference-number.ts}; the pure
 * half -- prefix, format, parse, re-prefix -- is {@link ReferenceNumbers}.
 *
 * <p><strong>Every method here demands the caller's transaction</strong>
 * ({@link Propagation#MANDATORY}), which is the reference's stated contract "must be called inside a
 * transaction" made enforceable. It matters in both directions: the serial must be allocated under
 * the same lock as the row that will carry it, and a create that then fails must give the serial
 * back rather than leaving a gap in an external-facing, permanent sequence. A caller that forgets to
 * open a transaction is told so immediately instead of silently burning serials.
 */
@Service
public class ReferenceNumberService {

    private final ReferenceCounterRepository referenceCounters;

    public ReferenceNumberService(ReferenceCounterRepository referenceCounters) {
        this.referenceCounters = referenceCounters;
    }

    /** What a create needs: the serial to store and the composed reference to display. */
    public record AllocatedReference(int referencenumber, String displayReference) {
    }

    /**
     * Allocates the next serial for a year, atomically. The first record of a year gets 1.
     *
     * <p>The atomicity is the repository's -- see
     * {@link ReferenceCounterRepository#allocateNextSerial(int)} for the lock and why it is one
     * statement -- and the transaction the lock is held for is the caller's.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int allocateSerial(int year) {
        return referenceCounters.allocateNextSerial(year);
    }

    /**
     * On create: derives the year from Date Received, allocates the serial and sets the prefix from
     * Type.
     *
     * @throws uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError if Date Received is absent, so
     *     the route layer answers 400 rather than 500. Nothing is written in that case -- the year is
     *     derived before the counter is touched.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AllocatedReference allocateForCreate(DeedPollType type, LocalDateTime dateReceived) {
        int year = ReferenceNumbers.yearFromDateReceived(dateReceived);
        int serial = allocateSerial(year);
        String prefix = ReferenceNumbers.prefixForType(type);
        return new AllocatedReference(serial, ReferenceNumbers.compose(prefix, year, serial));
    }
}
