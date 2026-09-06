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
 * <p><strong>Configured but unused.</strong> Nothing in plan 01 calls another CFT service -- CCD is plan 02
 * and CDAM is plan 03 -- so nothing injects this yet. It exists now because the shape of S2S is not something
 * to work out under time pressure later, and because a plan-02 session finding a generator already here is
 * less likely to invent a second way of minting one.
 *
 * <p>{@code @Lazy}, so the bean is not even created until something asks for it: a token generator that
 * nobody injects must not be a reason for the application to fail to start.
 *
 * <p>The secret has <strong>no working default</strong> ({@code DEED_POLL_API_S2S_SECRET} defaults to empty),
 * so an environment that has not been given one cannot mint a token -- it would be refused anyway, and a
 * committed placeholder that happened to work somewhere is the failure worth designing out. Nothing else in
 * the library is switched on: {@code idam.s2s-authorised.services} is deliberately unset, so no inbound S2S
 * filter is registered, and its health indicator is disabled in {@code application.yaml} because it would
 * report on a dependency this service does not have.
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
