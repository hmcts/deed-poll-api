package uk.gov.hmcts.reform.deedpoll.notify;

import uk.gov.hmcts.reform.deedpoll.entity.Notification;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NotificationState;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One email attempt recorded against a case.
 *
 * <p>{@code Sent} means GOV.UK Notify accepted the email. This service does not know whether it was delivered,
 * opened, or whether any linked document was downloaded.
 *
 * <p>The subject and body are null until an email is accepted. The body is Notify's plain-text rendering, not
 * the branded HTML the applicant received. Raw personalisation is deliberately absent from this type.
 *
 * @param id the outbox row id and Notify reference
 * @param template the stable template key
 * @param templateName the human-readable template name
 * @param createdOn when the caseworker requested the send
 * @param createdBy the caseworker who requested it
 * @param recipient the email address frozen when the row was enqueued
 * @param state the outbox state, where Sent means accepted by Notify rather than delivered
 * @param failureReason the recorded failure, or null when none exists
 * @param subject the subject Notify returned for an accepted send, otherwise null
 * @param body the plain-text body Notify returned for an accepted send, otherwise null
 * @param documents the case documents referenced by the frozen personalisation
 */
public record CaseCorrespondence(
    UUID id,
    String template,
    String templateName,
    LocalDateTime createdOn,
    String createdBy,
    String recipient,
    NotificationState state,
    String failureReason,
    String subject,
    String body,
    List<CorrespondenceDocument> documents
) {

    static CaseCorrespondence from(
        Notification notification,
        EmailTemplate template,
        List<CorrespondenceDocument> documents
    ) {
        return new CaseCorrespondence(
            notification.getId(),
            template.key(),
            template.displayName(),
            notification.getCreatedOn(),
            notification.getCreatedBy(),
            notification.getRecipient(),
            notification.getState(),
            notification.getFailureReason(),
            notification.getSubject(),
            notification.getBody(),
            List.copyOf(documents));
    }
}
