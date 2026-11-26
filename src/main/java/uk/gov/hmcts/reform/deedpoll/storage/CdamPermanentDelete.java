package uk.gov.hmcts.reform.deedpoll.storage;

import feign.Feign;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import feign.Retryer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;
import uk.gov.hmcts.reform.deedpoll.ccd.SystemUserTokens;
import uk.gov.hmcts.reform.deedpoll.ccd.TokenGrants;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionFailure;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionProperties;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionRemote.StepOutcome;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * CDAM's permanent delete, {@code DELETE /cases/documents/{id}?permanent=true}, for permanent document deletion
 * alone (plan 20261125-permanent-document-deletion, Task 4; docs/DOCUMENT-DELETION.md, "CDAM's answers to a
 * permanent delete", pinned by {@code CdamPermanentDeleteContractTest}).
 *
 * <p><strong>The only sender of {@code permanent=true}</strong>, by Steve's decision of 2026-11-25, and only for a
 * request already recorded. {@link CdamDocumentStore#delete} stays a soft delete for every other path, and nothing
 * here is ever preceded by one: a permanent delete after a soft delete never reaches the blob.
 *
 * <p><strong>Its own client.</strong> CDAM's pinned {@link CaseDocumentClientApi}, built here with the deletion's own
 * connect and read timeouts ({@code deedpoll.documents.deletion.cdam-*-timeout}), {@link NoConnectionRetry}'s OkHttp
 * client and Feign's {@link Retryer#NEVER_RETRY}, so that one call is one send and the upload and download client's
 * timeouts do not change. Only {@code deleteDocument} is ever called on it.
 *
 * <p>Answers a {@link StepOutcome}, never throws for what CDAM said, and never repeats CDAM's words:
 * <ul>
 *   <li>{@code 204} is the receipt, {@code DELETED}; any other {@code 2xx} is unknown and retried
 *       ({@link #receiptOnly});</li>
 *   <li>{@code 404} proves nothing (already purged, soft-deleted elsewhere, or never there) and needs a person,
 *       {@link DocumentDeletionFailure#CDAM_PURGE_UNPROVEN};</li>
 *   <li>{@code 403} is CDAM's service permission, {@link DocumentDeletionFailure#CDAM_PERMISSION};</li>
 *   <li>{@code 401} and the tokens failing are retried; any other {@code 4xx} but 408 and 429 is
 *       {@link DocumentDeletionFailure#CDAM_DELETE_FAILED};</li>
 *   <li>a {@code 5xx}, 408, 429, a timeout or a reset is unknown and retried: re-sending is safe.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = DocumentStoreProperties.STORE_PROPERTY, havingValue = DocumentStoreProperties.CDAM)
public class CdamPermanentDelete {

    private static final int NO_CONTENT = 204;
    private static final int NOT_FOUND = 404;
    private static final int FORBIDDEN = 403;
    private static final int UNAUTHORISED = 401;
    private static final int REQUEST_TIMEOUT = 408;
    private static final int TOO_MANY_REQUESTS = 429;

    private final CaseDocumentClientApi client;
    private final SystemUserTokens systemUser;
    private final ObjectProvider<AuthTokenGenerator> serviceTokens;

    @Autowired
    public CdamPermanentDelete(@Value("${case_document_am.url}") String cdamUrl, DocumentDeletionProperties properties,
                               SystemUserTokens systemUser, ObjectProvider<AuthTokenGenerator> serviceTokens) {
        this(client(cdamUrl, properties.cdamConnectTimeout(), properties.cdamReadTimeout()), systemUser,
             serviceTokens);
    }

    CdamPermanentDelete(CaseDocumentClientApi client, SystemUserTokens systemUser,
                        ObjectProvider<AuthTokenGenerator> serviceTokens) {
        this.client = client;
        this.systemUser = systemUser;
        this.serviceTokens = serviceTokens;
    }

    /** CDAM's own client interface, with the deletion's timeouts and no resend at any layer. */
    static CaseDocumentClientApi client(String cdamUrl, Duration connect, Duration read) {
        return Feign.builder()
            .contract(new SpringMvcContract())
            .client(receiptOnly(new NoConnectionRetry().enrich((feign.Client) new feign.okhttp.OkHttpClient())))
            .options(new Request.Options(connect.toMillis(), TimeUnit.MILLISECONDS, read.toMillis(),
                                         TimeUnit.MILLISECONDS, false))
            .retryer(Retryer.NEVER_RETRY)
            .target(CaseDocumentClientApi.class, cdamUrl + "/cases/documents");
    }

    /**
     * {@code delegate}, except that a {@code 2xx} other than {@code 204} is not a receipt. The client interface's
     * {@code deleteDocument} is {@code void}, so Feign would answer any {@code 2xx} alike; this turns the rest into
     * {@link NotAReceipt}, which Feign raises as a {@link RetryableException}: unknown, and tried again, whose second
     * answer (a {@code 404}) then needs a person rather than reading as a purge.
     */
    static feign.Client receiptOnly(feign.Client delegate) {
        return (request, options) -> {
            Response response = delegate.execute(request, options);
            int status = response.status();
            if (status >= 200 && status < 300 && status != NO_CONTENT) {
                response.close();
                throw new NotAReceipt();
            }
            return response;
        };
    }

    /**
     * CDAM answered a {@code 2xx} that is not {@code 204}: the delete may or may not have happened.
     */
    static final class NotAReceipt extends IOException {
        private static final long serialVersionUID = 1L;

        NotAReceipt() {
            super("CDAM's answer to a permanent delete was not 204");
        }
    }

    /** One permanent delete, as the system user with the api's S2S token. */
    public StepOutcome delete(UUID cdamDocumentId) {
        String user;
        String service;
        try {
            user = systemUser.accessToken();
            service = serviceTokens.getObject().generate();
        } catch (RuntimeException failure) {
            return StepOutcome.retry("TOKEN_UNAVAILABLE");
        }
        return send(cdamDocumentId, user, service);
    }

    /**
     * {@link #delete(UUID)} within a permanent document deletion's budget: each token grant is waited for only while
     * {@code timeLeft} is not negative ({@link TokenGrants}), because a cold IDAM or S2S grant can spend the staff
     * request's budget, and a delete sent then could outlast the request.
     *
     * @param timeLeft how long is left before the delete must have been sent; negative means it must not
     * @return {@code NOT_SENT} {@code OUT_OF_TIME} when time ran out, so nothing was sent
     */
    public StepOutcome delete(UUID cdamDocumentId, Supplier<Duration> timeLeft) {
        Optional<TokenGrants.Tokens> tokens;
        try {
            tokens = TokenGrants.within(timeLeft, systemUser::accessToken,
                                        () -> serviceTokens.getObject().generate());
        } catch (RuntimeException failure) {
            return StepOutcome.retry("TOKEN_UNAVAILABLE");
        }
        return tokens.map(held -> send(cdamDocumentId, held.user(), held.service()))
            .orElseGet(() -> StepOutcome.notSent("OUT_OF_TIME"));
    }

    private StepOutcome send(UUID cdamDocumentId, String user, String service) {
        try {
            client.deleteDocument(user, service, cdamDocumentId, true);
            return StepOutcome.done("DELETED");
        } catch (RetryableException unknown) {
            return StepOutcome.retry(unknown.getCause() instanceof NotAReceipt ? "NOT_A_RECEIPT" : "UNKNOWN");
        } catch (FeignException answered) {
            return classify(answered.status());
        } catch (RuntimeException unknown) {
            return StepOutcome.retry("UNKNOWN");
        }
    }

    static StepOutcome classify(int status) {
        if (status == NOT_FOUND) {
            return StepOutcome.failed("NOT_FOUND", DocumentDeletionFailure.CDAM_PURGE_UNPROVEN);
        }
        if (status == FORBIDDEN) {
            return StepOutcome.failed("FORBIDDEN", DocumentDeletionFailure.CDAM_PERMISSION);
        }
        if (status == UNAUTHORISED) {
            return StepOutcome.retry("UNAUTHORISED");
        }
        if (status >= 400 && status < 500 && status != REQUEST_TIMEOUT && status != TOO_MANY_REQUESTS) {
            return StepOutcome.failed("REFUSED", DocumentDeletionFailure.CDAM_DELETE_FAILED);
        }
        return StepOutcome.retry("UNKNOWN");
    }
}
