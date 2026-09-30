package uk.gov.hmcts.reform.deedpoll.ccd;

import java.util.Optional;
import java.util.UUID;

/**
 * The document URLs a case's {@code supportingDocuments} carries, stated once (plan 03a, task 5).
 *
 * <p>The event that attaches a document and the view that shows it must write the same URL: CDAM's download check
 * asks data-store whether the document appears in the case's data, and data-store answers from the view
 * (docs/CDAM-SEQUENCE.md section 3). CDAM itself reads only the last 36 characters, the document id, and never dials
 * the host, which is why the base is a dm-store URL data-store's pattern accepts rather than an address anyone calls.
 */
public final class CaseDocumentUrls {

    private static final String DOCUMENTS = "/documents/";
    private static final String BINARY = "/binary";
    private static final int UUID_LENGTH = 36;

    private CaseDocumentUrls() {
    }

    /**
     * The document's URL under {@code base}, which carries no trailing slash.
     */
    public static String url(String base, UUID cdamDocumentId) {
        return base + DOCUMENTS + cdamDocumentId;
    }

    /**
     * The document's binary URL under {@code base}.
     */
    public static String binaryUrl(String base, UUID cdamDocumentId) {
        return url(base, cdamDocumentId) + BINARY;
    }

    /**
     * The CDAM document id a URL names, read as CDAM reads it: the last 36 characters, after the last
     * {@code /documents/}. Empty for anything else.
     *
     * <p>Only ever <em>compared</em> with the id on a row, never stored: a CDAM id is never taken from a request
     * (decision 7), and an event payload is one.
     */
    public static Optional<UUID> cdamDocumentIdOf(String url) {
        if (url == null) {
            return Optional.empty();
        }
        int at = url.lastIndexOf(DOCUMENTS);
        if (at < 0 || url.length() - (at + DOCUMENTS.length()) != UUID_LENGTH) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(url.substring(at + DOCUMENTS.length())));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }
}
