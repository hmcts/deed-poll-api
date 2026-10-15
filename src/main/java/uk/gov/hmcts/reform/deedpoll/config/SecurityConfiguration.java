package uk.gov.hmcts.reform.deedpoll.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import uk.gov.hmcts.reform.authorisation.ServiceAuthorisationApi;
import uk.gov.hmcts.reform.authorisation.filters.ServiceAuthFilter;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.authorisation.validators.ServiceAuthTokenValidator;
import uk.gov.hmcts.reform.deedpoll.auth.AmRoleSource;
import uk.gov.hmcts.reform.deedpoll.auth.IdamAuthenticationConverter;
import uk.gov.hmcts.reform.deedpoll.auth.IdamDiscovery;
import uk.gov.hmcts.reform.deedpoll.auth.IdamJwtDecoder;
import uk.gov.hmcts.reform.deedpoll.auth.IdamProperties;
import uk.gov.hmcts.reform.deedpoll.auth.IdamUserInfoClient;
import uk.gov.hmcts.reform.deedpoll.auth.RoleAssignmentClient;
import uk.gov.hmcts.reform.deedpoll.auth.RoleAssignmentProperties;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdCallerAuthenticationConverter;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdProperties;
import uk.gov.hmcts.reform.deedpoll.ccd.DeedPollAccessProfile;
import uk.gov.hmcts.reform.deedpoll.ccd.DeedPollRoleMapping;

