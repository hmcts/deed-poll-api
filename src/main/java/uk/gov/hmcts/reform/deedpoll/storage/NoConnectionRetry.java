package uk.gov.hmcts.reform.deedpoll.storage;

import feign.Capability;
import feign.Client;
import feign.okhttp.OkHttpClient;

/**
 * The CDAM client's HTTP client with OkHttp's own retry on a connection failure turned off.
 *
 * <p>The client library's {@code FeignSupportConfig} supplies a default OkHttp client, and OkHttp silently re-sends
 * a request whose connection failed -- an upload included. A reset upload was then sent twice for each one
 * {@link CdamDocumentUploads} decided to send, so "exactly two uploads" was four, and a document CDAM had stored
 * could be stored again with nobody knowing. Every send is this service's decision, counted in
 * {@code storageUploadAttempts}; nothing underneath it may add one.
 *
 * <p>Applied to that one Feign client by name, through
 * {@code spring.cloud.openfeign.client.config.case-document-am-client-api.capabilities} in {@code application.yaml}.
 * It refuses any client that is not the OkHttp one it replaces, so a client library that changes its transport fails
 * startup rather than quietly getting a retrying client back. Per-request timeouts still apply: Feign's OkHttp
 * client derives a client with the request's options from this one.
 */
public class NoConnectionRetry implements Capability {

    @Override
    public Client enrich(Client client) {
        if (!(client instanceof OkHttpClient)) {
            throw new IllegalStateException("The CDAM client is expected to use OkHttp, and uses "
                + client.getClass().getName());
        }
        return new OkHttpClient(new okhttp3.OkHttpClient.Builder().retryOnConnectionFailure(false).build());
    }
}
