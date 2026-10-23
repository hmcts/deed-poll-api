package uk.gov.hmcts.reform.deedpoll.domain.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;

/**
 * The reads the trail records (decision 9), and what a failure to record each one costs the reader.
 *
 * <p><strong>A case view fails open.</strong> The detail page is how a caseworker does anything at all, so an audit
 * outage must not take it down: the failure is logged and the case is answered. {@link AuditTrail} has already
 * logged {@code audit.write.failed} for a failed insert; {@code audit.read.unrecorded} is logged here as well, because
 * a failure before the insert (no connection for the new transaction) reaches only this line.
 *
 * <p><strong>A document download fails closed.</strong> It hands over the applicant's own papers, so a download that
 * could not be recorded does not happen: the failure propagates, and the caller opens nothing. A document preview is
 * the same papers rendered as a PDF, so it fails closed in the same way, under its own action so the trail tells a
 * preview from a download (preview plan, decision 4). Lists and searches are not recorded.
 */
@Component
public class ReadAudit {

    private static final Logger LOG = LoggerFactory.getLogger(ReadAudit.class);

    private final AuditTrail trail;

    public ReadAudit(AuditTrail trail) {
        this.trail = trail;
    }

    /**
     * Record that the reader opened this case. Never throws.
     */
    public void caseViewed(String caseId, CurrentUser reader) {
        try {
            trail.recordRead(AuditEntry.of(AuditAction.CASE_VIEWED, AuditActor.of(reader), AuditEntities.CASE,
                                           caseId, caseId));
        } catch (RuntimeException failure) {
            LOG.error("audit.read.unrecorded action={} entityId={} cause={}",
                      AuditAction.CASE_VIEWED, caseId, failure.getClass().getSimpleName());
        }
    }

    /**
     * Record that the reader is downloading this case's document, before anything is opened.
     *
     * @throws RuntimeException whatever stopped the row being committed; the download must not go ahead
     */
    public void documentDownloaded(String caseId, String documentId, CurrentUser reader) {
        trail.recordRead(AuditEntry.of(AuditAction.DOCUMENT_DOWNLOADED, AuditActor.of(reader),
                                       AuditEntities.DOCUMENT, documentId, caseId));
    }

    /**
     * Record that the reader is previewing this case's document, before its original is opened. Once per preview
     * request: the pages of the one PDF it answers are the browser's, and record nothing more.
     *
     * @throws RuntimeException whatever stopped the row being committed; the preview must not go ahead
     */
    public void documentPreviewed(String caseId, String documentId, CurrentUser reader) {
        trail.recordRead(AuditEntry.of(AuditAction.DOCUMENT_PREVIEWED, AuditActor.of(reader),
                                       AuditEntities.DOCUMENT, documentId, caseId));
    }
}
