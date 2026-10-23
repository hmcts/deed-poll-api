package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link PreviewProperties} and holds the pod's one {@link ConversionCapacity}.
 *
 * <p>Nothing here needs the converter to be configured: an unset endpoint or key starts the service as normal.
 */
@Configuration
@EnableConfigurationProperties(PreviewProperties.class)
public class PreviewConfiguration {

    @Bean
    public ConversionCapacity conversionCapacity(PreviewProperties properties) {
        return new ConversionCapacity(properties.maxConcurrentConversions(), properties.busyRetryAfter());
    }
}
