package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import jakarta.servlet.MultipartConfigElement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartResolver;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The upload size limit: the figure that is enforced, and the answer a caller gets when they exceed it.
 *
 * <p><strong>Its own class because the limit is enforced somewhere no ordinary controller test reaches.</strong>
 * A too-large file is refused while the request is being resolved as multipart, which happens before a handler
 * method is selected -- so the refusal is not thrown by the upload route and cannot be caught next to it. The
 * two halves are asserted separately, because they fail separately:
 *
 * <ol>
 *   <li>the servlet's own ceiling really is the policy's figure, which is what stops Spring Boot's 1 MB default
 *       from refusing every scanned certificate a caseworker has;</li>
 *   <li>a refusal raised at resolution time still reaches {@code DomainErrorHandler} and comes back as the
 *       policy's own 400, attributed to the file input -- not as the 500 an unrecognised transport failure
 *       becomes, which the reference records three defects falling into.</li>
 * </ol>
 *
 * <p><strong>The resolver is mocked rather than fed a 26 MB body, deliberately.</strong> A real over-size upload
 * is answered and then has its connection dropped, because the server stops reading a body it has already
 * refused -- so the test would be racing the socket for the response it means to assert, and would send 26 MB to
 * do it. Mocking the resolver reproduces the one thing that matters here, an exception raised where no handler
 * has been chosen. The figure it would have been compared against is what test (1) checks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseworkerDocumentUploadLimitTest extends AbstractPostgresContainerTest {

    private static final StubIdam IDAM = new StubIdam();

    static {
        IDAM.start();
    }

    @DynamicPropertySource
    static void idamProperties(DynamicPropertyRegistry registry) {
        registry.add("idam.api.issuer-url", IDAM::issuerUrl);
        registry.add("idam.api.audiences", () -> StubIdam.AUDIENCE);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MultipartConfigElement multipartConfig;

    @MockitoBean
    private MultipartResolver multipartResolver;

    @BeforeEach
    void resetIdam() {
        IDAM.stubDefaults();
    }

    @Test
    @DisplayName("the servlet's file ceiling is the upload policy's figure, not Spring Boot's 1MB default")
    void theFileCeilingIsThePolicysFigure() {
        // Read from the policy rather than transcribed: the figure is flagged as unresearched, so correcting it
        // there has to correct the enforcement, the message and this test together.
        assertThat(multipartConfig.getMaxFileSize()).isEqualTo(UploadPolicy.MAX_UPLOAD_BYTES);
    }

    @Test
    @DisplayName("the request ceiling is above the file ceiling, so a file at the limit is not refused for its wrapper")
    void theRequestCeilingIsAboveTheFileCeiling() {
        // Otherwise the file limit could never be reached: the boundaries, part headers and form fields all
        // count towards the request size, so a file of exactly the permitted size would be refused -- a limit
        // that reports the wrong number.
        assertThat(multipartConfig.getMaxRequestSize()).isGreaterThan(UploadPolicy.MAX_UPLOAD_BYTES);
    }

    @Test
    @DisplayName("a file over the limit is the policy's 400, attributed to the file input, and not a 500")
    void anOverSizeFileIsThePolicysBadRequest() throws Exception {
        given(multipartResolver.isMultipart(any())).willReturn(true);
        willThrow(new MaxUploadSizeExceededException(UploadPolicy.MAX_UPLOAD_BYTES))
            .given(multipartResolver).resolveMultipart(any());

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken());

        mockMvc.perform(post("/caseworker/cases/any-case/documents")
                .headers(headers)
                .contentType(MediaType.MULTIPART_FORM_DATA))
            .andExpect(status().isBadRequest())
            // The policy's wording, read from the policy: what a caller sees here and what the sibling web
            // app's own form says have to be the same sentence.
            .andExpect(jsonPath("$.error")
                .value(UploadPolicy.uploadLimitRejection("LIMIT_FILE_SIZE").getMessage()))
            .andExpect(jsonPath("$.errors.length()").value(1))
            .andExpect(jsonPath("$.errors[0].field").value(UploadPolicy.UPLOAD_FILE_FIELD))
            .andExpect(jsonPath("$.errors[0].message")
                .value(UploadPolicy.uploadLimitRejection("LIMIT_FILE_SIZE").errors().get(0).message()));
    }
}
