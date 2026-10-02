package uk.gov.hmcts.reform.deedpoll.storage;

import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;
import uk.gov.hmcts.reform.ccd.document.am.model.Document;
import uk.gov.hmcts.reform.ccd.document.am.model.DocumentUploadRequest;
import uk.gov.hmcts.reform.ccd.document.am.model.UploadResponse;
import uk.gov.hmcts.reform.deedpoll.auth.IdamAuthentication;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdProperties;
import uk.gov.hmcts.reform.deedpoll.ccd.SystemUserTokens;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.UpstreamFailureError;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentOwner;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentToStore;
import uk.gov.hmcts.reform.deedpoll.domain.documents.StorageReceipt;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CdamDocumentStoreTest {

    static final UUID CDAM_ID = UUID.fromString("0b9fb4a8-b0c7-4c52-9f7f-5dc1a6b2d0e1");
    static final String HASH = "a-hash-token-that-must-stay-secret";
    private static final byte[] BYTES = "%PDF-1.7".getBytes(StandardCharsets.UTF_8);

    private final CaseDocumentClientApi cdam = mock(CaseDocumentClientApi.class);
    private final SystemUserTokens systemUser = mock(SystemUserTokens.class);
    private final AuthTokenGenerator s2s = mock(AuthTokenGenerator.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AuthTokenGenerator> serviceTokens = mock(ObjectProvider.class);
    private CdamDocumentStore store;

    @BeforeEach
    void setUp() {
        when(serviceTokens.getObject()).thenReturn(s2s);
        when(s2s.generate()).thenReturn("Bearer s2s");
        when(systemUser.accessToken()).thenReturn("Bearer system");
        store = new CdamDocumentStore(cdam, systemUser, serviceTokens,
            new CcdProperties("DeedPoll", "DEEDPOLL", "caseworker-deedpoll-system"));
    }

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    static UploadResponse answer(UUID id, String hashToken) {
        Document.Link self = new Document.Link();
        self.href = "http://dm-store:8080/documents/" + id;
        Document.Links links = new Document.Links();
        links.self = self;
        Document document = Document.builder().hashToken(hashToken).links(links).build();
        return new UploadResponse(List.of(document));
    }

    static FeignException answered(int status) {
        Request request = Request.create(Request.HttpMethod.POST, "http://cdam/cases/documents", Map.of(), null,
            StandardCharsets.UTF_8, null);
        return FeignException.errorStatus("uploadDocuments", feign.Response.builder()
            .status(status).reason("r").request(request).headers(Map.of())
            .body("{\"hashToken\": \"" + HASH + "\"}", StandardCharsets.UTF_8).build());
    }

    static RetryableException noResponse() {
        Request request = Request.create(Request.HttpMethod.POST, "http://cdam/cases/documents", Map.of(), null,
            StandardCharsets.UTF_8, null);
        return new RetryableException(-1, "Read timed out", Request.HttpMethod.POST, (Long) null, request);
    }

    private static DocumentToStore linked() {
        return new DocumentToStore(BYTES, "birth.pdf", "application/pdf",
            new DocumentOwner("record-1", Optional.of("1234567890123456")));
    }

    @Test
    @DisplayName("an upload is filed RESTRICTED under the case type and jurisdiction, as the system user")
    void uploads() {
        when(cdam.uploadDocuments(anyString(), anyString(), any())).thenReturn(answer(CDAM_ID, HASH));

        StorageReceipt receipt = store.save(linked());

        assertThat(receipt.storageKey()).isEqualTo(CDAM_ID.toString());
        assertThat(receipt.hashToken()).contains(HASH);
        ArgumentCaptor<DocumentUploadRequest> sent = ArgumentCaptor.forClass(DocumentUploadRequest.class);
        verify(cdam).uploadDocuments(eq("Bearer system"), eq("Bearer s2s"), sent.capture());
        assertThat(sent.getValue().getClassification()).isEqualTo("RESTRICTED");
        assertThat(sent.getValue().getCaseTypeId()).isEqualTo("DeedPoll");
        assertThat(sent.getValue().getJurisdictionId()).isEqualTo("DEEDPOLL");
        assertThat(sent.getValue().getFiles()).singleElement().satisfies(file -> {
            assertThat(file.getName()).isEqualTo(CdamDocumentStore.FILES);
            assertThat(file.getOriginalFilename()).isEqualTo("birth.pdf");
            assertThat(file.getContentType()).isEqualTo("application/pdf");
        });
    }

    @Test
    @DisplayName("a record with no CCD case is not ready, and nothing is sent -- not even for a token")
    void caseNotReadySendsNothing() {
        DocumentToStore unlinked = new DocumentToStore(BYTES, "birth.pdf", "application/pdf",
            new DocumentOwner("record-1", Optional.empty()));

        assertThatThrownBy(() -> store.save(unlinked))
            .isInstanceOf(ConflictError.class)
            .hasMessage(DocumentStorageRule.CASE_NOT_READY);
        verifyNoInteractions(cdam, systemUser, serviceTokens);
    }

    @Test
    @DisplayName("a 4xx upload is a refusal: nothing can have been stored")
    void refusedUpload() {
        when(cdam.uploadDocuments(anyString(), anyString(), any())).thenThrow(answered(400));

        assertThatThrownBy(() -> store.save(linked()))
            .isInstanceOfSatisfying(CdamUploadFailure.class, f -> assertThat(f.mayHaveStored()).isFalse())
            .hasMessage("Upload refused: CDAM answered HTTP 400")
            .hasNoCause();
    }

    @Test
    @DisplayName("a lost answer, a 5xx, or an unreadable answer may have stored the document")
    void uncertainUploads() {
        when(cdam.uploadDocuments(anyString(), anyString(), any()))
            .thenThrow(noResponse())
            .thenThrow(answered(502))
            .thenReturn(new UploadResponse(List.of()))
            .thenReturn(answer(CDAM_ID, " "));

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> store.save(linked()))
                .isInstanceOfSatisfying(CdamUploadFailure.class, f -> assertThat(f.mayHaveStored()).isTrue())
                .satisfies(f -> assertThat(f.getMessage()).doesNotContain(HASH).startsWith("Upload outcome unknown"))
                .hasNoCause();
        }
    }

    @Test
    @DisplayName("an href whose last 36 characters are not an id is an unreadable answer")
    void unreadableHref() {
        UploadResponse bad = answer(CDAM_ID, HASH);
        bad.getDocuments().get(0).links.self.href = "http://dm-store:8080/documents/not-an-id";

        assertThat(CdamDocumentStore.receipt(bad)).isEmpty();
        assertThat(CdamDocumentStore.receipt(null)).isEmpty();
    }

    @Test
    @DisplayName("a token failure sends nothing")
    void tokenFailureSendsNothing() {
        when(systemUser.accessToken()).thenThrow(new IllegalStateException("idam down"));

        assertThatThrownBy(() -> store.save(linked()))
            .isInstanceOfSatisfying(CdamUploadFailure.class, f -> assertThat(f.mayHaveStored()).isFalse());
        verifyNoInteractions(cdam);
    }

    @Test
    @DisplayName("a download goes under the caller's own token, never the system user's")
    void downloadsAsTheCaller() throws Exception {
        signIn("caller-token");
        ResponseEntity<Resource> body = ResponseEntity.ok(new ByteArrayResource(BYTES));
        when(cdam.getDocumentBinary("Bearer caller-token", "Bearer s2s", CDAM_ID)).thenReturn(body);

        assertThat(store.open(CDAM_ID.toString()).readAllBytes()).isEqualTo(BYTES);
        verifyNoInteractions(systemUser);
    }

    @Test
    @DisplayName("a download with no caller is refused rather than made as the system user")
    void downloadNeedsACaller() {
        assertThatThrownBy(() -> store.open(CDAM_ID.toString())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(cdam, systemUser);
    }

    @Test
    @DisplayName("CDAM's answers to a download become this service's own errors")
    void downloadFailures() {
        signIn("caller-token");
        when(cdam.getDocumentBinary(anyString(), anyString(), any()))
            .thenThrow(answered(404))
            .thenThrow(answered(403))
            .thenThrow(answered(500))
            .thenThrow(noResponse())
            .thenReturn(ResponseEntity.ok().build());

        assertThatThrownBy(() -> store.open(CDAM_ID.toString())).isInstanceOf(NotFoundError.class);
        assertThatThrownBy(() -> store.open(CDAM_ID.toString())).isInstanceOf(ForbiddenError.class);
        assertThatThrownBy(() -> store.open(CDAM_ID.toString())).isInstanceOf(UpstreamFailureError.class);
        assertThatThrownBy(() -> store.open(CDAM_ID.toString())).isInstanceOf(UpstreamFailureError.class);
        assertThatThrownBy(() -> store.open(CDAM_ID.toString())).isInstanceOf(UpstreamFailureError.class);
        assertThatThrownBy(() -> store.open("uploads/not-a-cdam-id")).isInstanceOf(NotFoundError.class);
        verifyNoInteractions(systemUser);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void serviceReadNeverUsesTheTriggeringStaffToken(boolean staffPresent) throws Exception {
        if (staffPresent) {
            signIn("unrelated-staff");
        }
        when(cdam.getDocumentBinary("Bearer system", "Bearer s2s", CDAM_ID))
            .thenReturn(ResponseEntity.ok(new ByteArrayResource(BYTES)));
        try (var stream = store.openForService(CDAM_ID.toString())) {
            assertThat(stream.readAllBytes()).isEqualTo(BYTES);
        }
        verify(systemUser).accessToken();
        verify(cdam).getDocumentBinary("Bearer system", "Bearer s2s", CDAM_ID);
    }

    @Test
    void serviceTokenFailureNeverReachesCdam() {
        when(systemUser.accessToken()).thenThrow(new IllegalStateException("secret"));
        assertThatThrownBy(() -> store.openForService(CDAM_ID.toString()))
            .isInstanceOf(UpstreamFailureError.class).hasMessageNotContaining("secret");
        verifyNoInteractions(cdam);
    }

    @Test
    @DisplayName("a delete is always a soft delete: permanent=true is never sent")
    void softDeleteOnly() {
        store.delete(CDAM_ID.toString());

        verify(cdam).deleteDocument("Bearer system", "Bearer s2s", CDAM_ID, false);
        verify(cdam, never()).deleteDocument(anyString(), anyString(), any(), eq(true));
    }

    @Test
    @DisplayName("a delete with no key is a no-op, and a failed one says so in fixed prose")
    void deleteEdges() {
        store.delete(null);
        store.delete(" ");
        verifyNoInteractions(cdam);

        doThrow(answered(500)).when(cdam).deleteDocument(anyString(), anyString(), any(), anyBoolean());
        assertThatThrownBy(() -> store.delete(CDAM_ID.toString()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("CDAM soft delete failed: CDAM answered HTTP 500")
            .hasNoCause();
        assertThatThrownBy(() -> store.delete("uploads/a-local-key")).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @EnumSource(DocumentStorageState.class)
    @DisplayName("CDAM serves a row once it is attached, and no other")
    void servesOnlyAttached(DocumentStorageState state) {
        assertThat(store.serves(state)).isEqualTo(state == DocumentStorageState.Attached);
    }

    private static void signIn(String token) {
        Jwt jwt = Jwt.withTokenValue(token).header("alg", "none").claim("sub", "caller").build();
        IdamAuthentication caller = mock(IdamAuthentication.class);
        when(caller.getCredentials()).thenReturn(jwt);
        SecurityContextHolder.getContext().setAuthentication(caller);
    }
}
