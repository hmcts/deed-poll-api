package uk.gov.hmcts.reform.deedpoll.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code config-gen} profile {@code ./gradlew generateCCDConfig} runs under, booted with no database anywhere.
 *
 * <p>Deliberately not an {@code AbstractPostgresContainerTest}: that the whole application starts here, with its
 * datasource pointed at a port nothing listens on, is the proof that generating a definition needs no database.
 *
 * <p>It is also where plan 02 decision 6 is held without Docker. Flyway is off under this profile, which turns off
 * the SDK's own {@code orderedFlywayMigrationStrategy} with it, so any {@link FlywayMigrationStrategy} left in this
 * context was defined by this application -- and one defined here would replace the SDK's in every other profile,
 * and the {@code ccd} schema would never be migrated. {@code SdkMigrationOrderTest} shows the same rule against
 * real Postgres.
 */
@SpringBootTest
@ActiveProfiles("config-gen")
class ConfigGenProfileTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("this application defines no FlywayMigrationStrategy of its own")
    void definesNoMigrationStrategy() {
        assertThat(context.getBeansOfType(FlywayMigrationStrategy.class))
            .as("The CCD SDK's DecentralisedFlywayAutoConfiguration runs the ccd-schema migrations and then ours, "
                + "and backs off when any other FlywayMigrationStrategy exists. Defining one here would silently "
                + "stop the ccd migrations in every environment -- plan 02, decision 6.")
            .isEmpty();
    }

    @Test
    @DisplayName("nothing migrates under the profile")
    void nothingMigrates() {
        assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
    }
}
