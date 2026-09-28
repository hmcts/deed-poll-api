package uk.gov.hmcts.reform.deedpoll.ccd;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingService.Result;
import uk.gov.hmcts.reform.deedpoll.entity.CcdCreationAttempt;

import java.util.function.LongSupplier;

/**
 * Links a few unlinked records and reconciles a few outstanding attempts, as a side effect of the case list request
 * (plan 02b, decisions 1 to 5) -- the way {@code NotificationOutbox.drain} empties the outbox, and for the same
 * reason: this service has no scheduler, so the work hangs off the caseworker traffic that is already there.
 *
 * <p><strong>Off unless {@code deedpoll.ccd.linking.sweep.enabled}</strong>, and while it is off it calls nothing
 * else in this package. {@link CcdLinkingSweepProperties} says why switching it on waits for onboarding.
 *
 * <p><strong>Bounded, and it stops at the first sign CCD is unwell.</strong> At most {@code max-records} links, then
 * at most {@code max-reconciliations} reads; the first {@code RETRY_LATER}, {@code CCD_UNANSWERED} or exception ends
 * the whole sweep, reconciliation included, so a CCD outage costs one list request one failed call rather than
 * several. What that call did is already on the attempt's row, so nothing is lost by stopping. A CCD or IDAM that is
 * slow but answers in time fails no call, so the counts alone would hold the list for every call they allow:
 * {@code max-duration} is checked before each link and each read, and once it has passed nothing more is started.
 *
 * <p><strong>It never throws into the list response.</strong> A failure is logged in fixed prose -- the exception's
 * class name, never its message, which for Feign quotes a response body -- and the list, already computed, is
 * returned as it was.
 *
 * <p><strong>Never inside a transaction</strong> (plan 02, decision 9): {@link CcdLinkingService} commits each claim
 * before CCD is called, and data-store's callback must be able to read it and lock the record. A caller in a
 * transaction is a wiring fault, not a CCD one, so it is refused with an exception rather than logged away.
 */
@Slf4j
@Service
public class CcdLinkingSweep {

    private final CcdLinkingSweepProperties properties;
    private final CcdLinkingService linking;
    private final CcdCreationAttempts attempts;
    private final LongSupplier nanoTime;

    @Autowired
    public CcdLinkingSweep(CcdLinkingSweepProperties properties,
                           CcdLinkingService linking,
                           CcdCreationAttempts attempts) {
        this(properties, linking, attempts, System::nanoTime);
    }

    CcdLinkingSweep(CcdLinkingSweepProperties properties,
                    CcdLinkingService linking,
                    CcdCreationAttempts attempts,
                    LongSupplier nanoTime) {
        this.properties = properties;
        this.linking = linking;
        this.attempts = attempts;
        this.nanoTime = nanoTime;
    }

    /**
     * Runs one sweep, if it is switched on.
     *
     * @throws IllegalStateException if called inside a transaction
     */
    public void sweep() {
        if (!properties.enabled()) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The CCD linking sweep must not run inside a transaction");
        }
        long deadline = nanoTime.getAsLong() + properties.maxDuration().toNanos();
        try {
            if (linkDue(deadline)) {
                reconcileOutstanding(deadline);
            }
        } catch (RuntimeException failure) {
            log.warn("The CCD linking sweep stopped on a failure: {}", failure.getClass().getSimpleName());
        }
    }

    /** Links the records at the head of the queue; false if it stopped because CCD is unwell or time ran out. */
    private boolean linkDue(long deadline) {
        for (String deedPollId : attempts.dueForLinking(properties.maxRecords())) {
            if (outOfTime(deadline)) {
                return false;
            }
            Result result = linking.link(deedPollId);
            if (stopsTheSweep(result)) {
                log.info("The CCD linking sweep stopped on {}", result);
                return false;
            }
        }
        return true;
    }

    private void reconcileOutstanding(long deadline) {
        for (CcdCreationAttempt outstanding : attempts.outstanding(properties.maxReconciliations())) {
            if (outOfTime(deadline)) {
                return;
            }
            Result result = linking.reconcileOne(outstanding);
            if (stopsTheSweep(result)) {
                log.info("The CCD linking sweep stopped on {}", result);
                return;
            }
        }
    }

    /**
     * Whether {@code max-duration} has passed, checked before anything more is started.
     */
    private boolean outOfTime(long deadline) {
        if (nanoTime.getAsLong() - deadline < 0) {
            return false;
        }
        log.info("The CCD linking sweep stopped at max-duration");
        return true;
    }

    /**
     * Whether one result ends the sweep (decision 4). {@code RETRY_LATER}: nothing was created and CCD, IDAM or S2S
     * refused or failed. {@code CCD_UNANSWERED}: a submission or a read got no usable answer, which within the
     * client's timeouts can cost seconds. Either way the next record would most likely meet the same failure. Every
     * other result is about the one record -- another worker holds it, it is waiting out the settle window, it needs a
     * person -- and says nothing about the next. An exception ends the sweep too, and {@link #sweep} catches it.
     */
    static boolean stopsTheSweep(Result result) {
        return result == Result.RETRY_LATER || result == Result.CCD_UNANSWERED;
    }
}
