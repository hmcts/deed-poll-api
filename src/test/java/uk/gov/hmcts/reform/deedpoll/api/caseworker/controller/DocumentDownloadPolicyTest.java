package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadPolicy;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentDownloadPolicyTest {

    @Test
    void inlineTypesAreAllowedUploadsButNeverWordOrTiff() {
        // Inspect the actual allowlist without exposing a production API solely for this invariant.
        Object value = ReflectionTestUtils.getField(CaseworkerDocumentController.class, "INLINE_MIME_TYPES");
        assertThat(value).isInstanceOf(Set.class);
        Set<?> inlineTypes = (Set<?>) value;
        assertThat(UploadPolicy.ALLOWED_UPLOAD_MIME_TYPES.containsAll(inlineTypes)).isTrue();
        assertThat(inlineTypes.contains("application/msword")).isFalse();
        assertThat(inlineTypes.contains("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            .isFalse();
        assertThat(inlineTypes.contains("image/tiff")).isFalse();
    }
}
