package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import uk.gov.hmcts.reform.deedpoll.api.DocumentFileResponses;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadPolicy;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentDownloadPolicyTest {

    @Test
    @DisplayName("every inline MIME type is an allowed upload type")
    void inlineTypesAreAllowedUploads() {
        // Inspect the actual allowlist without exposing a production API solely for this invariant.
        Object value = ReflectionTestUtils.getField(DocumentFileResponses.class, "INLINE_MIME_TYPES");
        assertThat(value).isInstanceOf(Set.class);
        List<String> inlineTypes = ((Set<?>) value).stream().map(String.class::cast).toList();
        assertThat(inlineTypes).as("inline MIME types").isSubsetOf(UploadPolicy.ALLOWED_UPLOAD_MIME_TYPES);
    }
}
