package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The processing window on a timer (plan D2, decision D-3): {@link ReferralService#unassignOverdue()} every
 * {@code deedpoll.referrals.unassign-interval}, fifteen minutes by default, so an assignment nobody looks at is still
 * returned to the office.
 *
 * <p>The request-time sweeps stay as well, and racing them is safe for the reason the sweep's class javadoc gives:
 * each release is one conditional statement, so the timer and a request release a referral once between them and
 * write one note.
 *
 * <p><strong>Outside any caller transaction, and never throws into the scheduler.</strong> Each run is the sweep's own
 * transaction, committed before the next run. A failure -- the database unreachable, the settings row missing -- is
 * one WARN in fixed prose naming the exception class only, never its message (which can carry SQL and values), and the
 * next run tries again. {@code deedpoll.referrals.unassign-enabled=false} removes the bean, which is how the
 * Postgres-backed tests keep a timer from releasing their fixtures.
 */
@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(name = "deedpoll.referrals.unassign-enabled", havingValue = "true", matchIfMissing = true)
public class ReferralUnassignment {

    private final ReferralService referrals;

    public ReferralUnassignment(ReferralService referrals) {
        this.referrals = referrals;
    }

    /**
     * One run of the sweep.
     *
     * @throws IllegalStateException if called inside a transaction, where the releases would commit with the caller
     *                               instead of on their own
     */
    @Scheduled(fixedDelayString = "${deedpoll.referrals.unassign-interval:PT15M}",
        initialDelayString = "${deedpoll.referrals.unassign-interval:PT15M}")
    public void run() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The processing-window sweep must run outside any caller transaction");
        }
        try {
            int released = referrals.unassignOverdue();
            if (released > 0) {
                log.info("Processing-window sweep returned {} referrals to the office", released);
            }
        } catch (RuntimeException failure) {
            log.warn("Processing-window sweep failed; the next run will try again ({})",
                     failure.getClass().getName());
        }
    }
}
