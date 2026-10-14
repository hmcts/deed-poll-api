package uk.gov.hmcts.reform.deedpoll.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Whether a caller's organisational roles are also read from the Role Assignment Service, and where:
 * {@code deedpoll.roles.am}.
 *
 * <p><strong>Off by default, and switching it on is part of onboarding.</strong> Until Caseworker and Judicial
 * Reference Data hold deed poll profiles, and RAS admits this service's S2S name, every lookup either finds nothing
 * or is refused. A refusal falls back to the IDAM roles, so an early switch costs a warning per request rather than
 * access, but it is still the last step and not a default to flip.
 *
 * <p>Every value is normalised rather than refused, as {@link IdamProperties} does: an unset value must not be the
 * case that stops the application starting while the switch is off.
 *
 * @param enabled      whether any request calls RAS at all
 * @param url          the RAS base URL, without a trailing slash
 * @param jurisdiction the {@code attributes.jurisdiction} an assignment must carry to count here. {@code ctsc} and
 *                     {@code ctsc-team-leader} are shared role names, so without it another service's team leader
 *                     would be one of ours
 * @param gazetteSkill the {@code authorisations} entry that makes a {@code ctsc-team-leader} a Delivery Manager.
 *                     Empty means AM never yields one, which is the right answer until RD allocates the code
 */
@ConfigurationProperties(prefix = "deedpoll.roles.am")
public record RoleAssignmentProperties(boolean enabled, String url, String jurisdiction, String gazetteSkill) {

    static final String DEFAULT_URL = "http://localhost:4096";
    static final String DEFAULT_JURISDICTION = "DEEDPOLL";

    public RoleAssignmentProperties {
        url = url == null || url.isBlank() ? DEFAULT_URL : stripTrailingSlashes(url.trim());
        jurisdiction = jurisdiction == null || jurisdiction.isBlank() ? DEFAULT_JURISDICTION : jurisdiction.trim();
        gazetteSkill = gazetteSkill == null ? "" : gazetteSkill.trim();
    }

    private static String stripTrailingSlashes(String value) {
        String stripped = value;
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }
}
