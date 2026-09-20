package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.entity.enums.NotificationState;
import uk.gov.hmcts.reform.deedpoll.notify.CaseCorrespondence;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One email attempt in a case's correspondence history.
 *
 * <p>The body is GOV.UK Notify's plain-text rendering. This service cannot know whether the applicant opened
 * the email or downloaded a linked document.
 *
 * @param id the outbox row id and Notify reference
 * @param template the stable template key
 * @param templateName the human-readable template name
 * @param createdOn when the caseworker requested the send
 * @param createdBy the caseworker who requested it
 * @param recipient the email address frozen when the row was enqueued
 * @param state the outbox state
 * @param failureReason the recorded failure, or null when none exists
 * @param subject the rendered subject, or null when no accepted send was captured
 * @param body the rendered plain-text wording, or null when no accepted send was captured
 * @param documents case documents carried by the email
 */
public record EmailHistoryResponse(
    String id,
    String template,
    String templateName,
    LocalDateTime createdOn,
    String createdBy,
    String recipient,
    NotificationState state,
    String failureReason,
    String subject,
    String body,
    List<CorrespondenceDocumentResponse> documents
) {

    static EmailHistoryResponse from(CaseCorrespondence correspondence) {
        return new EmailHistoryResponse(
            correspondence.id().toString(),
            correspondence.template(),
            correspondence.templateName(),
            correspondence.createdOn(),
            correspondence.createdBy(),
            correspondence.recipient(),
            correspondence.state(),
            correspondence.failureReason(),
            correspondence.subject(),
            correspondence.body(),
            CorrespondenceDocumentResponse.from(correspondence.documents()));
    }

    static List<EmailHistoryResponse> from(List<CaseCorrespondence> correspondence) {
        return correspondence.stream().map(EmailHistoryResponse::from).toList();
    }
}
