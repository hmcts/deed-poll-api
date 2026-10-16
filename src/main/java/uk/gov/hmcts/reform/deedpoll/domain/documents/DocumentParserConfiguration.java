package uk.gov.hmcts.reform.deedpoll.domain.documents;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Apply process-wide POI bounds before singleton readers or request handling can use the parser. */
@Configuration(proxyBeanMethods = false)
public class DocumentParserConfiguration {
    @Bean
    static BeanFactoryPostProcessor documentParserLimits() {
        // A static factory avoids constructing configuration/reader beans before this processor runs.
        return beanFactory -> DocxContentPolicy.configureParserLimits();
    }
}
