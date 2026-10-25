package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Binds {@link PreviewProperties} and holds the pod's one {@link ConversionCapacity} and its renderer.
 *
 * <p>Nothing here needs the converter to be configured: an unset endpoint or key starts the service as normal.
 */
@Configuration
@EnableConfigurationProperties(PreviewProperties.class)
public class PreviewConfiguration {

    @Bean
    public ConversionCapacity conversionCapacity(PreviewProperties properties) {
        return new ConversionCapacity(properties.maxConcurrentConversions(), properties.maxConcurrentPreviews(),
                                      properties.busyRetryAfter());
    }

    @Bean
    public PreviewRenderer previewRenderer(PreviewProperties properties, ConversionCapacity conversionCapacity) {
        return new PreviewRenderer(properties, conversionCapacity, Path.of(System.getProperty("java.io.tmpdir")));
    }
}
