package uk.gov.hmcts.reform.deedpoll.api;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletResponse;
import uk.gov.hmcts.reform.deedpoll.domain.documents.preview.ConversionCapacity;
import uk.gov.hmcts.reform.deedpoll.domain.documents.preview.PreviewBudget;
import uk.gov.hmcts.reform.deedpoll.domain.documents.preview.PreviewFormat;
import uk.gov.hmcts.reform.deedpoll.domain.documents.preview.PreviewProperties;
import uk.gov.hmcts.reform.deedpoll.domain.documents.preview.PreviewRenderer;
import uk.gov.hmcts.reform.deedpoll.domain.documents.preview.RenderedPreview;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentFileResponsesTest {

    @TempDir
    Path scratch;

    @Test
    @DisplayName("a caller who goes away half way through the preview's response leaves no scratch file behind")
    void disconnectMidResponse() throws IOException {
        byte[] pdf = pdf();
        PreviewProperties defaults = new PreviewProperties(null, null, null, null, null, null, null, null);
        RenderedPreview preview = new PreviewRenderer(defaults,
                                                      new ConversionCapacity(3, Duration.ofSeconds(5)), scratch)
            .render(PreviewFormat.PDF, () -> new ByteArrayInputStream(pdf),
                    PreviewBudget.start(Clock.systemUTC(), PreviewProperties.REQUEST_BUDGET));
        assertThat(entries()).isEqualTo(1);

        assertThatThrownBy(() -> DocumentFileResponses.writePreview(preview, new ResetAfter(16)))
            .isInstanceOf(IOException.class);

        assertThat(entries()).isZero();
    }

    private long entries() throws IOException {
        try (Stream<Path> children = Files.list(scratch)) {
            return children.count();
        }
    }

    private static byte[] pdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        }
    }

    /**
     * A response whose connection is reset once {@code limit} bytes have gone.
     */
    private static final class ResetAfter extends MockHttpServletResponse {

        private final int limit;

        ResetAfter(int limit) {
            this.limit = limit;
        }

        @Override
        public ServletOutputStream getOutputStream() {
            return new ServletOutputStream() {
                private int written;

                @Override
                public void write(int b) throws IOException {
                    if (++written > limit) {
                        throw new IOException("connection reset");
                    }
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                }
            };
        }
    }
}
