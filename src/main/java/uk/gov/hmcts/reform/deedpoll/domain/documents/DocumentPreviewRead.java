package uk.gov.hmcts.reform.deedpoll.domain.documents;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;

import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * A document a preview may read: the row, already found in its case and checked readable, and the one opening of
 * its original that the preview is allowed.
 *
 * <p>Unlike a {@link DocumentDownload}, nothing is open yet. A preview may be refused before it reads a byte (the
 * pod's conversions are all taken, the converter is not configured, the caller went away), and such a refusal should
 * neither record a read nor hold a stream. So {@link #open} is where the {@code DOCUMENT_PREVIEWED} row is committed,
 * fail-closed, and only then is the store opened under the reader's own authority -- on the thread that calls it.
 *
 * <p>It opens once. A second call is a programming error rather than a second audited read.
 */
public final class DocumentPreviewRead {

    private final DeedPollSupportingDocument document;
    private final Supplier<InputStream> opener;
    private final AtomicBoolean opened = new AtomicBoolean();

    DocumentPreviewRead(DeedPollSupportingDocument document, Supplier<InputStream> opener) {
        this.document = Objects.requireNonNull(document, "document");
        this.opener = Objects.requireNonNull(opener, "opener");
    }

    /** The metadata row, scoped to its case; its declared MIME type chooses the preview's format. */
    public DeedPollSupportingDocument document() {
        return document;
    }

    /**
     * Records the preview, then opens the original. The caller closes the stream.
     *
     * @throws RuntimeException whatever the audit insert or the store threw, unchanged; nothing is open after either
     * @throws IllegalStateException if this read has already been opened
     */
    public InputStream open() {
        if (!opened.compareAndSet(false, true)) {
            throw new IllegalStateException("A preview opens its document once");
        }
        return opener.get();
    }
}
