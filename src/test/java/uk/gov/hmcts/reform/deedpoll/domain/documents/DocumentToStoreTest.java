package uk.gov.hmcts.reform.deedpoll.domain.documents;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers {@link DocumentToStore}, what {@link DocumentStore#save} is asked to store.
 */
class DocumentToStoreTest {

    private static final String SECRET = "the applicant's birth certificate, in full";

    private static final DocumentOwner OWNER =
        new DocumentOwner("6f1c5f2e-0000-4000-8000-000000000001", Optional.of("1234567890123456"));

    private static DocumentToStore document(String content) {
        return new DocumentToStore(content.getBytes(StandardCharsets.UTF_8), "birth.pdf", "application/pdf", OWNER);
    }

    @Test
    @DisplayName("prints the length, never the content")
    void printsTheLengthNeverTheContent() {
        String printed = document(SECRET).toString();

        assertThat(printed)
            .doesNotContain(SECRET)
            .doesNotContain("[B@")
            .contains("filename=birth.pdf", "mimeType=application/pdf", "size=" + SECRET.length(), OWNER.toString());
    }

    @Test
    @DisplayName("is equal when the bytes are, not when the array is the same object")
    void isEqualWhenTheBytesAre() {
        assertThat(document(SECRET)).isEqualTo(document(SECRET)).hasSameHashCodeAs(document(SECRET));
        assertThat(document(SECRET)).isNotEqualTo(document("another document"));
    }

    @Test
    @DisplayName("refuses missing content or a missing owner")
    void refusesMissingContentOrOwner() {
        assertThatThrownBy(() -> new DocumentToStore(null, "birth.pdf", "application/pdf", OWNER))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DocumentToStore(new byte[0], "birth.pdf", "application/pdf", null))
            .isInstanceOf(NullPointerException.class);
    }
}
