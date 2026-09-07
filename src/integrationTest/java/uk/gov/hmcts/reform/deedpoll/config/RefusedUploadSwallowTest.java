package uk.gov.hmcts.reform.deedpoll.config;

import jakarta.servlet.MultipartConfigElement;
import org.apache.coyote.ProtocolHandler;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether the connector will drain a body it has already refused -- which is what decides whether an over-size
 * upload gets the 400 {@code UploadPolicy} words, or a connection reset with nothing in it.
 *
 * <p><strong>Why this needs a real server and so lives here.</strong> The two ceilings are bean state and
 * {@code CaseworkerDocumentUploadLimitTest} asserts them under MockMvc, but this one is connector state, and it is
 * set by a {@code WebServerFactoryCustomizer} that only runs when a servlet container is actually built. Asserting
 * it against an isolated factory would also miss the thing most likely to go wrong: Spring Boot contributes its
 * own {@code server.tomcat.max-swallow-size} customizer, so what matters is the value that survives both -- the
 * live connector, after every customizer has had its turn.
 *
 * <p>The failure it guards is silent in exactly the way that makes it worth a test. Tomcat stops parsing the
 * moment the file exceeds the ceiling, leaving 20-odd megabytes unread; the drain happens in {@code endRequest()}
 * <em>before</em> the response is committed, so a remainder over {@code maxSwallowSize} -- 2 MB by default --
 * aborts the connection and the 400 is never flushed. Every unit test still passes, because the refusal is built
 * correctly and simply never reaches anybody.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RefusedUploadSwallowTest extends AbstractPostgresContainerTest {

    @Autowired
    private ServletWebServerApplicationContext context;

    @Autowired
    private MultipartConfigElement multipartConfig;

    @Test
    @DisplayName("the live connector drains a refused body up to the request ceiling, so the 400 is flushed")
    void theConnectorDrainsARefusedBodyUpToTheRequestCeiling() {
        ProtocolHandler handler = ((TomcatWebServer) context.getWebServer()).getTomcat()
            .getConnector()
            .getProtocolHandler();

        assertThat(handler).isInstanceOf(AbstractHttp11Protocol.class);
        // At least the request ceiling, compared against the multipart config rather than a figure transcribed
        // here: the two are derived from the same constant, and a limit raised without this following it is the
        // regression. Boot's default is 2 MB, which is an order of magnitude below the file ceiling.
        assertThat(((AbstractHttp11Protocol<?>) handler).getMaxSwallowSize())
            .isGreaterThanOrEqualTo(Math.toIntExact(multipartConfig.getMaxRequestSize()));
    }
}
