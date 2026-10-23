package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadPolicy;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreviewFormatTest {

    @Test
    @DisplayName("every type an upload accepts has exactly one preview format, and nothing else does")
    void coversTheUploadAllowlistExactly() {
        assertThat(Arrays.stream(PreviewFormat.values()).map(PreviewFormat::mimeType).collect(Collectors.toSet()))
            .isEqualTo(UploadPolicy.ALLOWED_UPLOAD_MIME_TYPES)
            .hasSize(PreviewFormat.values().length);
        UploadPolicy.ALLOWED_UPLOAD_MIME_TYPES.forEach(type -> assertThat(PreviewFormat.of(type)).isPresent());
    }

    @Test
    @DisplayName("only a PDF passes through without a conversion permit")
    void onlyPdfIsNotAConversion() {
        assertThat(Arrays.stream(PreviewFormat.values()).filter(format -> !format.isConversion()))
            .containsExactly(PreviewFormat.PDF);
    }

    @Test
    @DisplayName("a historical, parameterised, differently cased or missing type has no preview")
    void unsupportedTypesAreInvalidDocuments() {
        for (String type : new String[] {"application/msword", "application/pdf; charset=binary", "IMAGE/PNG",
            "image/gif", "image/svg+xml", "text/html", "", null}) {
            assertThat(PreviewFormat.of(type)).as(String.valueOf(type)).isEmpty();
            assertThatThrownBy(() -> PreviewFormat.require(type))
                .isInstanceOfSatisfying(PreviewError.class,
                    error -> assertThat(error.failure()).isEqualTo(PreviewFailure.INVALID_DOCUMENT));
        }
        assertThat(PreviewFormat.require("image/tiff")).isEqualTo(PreviewFormat.TIFF);
    }
}
