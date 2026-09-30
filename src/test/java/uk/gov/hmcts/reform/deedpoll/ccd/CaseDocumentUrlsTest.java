package uk.gov.hmcts.reform.deedpoll.ccd;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CaseDocumentUrlsTest {

    private static final UUID ID = UUID.fromString("0b9fb4a8-b0c7-4c52-9f7f-5dc1a6b2d0e1");

    @Test
    @DisplayName("a URL written here reads back as its document id")
    void roundTrip() {
        assertThat(CaseDocumentUrls.url("http://dm-store:8080", ID)).isEqualTo("http://dm-store:8080/documents/" + ID);
        assertThat(CaseDocumentUrls.cdamDocumentIdOf(CaseDocumentUrls.url("http://dm-store:8080", ID))).contains(ID);
    }

    @Test
    @DisplayName("a binary URL, a URL with no documents segment, or no UUID after it names nothing")
    void namesNothingElse() {
        assertThat(CaseDocumentUrls.cdamDocumentIdOf(CaseDocumentUrls.binaryUrl("http://dm-store:8080", ID)))
            .isEmpty();
        assertThat(CaseDocumentUrls.cdamDocumentIdOf("http://dm-store:8080/files/" + ID)).isEmpty();
        assertThat(CaseDocumentUrls.cdamDocumentIdOf("http://dm-store:8080/documents/" + "x".repeat(36))).isEmpty();
        assertThat(CaseDocumentUrls.cdamDocumentIdOf("http://dm-store:8080/documents/")).isEmpty();
        assertThat(CaseDocumentUrls.cdamDocumentIdOf(null)).isEmpty();
    }
}
