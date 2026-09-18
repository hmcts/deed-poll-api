package uk.gov.hmcts.reform.deedpoll.notify;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.service.notify.NotificationClient;

/**
 * One {@link NotificationClient}, built from two configuration values.
 *
 * <p>Mirrors {@code apps/pcs/pcs-api/.../config/NotificationsConfiguration.java}: the client is a thin HTTP
 * wrapper, it holds no connection, and one bean is what every caller shares. It lives in the {@code notify}
 * package rather than in {@code config} on purpose -- an ArchUnit rule keeps {@code NotificationClient} out of
 * the rest of the service, so the one place that constructs it belongs on the inside of that boundary.
 *
 * <p><strong>Constructing it validates nothing and calls nothing.</strong> An empty API key is accepted
 * (verified against the 6.1.0 jar: the constructor splits the key into a service ID and a secret and an empty
 * string simply yields two empty strings), so an environment with no key starts normally and fails at the first
 * send with a 403 -- {@link NotificationFailure#PERMANENT}. That is deliberately the failure mode: the
 * alternative is a service that will not start because of a feature nobody in that environment is using yet.
 */
@Configuration
@EnableConfigurationProperties(NotifyProperties.class)
public class NotifyConfiguration {

    @Bean
    public NotificationClient notificationClient(NotifyProperties properties) {
        // Key first, base URL second -- the argument order is the opposite of how the properties read, which is
        // the kind of thing that silently sends a URL as a credential.
        return new NotificationClient(properties.apiKey(), properties.apiUrl());
    }
}