import java.time.Clock;
import java.util.List;

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
 * <p>Every business route is also inside a {@link StaffArea}, with one named exception:
 * {@link #ANY_AUTHENTICATED_PATHS}, the caller's own {@code GET /me}, which any token may read.
 *
 * <p><strong>Two chains, and the second is narrow on purpose.</strong> {@code /ccd-persistence/**} is the one path
 * not called by a person through a frontend. Data-store calls it, presenting its own S2S token and forwarding a
 * user's, so it has a chain of its own ({@link #ccdPersistenceFilterChain}) that requires both. Every other path,
 * that one's siblings included, is in {@link #securityFilterChain} and requires no S2S at all.
 *
 * <p>A consequence worth knowing: an unknown path answers 401 rather than 404, because authorisation happens
 * before routing. That is the right way round -- which paths exist is not something an unauthenticated caller
 * needs to learn.
 *
 * <p>Sessions are stateless and CSRF is disabled, which go together: there is no cookie to ride, every
 * request carries its own bearer token, and a CSRF token would be a thing for API clients to fetch and
 * forward for no gain.
 *
 * <p>The IDAM beans are declared here rather than in their own configuration class so that how
 * authentication is wired has one place to be read.
 *
 * <p><strong>A {@code @WebMvcTest} slice does not get this chain.</strong> The slice's include list covers
 * {@link SecurityFilterChain} beans, and a {@code @Configuration} class that <em>declares</em> one is not
 * itself one, so this class is filtered out and the slice falls back to Spring Boot's default chain --
 * which authenticates every path, including the ones allowlisted above. A slice test therefore asserts
 * Boot's defaults rather than this service's rules, in either direction: it refuses a public path and it
 * would admit a protected one under {@code @WithMockUser}. Tests that mean to assert authentication boot
 * the whole context; {@code GetWelcomeTest} records where that bit.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({IdamProperties.class, RoleAssignmentProperties.class})
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

    /**
     * The only business paths outside every {@link StaffArea}: any authenticated caller may call them.
     *
     * <p>{@code /me} answers what the caller may do, so a caller every area refuses must still reach it, to be told
     * "signed in, but no access" rather than left to read a 403. It is matched as a GET with {@code authenticated()}
     * ahead of {@code anyRequest()}, the same outcome stated as intent. {@code StaffAccessRulesTest} admits these
     * routes, pins this set, and pins {@code /me} to one GET handler, so adding a path or a method is a reviewed
     * change. Not {@link #PUBLIC_PATHS}: no token is still 401.
     */
    static final String[] ANY_AUTHENTICATED_PATHS = {
        "/me"
    };

    /**
     * Where data-store posts a case event into the decentralised runtime and reads a case back out of it. The one
     * SDK path that is not refused: {@link #ccdPersistenceFilterChain} admits it for {@link #CCD_PERSISTENCE_CALLERS}
     * with an authorised user, and nobody else (plan 02, Task 5).
     */
    static final String CCD_PERSISTENCE_PATH = "/ccd-persistence/**";

    /**
     * The only services that may call {@link #CCD_PERSISTENCE_PATH}: data-store, and nothing else.
     *
     * <p><strong>A constant, not {@code idam.s2s-authorised.services}.</strong> That property switches on the S2S
     * library's own filter bean, which reads as a service-wide allowlist. Here it would be read as one, and widened
     * the day some other namespace needed a caller. This allowlist belongs to one path, and its one member is the
     * definition of that path. This service's own name is deliberately absent too. The linking operation reaches
     * data-store through CCD's public API, never by posting to its own persistence controller (plan 02,
     * decision 11), so a {@code deed_poll_api} token here is either a mistake or a way around data-store's checks.
     */
    static final List<String> CCD_PERSISTENCE_CALLERS = List.of("ccd_data");

    /**
     * The routes the CCD SDK maps that nobody may call.
     *
     * <p>{@code /callbacks/**} is the generator's callback controller, which a decentralised case type does not use.
     * Under {@code anyRequest().authenticated()} alone, any valid IDAM token -- a caseworker's included -- could
     * reach it. {@code /ccd-persistence/**} was refused here too until plan 02 Task 5 gave it its own chain, which
     * replaced the refusal rather than sitting beside it. {@code CcdSdkRoutesTest} fails if the SDK maps anything
     * outside this and {@link #CCD_PERSISTENCE_PATH}.
     */
    static final String[] CCD_SDK_PATHS = {
        "/callbacks/**"
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

    /**
     * The RAS lookup, with S2S minted only when a lookup is made: {@link ServiceAuthConfiguration}'s generator is
     * lazy, and with {@code deedpoll.roles.am.enabled} off nothing here asks for it.
     */
    @Bean
    public RoleAssignmentClient roleAssignmentClient(RoleAssignmentProperties properties,
                                                     ObjectProvider<AuthTokenGenerator> serviceTokens) {
        return new RoleAssignmentClient(properties, () -> serviceTokens.getObject().generate());
    }

    @Bean
    public AmRoleSource amRoleSource(RoleAssignmentProperties properties, RoleAssignmentClient client,
                                     CcdProperties ccdProperties) {
        return new AmRoleSource(properties, client, ccdProperties.systemRole(), Clock.systemUTC());
    }

    /**
     * The caller for every chain but {@code /ccd-persistence/**}, whose converter stays IDAM-only until the case
     * definition maps AM roles (plan 20261014, decision 8).
     */
    @Bean
    public IdamAuthenticationConverter idamAuthenticationConverter(IdamUserInfoClient userInfoClient,
                                                                   AmRoleSource amRoleSource) {
        return new IdamAuthenticationConverter(userInfoClient, amRoleSource);
    }

    /**
     * {@code /ccd-persistence/**}, and nothing else: S2S from data-store, then the user data-store forwards.
     *
     * <p>Ordered ahead of {@link #securityFilterChain}, which matches every request and so must come last.
     * Everything that decides who may reach the path is in this chain and not in that one.
     *
     * <ul>
     *   <li><strong>The service.</strong> The S2S library's {@link ServiceAuthFilter} answers a missing or
     *       unverifiable {@code ServiceAuthorization} with 401, and a verified service outside
     *       {@link #CCD_PERSISTENCE_CALLERS} with 403, before any user token is read. It is built here and
     *       <em>not</em> declared as a bean. Boot registers every {@code Filter} bean on every path, which would
     *       make the caseworker and judicial namespaces start requiring S2S.</li>
     *   <li><strong>The user.</strong> The same {@link JwtDecoder} as everywhere else, so the token is checked
     *       exactly as a caseworker's is. The caller must then hold an access profile the definition grants
     *       something to, reached through the definition's own role mapping ({@link DeedPollRoleMapping}) rather
     *       than a second list.</li>
     * </ul>
     */
    @Bean
    @Order(1)
    @SuppressWarnings("PMD.SignatureDeclareThrowsException")
    public SecurityFilterChain ccdPersistenceFilterChain(HttpSecurity http,
                                                         JwtDecoder jwtDecoder,
                                                         IdamUserInfoClient userInfoClient,
                                                         CcdProperties ccdProperties,
                                                         ServiceAuthorisationApi serviceAuthorisationApi)
        throws Exception {

        ServiceAuthFilter serviceAuthFilter = new ServiceAuthFilter(
            new ServiceAuthTokenValidator(serviceAuthorisationApi), CCD_PERSISTENCE_CALLERS);
        String[] grantedProfiles = DeedPollRoleMapping.granted().stream()
            .map(DeedPollAccessProfile::getRole)
            .toArray(String[]::new);

        return http
            // A path-pattern matcher stated outright, not securityMatcher(String). The string form builds an MVC
            // matcher at once, and that needs the MVC introspector, which a context with no web server lacks.
            .securityMatcher(PathPatternRequestMatcher.withDefaults().matcher(CCD_PERSISTENCE_PATH))
            .addFilterBefore(serviceAuthFilter, BearerTokenAuthenticationFilter.class)
            .authorizeHttpRequests(requests -> requests.anyRequest().hasAnyAuthority(grantedProfiles))
            .oauth2ResourceServer(resourceServer -> resourceServer
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder)
                    .jwtAuthenticationConverter(new CcdCallerAuthenticationConverter(
                        userInfoClient, DeedPollRoleMapping.from(ccdProperties)))))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .build();
    }

    /**
     * Every path not claimed by {@link #ccdPersistenceFilterChain}.
     *
     * <p>Authentication alone was the rule for every staff route until the {@link StaffArea} rules, so any token IDAM
     * issued could reach the caseworker surface (OWASP finding H1). The judicial area is enforced outright, because
     * its services already refuse a non-judge and the rule changes no outcome. The caseworker area goes through
     * {@link StaffGate}, which reports before it enforces: see {@code deedpoll.security.staff-gate}.
     * {@link #ANY_AUTHENTICATED_PATHS} are authenticated by name. {@code anyRequest().authenticated()} stays as the
     * fallback for the empty citizen namespace and unknown paths. A new top-level prefix is caught by
     * {@code StaffAccessRulesTest} rather than by a {@code denyAll()}.
     */
    @Bean
    @SuppressWarnings("PMD.SignatureDeclareThrowsException")
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                  JwtDecoder jwtDecoder,
                                                  IdamAuthenticationConverter authenticationConverter,
                                                  ObjectMapper objectMapper,
                                                  @Value("${deedpoll.security.staff-gate:enforce}")
                                                  String staffGateMode)
        throws Exception {

        StaffGate caseworkerGate = new StaffGate(StaffArea.CASEWORKER, StaffGate.Mode.of(staffGateMode));

        return http
            .authorizeHttpRequests(requests -> requests
                // The error page renders the failure of a request that has already been authorised, and the
                // container dispatches to it after the security context has been cleared. Authorising the
                // forward as if it were a fresh request turns every 400 and 404 into a 401.
                .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                .requestMatchers(PUBLIC_PATHS).permitAll()
                .requestMatchers(CCD_SDK_PATHS).denyAll()
                .requestMatchers(StaffArea.JUDICIAL.pattern()).hasAnyAuthority(StaffArea.JUDICIAL.authorities())
                .requestMatchers(StaffArea.CASEWORKER.pattern()).access(caseworkerGate.manager())
                .requestMatchers(HttpMethod.GET, ANY_AUTHENTICATED_PATHS).authenticated()
                .anyRequest().authenticated())
            .exceptionHandling(errors -> errors.accessDeniedHandler(new StaffAccessDeniedHandler(objectMapper)))
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
