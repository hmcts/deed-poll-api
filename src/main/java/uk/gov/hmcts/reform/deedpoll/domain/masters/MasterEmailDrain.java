package uk.gov.hmcts.reform.deedpoll.domain.masters;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The Master email ledger's drain on a timer, every {@code deedpoll.masters.drain-interval} (a minute), beside the
 * drain each job runs after enqueueing. Racing them is safe: a row is claimed once.
 *
 * <p>Never throws into the scheduler: a failure is one WARN naming the exception class only, never its message
 * (which can carry SQL and values), and the next run tries again. {@code deedpoll.masters.emails-enabled=false}
 * removes the bean, which is how the Postgres-backed tests keep a timer off their fixtures.
 */
@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(name = "deedpoll.masters.emails-enabled", havingValue = "true", matchIfMissing = true)
public class MasterEmailDrain {

    private final MasterEmailDelivery delivery;

    public MasterEmailDrain(MasterEmailDelivery delivery) {
        this.delivery = delivery;
    }

    @Scheduled(fixedDelayString = "${deedpoll.masters.drain-interval:PT1M}",
        initialDelayString = "${deedpoll.masters.drain-interval:PT1M}")
    public void run() {
        try {
            delivery.drain();
        } catch (RuntimeException failure) {
            log.warn("Master email drain failed; the next run will try again ({})", failure.getClass().getName());
        }
    }
}
