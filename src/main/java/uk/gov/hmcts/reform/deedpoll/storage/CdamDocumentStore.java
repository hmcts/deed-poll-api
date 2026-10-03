package uk.gov.hmcts.reform.deedpoll.storage;

import feign.FeignException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;
import uk.gov.hmcts.reform.ccd.document.am.model.Classification;
import uk.gov.hmcts.reform.ccd.document.am.model.Document;
import uk.gov.hmcts.reform.ccd.document.am.model.DocumentUploadRequest;
import uk.gov.hmcts.reform.ccd.document.am.model.UploadResponse;
import uk.gov.hmcts.reform.ccd.document.am.util.InMemoryMultipartFile;
import uk.gov.hmcts.reform.deedpoll.auth.IdamAuthentication;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingRule;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingService;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdProperties;
import uk.gov.hmcts.reform.deedpoll.ccd.SystemUserTokens;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.UpstreamFailureError;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentStore;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentToStore;
import uk.gov.hmcts.reform.deedpoll.domain.documents.StorageReceipt;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Supporting documents in CDAM (plan 03a, task 4), selected by {@code deedpoll.documents.store: cdam}.
 *
 * <p><strong>Each call is one conversation with CDAM, and none of them is made inside a transaction</strong> --
 * {@link CdamDocumentUploads} and {@code DocumentService} see to that. What an upload's outcome means for its row is
 * decided there; this class says only what happened, as a {@link StorageReceipt} or a {@link CdamUploadFailure}.
 *
 * <ul>
 *   <li><strong>Upload</strong> as the system user with the api's S2S token, filed under the record's CCD case type
 *       and jurisdiction, {@code RESTRICTED}. The case reference is checked again here, before anything is sent: a
 *       record with no CCD case is "case not ready" (decision 1). The receipt's key is the CDAM document id -- the last
 *       36 characters of {@code _links.self.href}, as CDAM itself reads it -- and it carries the hash token.</li>
 *   <li><strong>Download</strong> under the caller's own token, so CDAM's permission check, which asks CCD whether
 *       this user can read the case, applies to the caller and not to the system user. There is no fallback to the
 *       system user: a download with no caller is refused. The client decodes the body into memory before handing it
 *       on, so a download is buffered rather than streamed; 25 MB is {@code UploadPolicy}'s ceiling.</li>
 *   <li><strong>Delete</strong> is a soft delete, {@code permanent=false}, always (decision 5). Permanent removal
 *       belongs to the retention plan, and no path here can send {@code true}.</li>
 * </ul>
 *
 * <p><strong>Nothing CDAM says is repeated.</strong> Failures are reported in fixed prose -- the status, or the kind of
 * failure -- never a message or a cause, because a Feign exception carries the response body and an upload's body
 * carries the hash token (decision 8).
 */
@Component
@ConditionalOnProperty(name = DocumentStoreProperties.STORE, havingValue = DocumentStoreProperties.CDAM)
public class CdamDocumentStore implements DocumentStore {

    /** The multipart field CDAM reads files from. */
    static final String FILES = "files";

    /** The length of a UUID written out, which is what CDAM takes from the end of a document href. */
    private static final int ID_LENGTH = 36;

    private static final String CDAM = "CDAM";

    private static final String NO_FILE = "The document store did not return this document's file. Try again.";

    private final CaseDocumentClientApi cdam;
    private final SystemUserTokens systemUser;
    private final ObjectProvider<AuthTokenGenerator> serviceTokens;
    private final CcdProperties ccd;

    public CdamDocumentStore(CaseDocumentClientApi cdam,
                             SystemUserTokens systemUser,
                             ObjectProvider<AuthTokenGenerator> serviceTokens,
                             CcdProperties ccd) {
        this.cdam = cdam;
        this.systemUser = systemUser;
        this.serviceTokens = serviceTokens;
        this.ccd = ccd;
    }

    /**
     * Uploads one document.
     *
     * @throws uk.gov.hmcts.reform.deedpoll.domain.ConflictError if the record has no CCD case; nothing is sent
     * @throws CdamUploadFailure if the upload did not end in a readable receipt, saying whether it may have landed
     */
    @Override
    public StorageReceipt save(DocumentToStore document) {
        DocumentStorageRule.requireCaseReady(document.owner().ccdCaseReference());
        String user;
        String service;
        try {
            user = systemUser.accessToken();
            service = serviceTokens.getObject().generate();
        } catch (RuntimeException failure) {
            throw CdamUploadFailure.nothingStored("Not sent: " + CcdLinkingService.describe("IDAM or S2S", failure));
        }
        UploadResponse response;
        try {
            response = cdam.uploadDocuments(user, service, new DocumentUploadRequest(
                Classification.RESTRICTED.name(),
                ccd.caseTypeId(),
                ccd.jurisdictionId(),
                List.of(new InMemoryMultipartFile(FILES, document.filename(), document.mimeType(),
                    document.content()))));
        } catch (RuntimeException failure) {
            String described = CcdLinkingService.describe(CDAM, failure);
            throw CcdLinkingRule.classify(failure) == CcdLinkingRule.SubmitFailure.REFUSED
                ? CdamUploadFailure.nothingStored("Upload refused: " + described)
                : CdamUploadFailure.uncertain("Upload outcome unknown: " + described);
        }
        return receipt(response)
            .orElseThrow(() -> CdamUploadFailure.uncertain("Upload outcome unknown: CDAM's answer could not be read"));
    }

