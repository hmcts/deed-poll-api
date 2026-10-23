package uk.gov.hmcts.reform.deedpoll.domain.documents;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A preview read opens its original once, so it can never record a second {@code DOCUMENT_PREVIEWED} row.
 */
class DocumentPreviewReadTest {

    @Test
    @DisplayName("a second opening is a programming error, and neither records nor opens anything again")
    void opensOnce() {
        AtomicInteger openings = new AtomicInteger();
        DocumentPreviewRead read = new DocumentPreviewRead(new DeedPollSupportingDocument(), () -> {
            openings.incrementAndGet();
            return (InputStream) new ByteArrayInputStream(new byte[0]);
        });

        read.open();

        assertThatThrownBy(read::open).isInstanceOf(IllegalStateException.class);
        assertThat(openings).hasValue(1);
    }
}
