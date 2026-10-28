package uk.gov.hmcts.reform.deedpoll.domain.masters;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gov.hmcts.reform.deedpoll.notify.MasterEmailSender;
import uk.gov.hmcts.reform.deedpoll.notify.NotificationFailure;
import uk.gov.hmcts.reform.deedpoll.notify.SendOutcome;

import java.util.Optional;
import java.util.UUID;

/**
 * Sends the Master email ledger's pending rows (plan D3, step 4): after each job enqueues, and on
 * {@link MasterEmailDrain}'s timer.
 *
 * <p><strong>Outside any caller transaction.</strong> Each step is {@link MasterEmailTransactions}' own commit, and
 * the Notify call is between commits: the claim, then the content read, then {@code Sending}, then the one call,
 * then its outcome. A lost or failed commit of {@code Sending} stops the row before anything is sent; a lost outcome
 * leaves it {@code Sending}, which the next drain fails once its lease ends, and never resends.
 */
@Slf4j
@Service
public class MasterEmailDelivery {

    /** At most this many rows per drain. */
    public static final int DEFAULT_LIMIT = 25;

    private final MasterEmailTransactions transactions;
    private final MasterEmailSender sender;

    public MasterEmailDelivery(MasterEmailTransactions transactions, MasterEmailSender sender) {
        this.transactions = transactions;
        this.sender = sender;
    }

    public int drain() {
        return drain(DEFAULT_LIMIT);
    }

    /**
     * One bounded drain.
     *
     * @return how many emails Notify accepted
     * @throws IllegalStateException inside a transaction, where a claim would not be committed before the call
     */
    public int drain(int limit) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The Master email drain must run outside a transaction");
        }
        transactions.failAbandoned();
        int sent = 0;
        for (UUID id : transactions.candidates(limit)) {
            Optional<MasterEmailClaim> claimed = transactions.claim(id);
            if (claimed.isPresent() && deliver(claimed.get())) {
                sent++;
            }
        }
        return sent;
    }

    private boolean deliver(MasterEmailClaim claim) {
        Optional<MasterEmailContent> content;
        try {
            content = transactions.content(claim);
        } catch (RuntimeException failure) {
            // Before Sending, so nothing was sent and another attempt is safe.
            log.warn("Master email {} could not be prepared ({})", claim.id(), failure.getClass().getName());
            return transactions.complete(claim, SendOutcome.failed(NotificationFailure.TEMPORARY,
                                                                   "Could not prepare the email"));
        }
        if (content.isEmpty()) {
            transactions.skip(claim);
            return false;
        }
        // Not caught: an uncertain commit cannot authorise a send.
        if (!transactions.startSend(claim)) {
            log.error("Master email {} send authority rejected for attempt {}; nothing sent", claim.id(),
                      claim.attempt());
            return false;
        }
        MasterEmailContent email = content.get();
        SendOutcome outcome;
        try {
            outcome = sender.send(email.templateId(), email.recipient(), email.personalisation(), claim.id());
        } catch (RuntimeException failure) {
            log.error("Master email {} delivery outcome is uncertain ({})", claim.id(),
                      failure.getClass().getName());
            outcome = SendOutcome.failed(NotificationFailure.TECHNICAL,
                                         "Delivery outcome is uncertain; investigate before any resend");
        }
        return transactions.complete(claim, outcome);
    }
}
