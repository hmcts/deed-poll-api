package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * What a document deletion request's body was, as one value a replay is compared with: the same key with the same
 * fingerprint is the same request, and the same key with another is {@code KEY_REUSED}.
 *
 * <p>SHA-256 over a versioned, line-separated encoding of the document id, the reviewed version and the reason last,
 * so no field can run into the next: neither of the first two can contain a line break. Lower-case hex, as
 * {@code V51} checks it. The document is part of it because a key is unique within the case, not the document.
 */
public final class DocumentDeletionFingerprint {

    private static final String VERSION = "document-deletion-request-v1";

    private DocumentDeletionFingerprint() {
    }

    /** The fingerprint of this body. */
    public static String of(String documentId, long expectedDocumentVersion, DocumentDeletionReason reason) {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(reason, "reason");
        if (documentId.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("A document id has no line break");
        }
        String encoded = VERSION + '\n' + documentId + '\n' + expectedDocumentVersion + '\n' + reason.value();
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(encoded.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", impossible);
        }
    }
}
