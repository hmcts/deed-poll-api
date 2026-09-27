package uk.gov.hmcts.reform.deedpoll.ccd;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import uk.gov.hmcts.ccd.sdk.CCDDefinitionGenerator;

/**
 * Registers {@link CcdProperties} and the SDK's definition generator -- the generator only, not the SDK.
 *
 * <p>{@code ./gradlew generateCCDConfig} boots this application and asks it for a {@link CCDDefinitionGenerator},
 * so the generator has to be a bean here. Scanning the whole of {@code uk.gov.hmcts.ccd.sdk}, as {@code pcs-api}
 * does, would also bring the decentralised runtime, which refuses to start without a {@code CaseView} and would map
 * {@code /ccd-persistence/**}. Both arrive with plan 02 Task 6, which replaces this narrow scan with the whole
 * package; until then the generator package and its entry point are all that is registered, and nothing the SDK
 * maps is reachable.
 */
@Configuration
@EnableConfigurationProperties(CcdProperties.class)
@Import(CCDDefinitionGenerator.class)
@ComponentScan("uk.gov.hmcts.ccd.sdk.generator")
public class CcdConfiguration {
}
