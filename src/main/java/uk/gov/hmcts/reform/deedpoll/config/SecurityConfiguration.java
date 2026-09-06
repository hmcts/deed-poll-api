package uk.gov.hmcts.reform.deedpoll.config;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import uk.gov.hmcts.reform.deedpoll.auth.IdamAuthenticationConverter;
import uk.gov.hmcts.reform.deedpoll.auth.IdamDiscovery;
import uk.gov.hmcts.reform.deedpoll.auth.IdamJwtDecoder;
import uk.gov.hmcts.reform.deedpoll.auth.IdamProperties;
import uk.gov.hmcts.reform.deedpoll.auth.IdamUserInfoClient;

/**
 * Authentication for the whole service: a forwarded IDAM access token, validated as a resource server.
 *
 * <p>Ported from {@code apps/api/src/auth/authenticate.ts}. The web app runs the authorization-code flow and
 * holds a session; this service has no session and no login page, so it takes the {@code access_token} that
 * flow already receives and checks it. What is checked, and where the keys come from, is
 * {@link IdamJwtDecoder}; who the token belongs to is {@link IdamUserInfoClient}.
 *
 * <p><strong>Authenticated by default.</strong> {@code anyRequest().authenticated()} with a short allowlist,
 * rather than a rule per namespace, which is the decision the reference makes and for the same reason: a
 * route added tomorrow is then protected by omission rather than protected when someone remembers, and the
 * failure mode of forgetting is silent. The allowlist is infrastructure only -- the Always-On root endpoint,
 * the probes and the published spec -- and every business route, {@code /caseworker/**} and later
 * {@code /citizen/**} alike, is authenticated because it is not on it.
 *
 * <p>A consequence worth knowing: an unknown path answers 401 rather than 404, because authorisation happens
 * before routing. That is the right way round -- which paths exist is not something an unauthenticated caller
 * needs to learn.
 *
 * <p>Sessions are stateless and CSRF is disabled, which go together: there is no cookie to ride, every
 * request carries its own bearer token, and a CSRF token would be a thing for API clients to fetch and
 * forward for no gain.
 *
 * <p>The IDAM beans are declared here rather than in their own configuration class deliberately: a
 * {@code @WebMvcTest} slice imports {@link SecurityFilterChain} beans, so a controller test would otherwise
 * build a filter chain with no decoder behind it and fail for a reason that has nothing to do with the
 * controller. One place to look for how authentication is wired is also simply true of this shape.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(IdamProperties.class)
public class SecurityConfiguration {

    /**
     * The only paths that do not require a token.
     *
     * <p>Kubernetes has no credential to present, the Always-On probe is Azure's and not ours, and the
     * OpenAPI document is published to {@code hmcts/cnp-api-docs} by a job that reads it from a running
     * container. Nothing here reads or writes case data.
     */
    private static final String[] PUBLIC_PATHS = {
        "/",
        "/health",
        "/health/**",
        "/info",
        "/prometheus",
        "/v3/api-docs",
        "/v3/api-docs/**",
        "/swagger-ui.html",
        "/swagger-ui/**",
        "/favicon.ico"
    };

    @Bean
    public IdamDiscovery idamDiscovery(IdamProperties properties, Environment environment) {
        return new IdamDiscovery(properties, environment);
    }

    @Bean
    public JwtDecoder jwtDecoder(IdamDiscovery discovery, IdamProperties properties) {
        return new IdamJwtDecoder(discovery, properties);
    }

    @Bean
    public IdamUserInfoClient idamUserInfoClient(IdamDiscovery discovery) {
        return new IdamUserInfoClient(discovery);
    }

    @Bean
    public IdamAuthenticationConverter idamAuthenticationConverter(IdamUserInfoClient userInfoClient) {
        return new IdamAuthenticationConverter(userInfoClient);
    }

    @Bean
    @SuppressWarnings("PMD.SignatureDeclareThrowsException")
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                  JwtDecoder jwtDecoder,
                                                  IdamAuthenticationConverter authenticationConverter)
        throws Exception {

        return http
            .authorizeHttpRequests(requests -> requests
                // The error page renders the failure of a request that has already been authorised, and the
                // container dispatches to it after the security context has been cleared. Authorising the
                // forward as if it were a fresh request turns every 400 and 404 into a 401.
                .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                .requestMatchers(PUBLIC_PATHS).permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(resourceServer -> resourceServer
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder)
                    .jwtAuthenticationConverter(authenticationConverter)))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .build();
    }
}
