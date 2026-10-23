package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadPolicy;

import java.util.Arrays;
import java.util.Optional;

/**
 * Every format a supporting document may be uploaded in, and how its preview is made.
 *
 * <p>One member per entry of {@link UploadPolicy#ALLOWED_UPLOAD_MIME_TYPES}, no more and no fewer, which
 * {@code PreviewFormatTest} holds: a type the upload policy admits with no member here would be a document a
 * caseworker can upload and never preview. The dependency runs this way round only. Nothing here widens or narrows
 * what an upload accepts, and a stored type outside the list (a legacy {@code .doc}, or a value set through the
 * metadata path) has no preview at all rather than a guessed one.
 *
 * <p>A PDF is passed through after checks, so it takes no conversion permit. Every other format is rendered or
 * converted, and is what {@link ConversionCapacity} counts (decision 5).
 */
public enum PreviewFormat {
    PDF("application/pdf", false),
    JPEG("image/jpeg", true),
    PNG("image/png", true),
    TIFF("image/tiff", true),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", true);

    private final String mimeType;
    private final boolean conversion;

    PreviewFormat(String mimeType, boolean conversion) {
        this.mimeType = mimeType;
        this.conversion = conversion;
    }

    public String mimeType() {
        return mimeType;
    }

    /** Whether making the preview is a conversion, and so needs one of the pod's few permits. */
    public boolean isConversion() {
        return conversion;
    }

    /**
     * The format stored under this type, by exact match as {@link UploadPolicy#isAllowedUploadType} matches.
     */
    public static Optional<PreviewFormat> of(String mimeType) {
        return Arrays.stream(values()).filter(format -> format.mimeType.equals(mimeType)).findFirst();
    }

    /**
     * {@link #of}, refusing a type with no preview as an invalid document.
     *
     * @throws PreviewError {@link PreviewFailure#INVALID_DOCUMENT} for any type outside the upload allowlist
     */
    public static PreviewFormat require(String mimeType) {
        return of(mimeType).orElseThrow(() -> new PreviewError(PreviewFailure.INVALID_DOCUMENT));
    }
}
