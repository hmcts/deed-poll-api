package uk.gov.hmcts.reform.deedpoll.config;

import jakarta.servlet.MultipartConfigElement;
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

    @Bean
    public MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofBytes(UploadPolicy.MAX_UPLOAD_BYTES));
        factory.setMaxRequestSize(DataSize.ofBytes(UploadPolicy.MAX_UPLOAD_BYTES + REQUEST_OVERHEAD_BYTES));
        return factory.createMultipartConfig();
    }
}
