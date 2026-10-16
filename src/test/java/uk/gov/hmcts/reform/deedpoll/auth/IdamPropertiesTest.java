package uk.gov.hmcts.reform.deedpoll.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The normalisation that decides whether the audience list ends up empty -- and empty is the case that
 * refuses every request.
 *
 * <p>Both values arrive from a Helm environment map, so both arrive as text and both arrive absent in an
 * environment nobody has configured. What the compact constructor does with an absent or whitespace-only
 * value is therefore load-bearing rather than tidy-up: a default that produced a non-empty list would make
 * {@link IdamAudienceValidator} accept any IDAM-signed token in the estate.
 */
class IdamPropertiesTest {

    @Test
    @DisplayName("an unset issuer and audience list become empty, not null")
    void unsetValuesBecomeEmpty() {
        IdamProperties properties = new IdamProperties(null, null, null);

        assertThat(properties.issuerUrl()).isEmpty();
        assertThat(properties.audiences()).isEmpty();
        assertThat(properties.additionalIssuers()).isEmpty();
    }

    @Test
    @DisplayName("trims the issuer, because a trailing newline in a Helm value is invisible")
    void trimsTheIssuer() {
        assertThat(new IdamProperties("  https://idam.example/o\n ", List.of(), List.of()).issuerUrl())
            .isEqualTo("https://idam.example/o");
    }

    @Test
    @DisplayName("trims each audience and drops the blanks a trailing comma leaves behind")
    void trimsAndDropsBlankAudiences() {
        // `IDAM_API_AUDIENCES=deed_poll_api,` splits to a trailing empty element, which would otherwise be
        // an audience no token can carry and a list that is not empty -- so it would pass the fail-closed
        // check while matching nothing.
        assertThat(new IdamProperties("", List.of(" deed_poll_api ", "", "  ", "xui_webapp"), List.of()).audiences())
            .containsExactly("deed_poll_api", "xui_webapp");
    }

    @Test
    @DisplayName("a list of nothing but blanks is empty, so it fails closed rather than matching nothing")
    void listOfNothingButBlanksIsEmpty() {
        assertThat(new IdamProperties("", List.of(" ", ""), List.of()).audiences()).isEmpty();
    }

    private static final String FORGEROCK =
        "https://forgerock-am.service.core-compute-idam-aat2.internal:8443/openam/oauth2/realms/root/realms/hmcts";

    private static List<String> additionalIssuers(String... configured) {
        return new IdamProperties("", List.of(), List.of(configured)).additionalIssuers();
    }

    @Test
    @DisplayName("trims each additional issuer, drops the blanks and drops one trailing slash")
    void normalisesAdditionalIssuers() {
        assertThat(additionalIssuers(" " + FORGEROCK + "/\n", "", "  ", "https://idam.example/o"))
            .containsExactly(FORGEROCK, "https://idam.example/o");
    }

    @Test
    @DisplayName("drops only one trailing slash, so a doubled one still cannot match a real issuer")
    void dropsOnlyOneTrailingSlash() {
        assertThat(additionalIssuers("https://idam.example/o//")).containsExactly("https://idam.example/o/");
    }

    @Test
    @DisplayName("lists a repeated additional issuer once")
    void deduplicatesAdditionalIssuers() {
        assertThat(additionalIssuers(FORGEROCK, FORGEROCK + "/")).containsExactly(FORGEROCK);
    }

    @Test
    @DisplayName("the additional issuers are immutable")
    void additionalIssuersAreImmutable() {
        List<String> issuers = additionalIssuers(FORGEROCK);

        assertThatThrownBy(() -> issuers.add("https://idam.example/o"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://idam.example/o",
        "HTTP://idam.example/o",
        "/o",
        "idam.example/o",
        "https:/o",
        "https://idam example/o",
        "ftp://idam.example/o"
    })
    @DisplayName("refuses an additional issuer that is not an absolute https URL, naming the variable")
    void refusesAnythingButAbsoluteHttps(String configured) {
        assertThatThrownBy(() -> additionalIssuers(FORGEROCK, configured))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("IDAM_API_ADDITIONAL_ISSUERS entry 2 is not an absolute https URL");
    }

    @Test
    @DisplayName("the refusal never repeats the value, so a query string in it is not logged")
    void refusalOmitsTheValue() {
        assertThatThrownBy(() -> additionalIssuers("http://idam.example/o?secret=abc"))
            .hasMessageContaining("IDAM_API_ADDITIONAL_ISSUERS")
            .hasMessageNotContaining("secret")
            .hasMessageNotContaining("idam.example");
    }

    @Test
    @DisplayName("lower-cases an upper-case HTTPS scheme, as Spring does to a token's iss, and keeps the host's case")
    void normalisesTheSchemeAsSpringDoes() {
        assertThat(additionalIssuers("HTTPS://Idam.example/o")).containsExactly("https://Idam.example/o");
    }
}
