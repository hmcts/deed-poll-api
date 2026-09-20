package uk.gov.hmcts.reform.deedpoll.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.reform.deedpoll.entity.Notification;
import uk.gov.service.notify.NotificationClient;
import uk.gov.service.notify.NotificationClientException;
import uk.gov.service.notify.SendEmailResponse;

import java.util.Map;

/**
 * The one thing in this service that sends an email: hands one claimed outbox row to GOV.UK Notify and reports
 * what came back in the vocabulary the outbox settles rows with.
 *
 * <p>The implementation of {@link NotificationSender} the outbox was written to work without. Adding this bean is
 * what turns the outbox from a durable record of owed emails into a service that sends them; until this class
 * existed a drain claimed nothing and every row stayed {@code Pending}, which was the correct behaviour rather
 * than a gap.
 *
 * <p><strong>It sends and it classifies, and it does nothing else.</strong> It does not decide which row to take
 * (the claim does), what a failure does to the row ({@link NotificationOutbox#settle} does), when to try again
 * ({@link NotificationBackoff} does), or what the email says (the row does -- the recipient and the
 * personalisation were resolved and frozen when the caseworker sent it). Keeping those apart is what lets the
 * state machine be proved with no network and the taxonomy be proved with no database.
 *
 * <p><strong>The row id is the Notify {@code reference}.</strong> Notify stores it against the notification and
 * returns it on every delivery query, so a row and a notification are mutually traceable from either end without
 * a second identifier to keep in step -- which is what makes the delivery receipts this plan excludes an additive
 * change rather than a migration.
 *
 * <p><strong>Nothing is recomputed from the case.</strong> The template's Notify UUID comes from configuration
 * and everything else comes off the row. A sender that re-resolved the personalisation would send something the
 * caseworker never approved whenever the case had been edited in between, and would make "what was this applicant
 * told" a question about the state of the case at drain time.
 *
 * <p><strong>Until notify-0 provisions the Notify service every send here fails, and fails permanently.</strong>
 * The API key is empty, so Notify answers 403 -- {@link NotificationFailure#PERMANENT} -- and the row is failed
 * with that on it rather than retried three hours into a backoff that cannot help. That is the designed answer to
 * an unprovisioned service, and {@code EmailAvailability.provisioned} is how the Email tab says so before a
 * caseworker presses send.
 */
@Service
public class NotifyEmailSender implements NotificationSender {

    private static final Logger LOG = LoggerFactory.getLogger(NotifyEmailSender.class);

    private final NotificationClient notify;
    private final NotifyProperties properties;

    public NotifyEmailSender(NotificationClient notify, NotifyProperties properties) {
        this.notify = notify;
        this.properties = properties;
    }

    /**
     * Sends one row, and answers with a verdict rather than an exception.
     *
     * <p>{@link NotificationClientException} is the only failure the client reports, and every one of them is
     * classified by {@link NotificationFailure#classify(NotificationClientException)} -- in one place, here, so
     * the outbox never reads a status code. A call that never reached Notify carries status {@code 0} and falls to
     * {@link NotificationFailure#TECHNICAL}, which is terminal on purpose: the request may have been delivered,
     * this plan has no delivery query to ask with, and a second letter telling an applicant their application was
     * refused is worse than a failed row somebody can chase.
     *
     * <p>The client's message is logged and summarised rather than returned whole: it contains Notify's response
     * body, and the row's {@code failureReason} is displayed to a caseworker.
     *
     * @param notification the claimed row, with its attempt already counted
     * @return {@link SendOutcome.Sent} with Notify's notification id, template version and rendered wording, or
     *     {@link SendOutcome.Failed} with the taxonomy's verdict
     */
    @Override
    public SendOutcome send(Notification notification) {
        EmailTemplate template = EmailTemplate.byName(notification.getTemplate()).orElse(null);
        if (template == null) {
            // An EmailTemplate member renamed after this row was written -- the one thing the class javadoc on
            // Notification warns about. Permanent because no retry resolves a member that no longer exists, and
            // named loudly because the fix is a code change rather than an operational one.
            LOG.error("Notification {} for case {} names template {}, which no EmailTemplate member matches",
                      notification.getId(), notification.getDeedPollId(), notification.getTemplate());
            return SendOutcome.failed(
                NotificationFailure.PERMANENT,
                "no such email template: " + notification.getTemplate());
        }

        String templateId = template.templateId(properties);
        // The row id, which Notify keeps against the notification. Bare rather than prefixed: a reference that
        // needed unwrapping to be matched back to a row would defeat the point of storing it.
        String reference = notification.getId().toString();
        Map<String, Object> personalisation = notification.getPersonalisation();

        try {
            SendEmailResponse accepted =
                notify.sendEmail(templateId, notification.getRecipient(), personalisation, reference);
            LOG.info("Notification {} for case {} accepted by Notify as {} (template {} version {})",
                     notification.getId(), notification.getDeedPollId(), accepted.getNotificationId(),
                     template.key(), accepted.getTemplateVersion());
            return SendOutcome.sent(
                accepted.getNotificationId(),
                accepted.getTemplateVersion(),
                accepted.getSubject(),
                accepted.getBody());
        } catch (NotificationClientException refused) {
            NotificationFailure verdict = NotificationFailure.classify(refused);
            LOG.warn("Notify would not send notification {} for case {} ({}, status {})",
                     notification.getId(), notification.getDeedPollId(), verdict, refused.getHttpResult(),
                     refused);
            return SendOutcome.failed(verdict, "GOV.UK Notify refused the send with status "
                + refused.getHttpResult());
        }
    }
}
