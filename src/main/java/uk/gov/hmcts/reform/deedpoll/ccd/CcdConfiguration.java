package uk.gov.hmcts.reform.deedpoll.ccd;

import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.idam.client.IdamApi;

/**
 * Registers {@link CcdProperties} and the whole CCD SDK: the definition generator and the decentralised runtime.
 *
 * <p>The runtime is what maps {@code /ccd-persistence/**}, which {@code SecurityConfiguration} admits to data-store
 * alone, and it refuses to start without a {@code CaseView} -- {@link DeedPollCaseView} is ours. Scanning the whole
 * of {@code uk.gov.hmcts.ccd.sdk}, as {@code pcs-api} does, also brings the generator's {@code /callbacks/**}
 * controller, which nothing here uses and the security chain refuses to everybody.
 *
 * <p><strong>The scan skips the SDK's auto-configurations</strong>, as {@code @SpringBootApplication}'s own scan does.
 * Scanned as ordinary configuration they are processed before Boot's, so the SDK's two {@code ObjectMapper} beans
 * make Jackson's auto-configured one back off, and every unqualified {@code ObjectMapper} here becomes ambiguous.
 * Left to their {@code AutoConfiguration.imports} they load after it, as the SDK intends.
 *
 * <p><strong>It also skips the SDK's JSON-definition support</strong> -- {@code JsonCCDConfigSupport} and the
 * {@code JsonCallbackBridge} it needs, which is all of {@code sdk.json} and {@code sdk.impl.json}. They serve a case
 * type defined in JSON rather than by a {@code CCDConfig}, which ours is not, and the bridge demands MVC's
 * {@code RequestMappingHandlerMapping}, so every non-web context -- each {@code WebEnvironment.NONE} test -- would
 * refuse to start for a feature nothing here uses.
 *
 * <p>{@link IdamApi} is registered because the SDK's {@code IdamService} resolves the user data-store forwards, and
 * {@code idam-java-client}'s {@code IdamClient} needs the Feign client to exist; nothing in this service calls it
 * directly. Its URL is {@code idam.api.url}.
 *
 * <p>{@link CoreCaseDataApi} is the linking operation's way into data-store, at {@code core_case_data.api.url}. It is
 * registered here rather than by the client's own {@code CoreCaseDataClientAutoConfiguration}, which
 * {@code application.yaml} excludes: that one is switched on by the same URL property and adds a health indicator
 * calling data-store, and the unauthenticated {@code /health} aggregates every indicator -- so one data-store outage
 * would read as this service being down.
 */
@Configuration
@EnableConfigurationProperties({CcdProperties.class, CcdLinkingProperties.class, CcdLinkingSweepProperties.class})
@ComponentScan(
    basePackages = "uk.gov.hmcts.ccd.sdk",
    excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "uk\\.gov\\.hmcts\\.ccd\\.sdk\\.(impl\\.)?json\\..*")
    })
@EnableFeignClients(clients = {IdamApi.class, CoreCaseDataApi.class})
public class CcdConfiguration {
}
