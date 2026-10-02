package uk.gov.hmcts.reform.deedpoll.domain.staging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableConfigurationProperties(StagingPolicy.class)
public class StagingConfiguration {
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "deedpoll.documents.staging.enabled", havingValue = "true")
    static class Scheduling {
        @Bean
        Cleanup stagingCleanup(OwnedStaging staging) {
            return new Cleanup(staging);
        }
    }

    static class Cleanup {
        private final OwnedStaging staging;

        Cleanup(OwnedStaging staging) {
            this.staging = staging;
        }

        @Scheduled(fixedDelayString = "${deedpoll.documents.staging.cleanup-interval-ms}",
            initialDelayString = "${deedpoll.documents.staging.cleanup-interval-ms}")
        public void run() {
            staging.expire();
        }
    }
}
