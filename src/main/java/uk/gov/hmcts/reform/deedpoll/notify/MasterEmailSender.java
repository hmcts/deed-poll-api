package uk.gov.hmcts.reform.deedpoll.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gov.service.notify.NotificationClient;
import uk.gov.service.notify.NotificationClientException;
import uk.gov.service.notify.SendEmailResponse;

import java.util.Map;
import java.util.UUID;

/**
 * The one Notify call a Master email makes (plan D3), on the shared {@link NotificationClient} and classified by
 * {@link NotificationFailure}, exactly as {@link NotifyEmailSender} classifies a case's.
 *
 * <p>Here rather than beside its ledger in {@code domain/masters} because nothing outside this package may touch the
 * client. It sends and classifies, and decides nothing else: which row, and what a verdict does to it, are
 * {@code MasterEmailDelivery}'s. Not {@code GazetteNotify}, whose bounded non-replaying transport exists for file
 * uploads.
 *
 * <p>Only the row id, the verdict and the status are logged and returned: the recipient is a judge's address, and
 * the client's message carries Notify's response body, which may echo it.
 */
@Slf4j
@Service
public class MasterEmailSender {

    private final NotificationClient notify;

    public MasterEmailSender(NotificationClient notify) {
        this.notify = notify;
    }

    /**
     * Sends one email, with no reply-to.
     *
     * @param reference the ledger row's id, which Notify keeps against the notification
     * @return what Notify answered. A {@link RuntimeException} from the client propagates: its outcome is unknown
     */
    public SendOutcome send(String templateId, String recipient, Map<String, ?> personalisation, UUID reference) {
        try {
            SendEmailResponse accepted = notify.sendEmail(templateId, recipient, personalisation,
                                                          reference.toString());
            log.info("Master email {} accepted by Notify as {} (template version {})",
                     reference, accepted.getNotificationId(), accepted.getTemplateVersion());
            return SendOutcome.sent(accepted.getNotificationId(), accepted.getTemplateVersion());
        } catch (NotificationClientException refused) {
            NotificationFailure verdict = NotificationFailure.classify(refused);
            log.warn("Notify would not send Master email {} ({}, status {})", reference, verdict,
                     refused.getHttpResult());
            return SendOutcome.failed(verdict, "GOV.UK Notify refused the send with status "
                + refused.getHttpResult());
        }
    }
}
