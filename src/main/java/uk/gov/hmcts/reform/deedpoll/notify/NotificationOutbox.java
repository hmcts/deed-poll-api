package uk.gov.hmcts.reform.deedpoll.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/** Traffic-triggered, bounded drain. Each claim and outcome commits independently of remote work. */
@Slf4j
@Service
public class NotificationOutbox {
    public static final int DEFAULT_LIMIT = 5;

    private final NotificationDeliveryTransactions transactions;
    private final Optional<NotificationSender> sender;

    public NotificationOutbox(NotificationDeliveryTransactions transactions, Optional<NotificationSender> sender) {
        this.transactions = transactions;
        this.sender = sender;
    }

    public int drain() {
        return drain(DEFAULT_LIMIT);
    }

    public int drain(int limit) {
        if (limit <= 0 || sender.isEmpty()) {
            return 0;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Notification drain must run outside a transaction");
        }
        LocalDateTime cutoff = AuditClock.now();
        int sent = 0;
        for (UUID id : transactions.candidates(cutoff, limit)) {
            Optional<NotificationClaim> claimed = transactions.claim(id, cutoff);
            if (claimed.isEmpty()) {
                continue;
            }
            NotificationClaim claim = claimed.orElseThrow();
            PreparedNotification prepared;
            try {
                prepared = sender.orElseThrow().prepare(claim.notification());
            } catch (RuntimeException exception) {
                log.warn("Notification {} preparation failed", claim.id(), NotificationDiagnostics.redacted(exception));
                prepared = new PreparedNotification.Refused(SendOutcome.failed(
                    NotificationFailure.TEMPORARY, "Could not prepare the notification"));
            }
            SendOutcome outcome;
            if (prepared instanceof PreparedNotification.Refused refused) {
                outcome = refused.outcome();
            } else {
                // Do not catch transaction/commit failures here: an uncertain commit cannot authorize sending.
                if (!transactions.startSend(claim)) {
                    log.error("Notification {} send authority rejected for attempt {}; nothing sent",
                        claim.id(), claim.generation());
                    continue;
                }
                try {
                    outcome = ((PreparedNotification.Ready) prepared).delivery().send();
                } catch (RuntimeException exception) {
                    log.error("Notification {} delivery outcome is uncertain", claim.id(),
                        NotificationDiagnostics.redacted(exception));
                    outcome = SendOutcome.failed(NotificationFailure.TECHNICAL,
                        "Delivery outcome is uncertain; investigate before any resend");
                }
            }
            // A failed/lost commit stops this drain. The durable send marker prevents another delivery.
            if (transactions.complete(claim, outcome)) {
                sent++;
            }
        }
        return sent;
    }
}