    /**
     * The receipt an upload's answer holds, if it holds exactly one document with an id and a token.
     *
     * <p>Anything else is an answer this service cannot read, and an unreadable answer is not a refusal: CDAM may
     * well have stored the document, so the caller records the upload as uncertain.
     */
    static Optional<StorageReceipt> receipt(UploadResponse response) {
        if (response == null || response.getDocuments() == null || response.getDocuments().size() != 1) {
            return Optional.empty();
        }
        Document document = response.getDocuments().get(0);
        if (document == null || document.hashToken == null || document.hashToken.isBlank()
            || document.links == null || document.links.self == null || document.links.self.href == null) {
            return Optional.empty();
        }
        return documentId(document.links.self.href)
            .map(id -> new StorageReceipt(id.toString(), Optional.of(document.hashToken)));
    }

    private static Optional<UUID> documentId(String href) {
        if (href.length() < ID_LENGTH) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(href.substring(href.length() - ID_LENGTH)));
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }

    /**
     * The document's bytes, fetched under the caller's own token.
     *
     * @throws NotFoundError if CDAM has no such document, or it was soft-deleted
     * @throws ForbiddenError if CDAM refuses the caller
     * @throws UpstreamFailureError if CDAM did not answer with the bytes
     * @throws IllegalStateException if there is no caller to fetch them as
     */
    @Override
    public InputStream open(String storageKey) {
        return openWithToken(storageKey, callerToken());
    }

    /** Internal correspondence authority, never a retry/fallback from a staff refusal. */
    InputStream openForService(String storageKey) {
        String token;
        try {
            token = systemUser.accessToken();
        } catch (RuntimeException failure) {
            throw new UpstreamFailureError(NO_FILE);
        }
        return openWithToken(storageKey, token);
    }

    private InputStream openWithToken(String storageKey, String caller) {
        UUID id = documentId(storageKey == null ? "" : storageKey)
            .orElseThrow(() -> new NotFoundError("This document's file could not be found"));
        try {
            String service = serviceTokens.getObject().generate();
            ResponseEntity<Resource> response = cdam.getDocumentBinary(caller, service, id);
            if (response == null || response.getBody() == null) {
                throw new UpstreamFailureError(NO_FILE);
            }
            return response.getBody().getInputStream();
        } catch (FeignException failure) {
            throw switch (failure.status()) {
                case 404 -> new NotFoundError("This document's file could not be found");
                case 403 -> new ForbiddenError("You do not have permission to open this document's file");
                default -> new UpstreamFailureError(NO_FILE);
            };
        } catch (IOException unreadable) {
            throw new UpstreamFailureError(NO_FILE);
        } catch (UpstreamFailureError failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // The S2S generator, or a client fault short of an HTTP answer.
            throw new UpstreamFailureError(NO_FILE);
        }
    }

    /**
     * Soft-deletes a document in CDAM, {@code permanent=false}.
     *
     * <p>A no-op for a null or blank key, as the seam requires. A key that is not a CDAM id cannot be deleted here, and
     * says so, so that {@code DocumentService} logs both ids.
     *
     * @throws IllegalStateException if the key is not a CDAM id, or CDAM did not take the delete; fixed prose
     */
    @Override
    public void delete(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return;
        }
        UUID id = documentId(storageKey)
            .orElseThrow(() -> new IllegalStateException("The key is not a CDAM document id"));
        try {
            cdam.deleteDocument(systemUser.accessToken(), serviceTokens.getObject().generate(), id, false);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("CDAM soft delete failed: " + CcdLinkingService.describe(CDAM, failure));
        }
    }

    /** CDAM holds a document once it is attached to the case, and not before. */
    @Override
    public boolean serves(DocumentStorageState state) {
        return state == DocumentStorageState.Attached;
    }

    private static String callerToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof IdamAuthentication && authentication.getCredentials() instanceof Jwt jwt) {
            return "Bearer " + jwt.getTokenValue();
        }
        throw new IllegalStateException("A CDAM download needs the caller's own access token, and there is none");
    }
}
