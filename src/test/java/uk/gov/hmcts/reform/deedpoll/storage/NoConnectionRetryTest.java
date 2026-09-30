package uk.gov.hmcts.reform.deedpoll.storage;

import feign.Client;
import feign.okhttp.OkHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Container-free: the replacement's shape. That it really stops a re-sent upload is
 * {@code CdamDocumentScenariosTest.aResetUploadIsNotRepeatedByTheClient}, against WireMock.
 */
class NoConnectionRetryTest {

    @Test
    @DisplayName("the library's OkHttp client is replaced by another OkHttp client")
    void replacesTheOkHttpClient() {
        OkHttpClient library = new OkHttpClient();

        Client replaced = new NoConnectionRetry().enrich((Client) library);

        assertThat(replaced).isInstanceOf(OkHttpClient.class).isNotSameAs(library);
    }

    @Test
    @DisplayName("any other transport fails startup rather than passing through")
    void refusesAnotherTransport() {
        assertThatThrownBy(() -> new NoConnectionRetry().enrich((Client) new Client.Default(null, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("OkHttp");
    }
}
