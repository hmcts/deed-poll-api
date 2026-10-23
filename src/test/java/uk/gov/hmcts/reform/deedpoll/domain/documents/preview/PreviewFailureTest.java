package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreviewFailureTest {

    @Test
    @DisplayName("the vocabulary is exactly the four published codes, under their statuses")
    void codesAndStatuses() {
        assertThat(Arrays.stream(PreviewFailure.values())
                       .collect(Collectors.toMap(PreviewFailure::code, PreviewFailure::status)))
            .isEqualTo(Map.of(
                "PREVIEW_INVALID_DOCUMENT", 422,
                "PREVIEW_LIMIT_EXCEEDED", 422,
                "PREVIEW_UNAVAILABLE", 503,
                "PREVIEW_BUSY", 429));
    }

    @Test
    @DisplayName("each sentence is fixed and points at the original or a retry")
    void messagesAreFixed() {
        for (PreviewFailure failure : PreviewFailure.values()) {
            assertThat(failure.message()).isNotBlank().doesNotContain("%", "{", "}").contains("original");
        }
    }

    @Test
    @DisplayName("an error carries its failure's sentence and no cause; only busy carries a wait")
    void errorsCarryNothingElse() {
        PreviewError invalid = new PreviewError(PreviewFailure.INVALID_DOCUMENT);
        assertThat(invalid.getMessage()).isEqualTo(PreviewFailure.INVALID_DOCUMENT.message());
        assertThat(invalid.getCause()).isNull();
        assertThat(invalid.retryAfter()).isEmpty();

        PreviewError busy = PreviewError.busy(Duration.ofSeconds(5));
        assertThat(busy.failure()).isEqualTo(PreviewFailure.BUSY);
        assertThat(busy.retryAfter()).contains(Duration.ofSeconds(5));
        assertThatThrownBy(() -> new PreviewError(null)).isInstanceOf(NullPointerException.class);
    }
}
