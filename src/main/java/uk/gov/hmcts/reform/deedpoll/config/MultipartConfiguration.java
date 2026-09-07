package uk.gov.hmcts.reform.deedpoll.config;

import jakarta.servlet.MultipartConfigElement;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadPolicy;

/**
 * The multipart limits for the one route that accepts a file, taken from the upload policy.
 *
 * <p>Spring Boot's own default is <strong>1 MB</strong> per file, which every legitimate scanned certificate
 * exceeds -- so without this the upload route would refuse almost everything a caseworker actually has, and
 * refuse it as a limit the service never chose. This is the equivalent of the reference's Multer options, whose
 * comment records the same trap from the other side: the prototype's interceptor took no options and so accepted
 * any file of any size straight into memory.
 *
 * <p><strong>Declared as a bean rather than set in {@code application.yaml}, and that is the decision worth
 * reading.</strong> {@link UploadPolicy#MAX_UPLOAD_BYTES} is also what the refusal message is derived from
 * ("The file must be smaller than 25MB"), so a property that an environment could override would let the limit
 * being enforced and the limit being quoted to the caller drift apart -- a caseworker told their 12 MB file must
 * be under 25 MB. Declaring the bean makes {@code spring.servlet.multipart.max-file-size} inert: the constant is
 * the only figure, in the service and in the message. {@code CaseworkerDocumentUploadLimitTest} asserts the
 * pairing so a later edit cannot quietly restore two of them.
 *
 * <p>The figure itself is still not researched -- see the constant -- but correcting it there now corrects the
 * enforcement, the message and the tests together.
 */
@Configuration
public class MultipartConfiguration {

    /**
     * How much of a request is not the file: the multipart boundaries, part headers, and the three form fields
     * that arrive alongside it.
     *
     * <p>The request ceiling has to be above the file ceiling or the file ceiling could never be reached -- a
     * file of exactly the permitted size would be refused for the overhead wrapping it, which is a limit that
     * reports the wrong number. 1 MB is far more than the parts need and small enough to keep the worst case
     * close to the file limit.
     */
    private static final long REQUEST_OVERHEAD_BYTES = 1024L * 1024;

    /** The request ceiling: the file ceiling plus room for everything wrapping it. */
    private static final long MAX_REQUEST_BYTES = UploadPolicy.MAX_UPLOAD_BYTES + REQUEST_OVERHEAD_BYTES;

    @Bean
    public MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofBytes(UploadPolicy.MAX_UPLOAD_BYTES));
        factory.setMaxRequestSize(DataSize.ofBytes(MAX_REQUEST_BYTES));
        return factory.createMultipartConfig();
    }

    /**
     * How much of an already-refused request body the connector will read and discard before giving up on the
     * connection -- <strong>which is what decides whether the caller ever sees the 400 above</strong>.
     *
     * <p>Raising the file ceiling to 25 MB without raising this one silently undoes the refusal message. Tomcat
     * stops parsing the moment the file exceeds the ceiling, so 20-odd megabytes of the body are still unread; it
     * then drains them so the connection can be reused, and if the remainder exceeds {@code maxSwallowSize} --
     * <strong>2 MB by default</strong> -- it raises an {@code IOException} instead. That puts the processor in
     * {@code CLOSE_CONNECTION_NOW}, and the swallow happens in {@code endRequest()} <em>before</em> the response
     * is committed, so the 400 is never flushed. The caller gets a connection reset: no status, no body, and none
     * of the wording {@link UploadPolicy} exists to produce. The very case the limit was raised for -- an
     * over-size scan -- is the case that loses its explanation.
     *
     * <p>Set to the request ceiling rather than to {@code -1}. Unlimited would drain a body of any size at all,
     * which turns a refused upload into an unbounded read; the ceiling covers a genuine mistake -- roughly, a
     * file up to twice the limit, since about the limit itself has already been read by the time parsing stops --
     * and still drops the connection on something absurd. Derived from the same constant as the other two figures
     * for the reason in this class's note: three limits that can drift apart are three limits that will.
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> refusedUploadSwallowCustomizer() {
        return factory -> factory.addConnectorCustomizers(connector -> {
            // Set on the protocol handler, not the connector: maxSwallowSize is an HTTP/1.1 setting and
            // Connector has no such property, which is where Spring Boot's own max-swallow-size customizer
            // puts it too. Anything but HTTP/1.1 has no body to swallow and is left alone.
            if (connector.getProtocolHandler() instanceof AbstractHttp11Protocol<?> http11) {
                http11.setMaxSwallowSize(Math.toIntExact(MAX_REQUEST_BYTES));
            }
        });
    }
}
