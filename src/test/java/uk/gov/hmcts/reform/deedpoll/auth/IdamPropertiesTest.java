package uk.gov.hmcts.reform.deedpoll.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
        IdamProperties properties = new IdamProperties(null, null);

        assertThat(properties.issuerUrl()).isEmpty();
        assertThat(properties.audiences()).isEmpty();
    }

    @Test
    @DisplayName("trims the issuer, because a trailing newline in a Helm value is invisible")
    void trimsTheIssuer() {
        assertThat(new IdamProperties("  https://idam.example/o\n ", List.of()).issuerUrl())
            .isEqualTo("https://idam.example/o");
    }

    @Test
    @DisplayName("trims each audience and drops the blanks a trailing comma leaves behind")
    void trimsAndDropsBlankAudiences() {
        // `IDAM_API_AUDIENCES=deed_poll_api,` splits to a trailing empty element, which would otherwise be
        // an audience no token can carry and a list that is not empty -- so it would pass the fail-closed
        // check while matching nothing.
        assertThat(new IdamProperties("", List.of(" deed_poll_api ", "", "  ", "xui_webapp")).audiences())
            .containsExactly("deed_poll_api", "xui_webapp");
    }

    @Test
    @DisplayName("a list of nothing but blanks is empty, so it fails closed rather than matching nothing")
    void listOfNothingButBlanksIsEmpty() {
        assertThat(new IdamProperties("", List.of(" ", "")).audiences()).isEmpty();
    }
}
