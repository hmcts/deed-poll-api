package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.repository.GazetteRequestRepository;

import java.util.UUID;

/** Bounded recovery only. Runs even with new delivery disabled and never calls a sender. */
@Slf4j
@Service
@EnableScheduling
public class GazetteRecovery {
    private final GazetteRequestRepository requests;
    private final GazetteRequestLedger ledger;

    public GazetteRecovery(GazetteRequestRepository requests, GazetteRequestLedger ledger) {
        this.requests = requests;
        this.ledger = ledger;
    }

    @Scheduled(initialDelayString = "${deedpoll.gazette.recovery-initial-delay-ms:60000}", fixedDelay = 60_000)
    public void recover() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Gazette recovery requires independent commits");
        }
        for (UUID id : requests.expired(AuditClock.now())) {
            try {
                ledger.expire(id);
            } catch (RuntimeException unavailable) {
                log.warn("Gazette batch {} recovery unconfirmed; retained for a later scan", id);
            }
        }
    }
}
