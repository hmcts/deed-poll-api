package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentPreviewRead;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentService;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.JudicialReferralService;

import java.time.Clock;
import java.util.Objects;

/**
 * A preview of one supporting document, for a member of staff through its case or a judge through a referral.
 *
 * <p><strong>Who may read what is decided upstream, once.</strong> A caseworker's document is
 * {@link DocumentService#previewDocument} scoped to the case in the path; a judge's is
 * {@link JudicialReferralService#previewDocument}, the judicial download's own role gate and visibility. Nothing
 * here repeats a role rule, and every refusal those make -- the indistinguishable 404s, the judicial 403 -- reaches
 * the caller unchanged, before any format is chosen, any permit taken or any converter called.
 *
 * <p><strong>Then, in order:</strong> the stored MIME type picks the format (outside the upload allowlist is
 * {@link PreviewFailure#INVALID_DOCUMENT}, nothing read); {@link PreviewRenderer} takes a permit if the format
 * needs one; and only then does it open the source, which commits {@code DOCUMENT_PREVIEWED} and opens the store
 * under the reader's authority. A busy pod, an unconfigured converter or a caller who has already gone records and
 * opens nothing; an audit that cannot be committed opens and converts nothing.
 *
 * <p><strong>Never inside a transaction.</strong> The store may be CDAM and the converter is remote, and neither may
 * hold a database transaction open, so a caller that brings one is refused before anything happens rather than left
 * to hold it for up to the whole budget.
 *
 * <p>A preview writes nothing but its read audit: no document row, attachment, notification or cache. The PDF
 * exists only in its request's private scratch directory, deleted when the {@link RenderedPreview} is closed.
 */
@Service
public class DocumentPreviewService {

    private final DocumentService documents;
    private final JudicialReferralService judicialReferrals;
    private final PreviewRenderer renderer;
    private final PreviewProperties properties;
    private final Clock clock;

    @Autowired
    public DocumentPreviewService(DocumentService documents,
                                  JudicialReferralService judicialReferrals,
                                  PreviewRenderer renderer,
                                  PreviewProperties properties) {
        this(documents, judicialReferrals, renderer, properties, Clock.systemUTC());
    }

    DocumentPreviewService(DocumentService documents,
                           JudicialReferralService judicialReferrals,
                           PreviewRenderer renderer,
                           PreviewProperties properties,
                           Clock clock) {
        this.documents = Objects.requireNonNull(documents, "documents");
        this.judicialReferrals = Objects.requireNonNull(judicialReferrals, "judicialReferrals");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * A fresh request budget (decision 2), started now. The caller keeps it so that it can cancel it.
     */
    public PreviewBudget budget() {
        return PreviewBudget.start(clock, properties.requestBudget());
    }

    /**
     * The preview of a case's document, for the member of staff reading it.
     *
     * @return the complete PDF, which the caller streams and then closes
     * @throws NotFoundError as the original download would, before anything is recorded or opened
     * @throws PreviewError for any of {@link PreviewFailure}'s reasons
     * @throws PreviewCancelledException if the budget was cancelled
     * @throws IllegalStateException if called inside a transaction
     */
    public RenderedPreview caseworkerPreview(String caseId, String documentId, CurrentUser reader,
                                             PreviewBudget budget) {
        requireNoTransaction();
        return render(documents.previewDocument(caseId, documentId, reader), budget);
    }

    /**
     * The preview of a document behind a referral this judge may see.
     *
     * @return the complete PDF, which the caller streams and then closes
     * @throws ForbiddenError if the caller does not hold the judicial role
     * @throws NotFoundError as the judicial download would, before anything is recorded or opened
     * @throws PreviewError for any of {@link PreviewFailure}'s reasons
     * @throws PreviewCancelledException if the budget was cancelled
     * @throws IllegalStateException if called inside a transaction
     */
    public RenderedPreview judicialPreview(String referralId, String documentId, CurrentUser caller,
                                           PreviewBudget budget) {
        requireNoTransaction();
        return render(judicialReferrals.previewDocument(referralId, documentId, caller), budget);
    }

    private RenderedPreview render(DocumentPreviewRead read, PreviewBudget budget) {
        PreviewFormat format = PreviewFormat.require(read.document().getMimeType());
        return renderer.render(format, read::open, budget);
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                "A document preview reads the store and may call the converter, so it must run outside a transaction");
        }
    }
}
