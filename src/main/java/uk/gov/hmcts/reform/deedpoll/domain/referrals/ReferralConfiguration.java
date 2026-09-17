package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link ReferralProperties}, and nothing else.
 *
 * <p>A record with constructor binding cannot register itself: {@code @Component} instantiates a bean the
 * ordinary way and constructor binding is not the ordinary way, so a properties record needs either
 * {@code @EnableConfigurationProperties} naming it or a {@code @ConfigurationPropertiesScan} over its
 * package. This service does the former for {@code IdamProperties} -- {@code SecurityConfiguration}
 * names it -- so this does the same rather than switching the whole application to scanning, which would
 * make "is this bound?" a question about package layout instead of a line somebody can read.
 *
 * <p>It sits beside the service that reads the properties rather than in {@code config}, because what is
 * configurable about referrals is a fact about referrals.
 */
@Configuration
@EnableConfigurationProperties(ReferralProperties.class)
public class ReferralConfiguration {
}
