package uk.gov.hmcts.reform.deedpoll.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import uk.gov.hmcts.reform.authorisation.ServiceAuthorisationApi;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGeneratorFactory;

/**
 * Outbound service-to-service tokens, wired as {@code pcs-api} wires them.
 *
 * <p><strong>The one S2S source.</strong> Its first caller is plan 02's {@code CcdLinkingService}, which sends the
 * token to CCD data-store; CDAM (plan 03) is the next. A second way of minting one is the thing not to add.
 *
 * <p>{@code @Lazy}, so the bean is not even created until something asks for it, and its caller asks through an
 * {@code ObjectProvider} at the moment it needs a token: a generator nobody has provisioned must not be a reason for
 * the application to fail to start.
 *
 * <p>The secret has <strong>no working default</strong> ({@code DEED_POLL_API_S2S_SECRET} defaults to empty),
 * so an environment that has not been given one cannot mint a token -- it would be refused anyway, and a
 * committed placeholder that happened to work somewhere is the failure worth designing out. Nothing else in
 * the library is switched on: {@code idam.s2s-authorised.services} is deliberately unset, so the library registers
 * no inbound S2S filter bean. The one inbound check, for {@code /ccd-persistence/**}, is built inside
 * {@link SecurityConfiguration#ccdPersistenceFilterChain} with its own one-service allowlist. The library's health
 * indicator is disabled in {@code application.yaml}, because it would report on a dependency this service does
 * not have.
 */
@Configuration
@Lazy
public class ServiceAuthConfiguration {

    @Bean
    public AuthTokenGenerator serviceAuthTokenGenerator(
        @Value("${idam.s2s-auth.totp-secret}") String secret,
        @Value("${idam.s2s-auth.microservice}") String microservice,
        ServiceAuthorisationApi serviceAuthorisationApi
    ) {
        return AuthTokenGeneratorFactory.createDefaultGenerator(secret, microservice, serviceAuthorisationApi);
    }
}
