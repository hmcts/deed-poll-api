package uk.gov.hmcts.reform.deedpoll.domain.masters;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.reform.deedpoll.notify.NotifyProperties;

/**
 * The Master emails' configuration (plan D3), checked once at start-up by {@link MasterEmailLinks}.
 */
@Configuration
@EnableConfigurationProperties({MasterEmailProperties.class, AdminProperties.class})
public class MasterEmailConfiguration {

    @Bean
    public MasterEmailLinks masterEmailLinks(AdminProperties admin, NotifyProperties notify) {
        return new MasterEmailLinks(admin, notify);
    }
}
