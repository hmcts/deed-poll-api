package uk.gov.hmcts.reform.deedpoll.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CdamOnboardingTest {

    private static final CcdProperties CONFIRMED = new CcdProperties("TEST", "TEST", "test-system");
    private static final CcdProperties PLACEHOLDERS = new CcdProperties(
        "DEED_POLL_PLACEHOLDER", "DEED_POLL_PLACEHOLDER", "DEED_POLL_PLACEHOLDER");

    @ParameterizedTest
    @ValueSource(strings = {"", "DEED_POLL_PLACEHOLDER", "ftp://cdam", "https://", "http://user:password@cdam",
        "http://cdam?token=secret", "http://cdam#fragment", "http://localhost:4455", "http://127.0.0.1:4455"})
    void incompleteEndpointRefusesStartup(String url) {
        assertThatThrownBy(() -> new CdamOnboarding(url, CONFIRMED, new MockEnvironment()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CASE_DOCUMENT_AM_URL");
    }

    @Test
    void everyIdentifierMustBeConfirmed() {
        for (CcdProperties identifiers : new CcdProperties[] {
            new CcdProperties("DEED_POLL_PLACEHOLDER", "TEST", "role"),
            new CcdProperties("TEST", "DEED_POLL_PLACEHOLDER", "role"),
            new CcdProperties("TEST", "TEST", "DEED_POLL_PLACEHOLDER")}) {
            assertThatThrownBy(() -> new CdamOnboarding("http://cdam", identifiers, new MockEnvironment()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("onboarded CCD");
        }
        assertThatCode(() -> new CdamOnboarding("http://cdam", CONFIRMED, new MockEnvironment()))
            .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "cftlib"})
    void placeholdersAreAllowedOnlyInLocalTestStacks(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        assertThatCode(() -> new CdamOnboarding("http://localhost:4455", PLACEHOLDERS, environment))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> new CdamOnboarding("http://cdam", PLACEHOLDERS, environment))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theStoreSwitchActuallyControlsStartupValidation() {
        new ApplicationContextRunner().withUserConfiguration(CdamOnboarding.class)
            .withBean(CcdProperties.class, () -> PLACEHOLDERS)
            .withPropertyValues("deedpoll.documents.store=filesystem")
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(CdamOnboarding.class));
        new ApplicationContextRunner().withUserConfiguration(CdamOnboarding.class)
            .withBean(CcdProperties.class, () -> PLACEHOLDERS)
            .withPropertyValues("deedpoll.documents.store=cdam", "case_document_am.url=http://cdam")
            .run(context -> assertThat(context).hasFailed());
    }
}
