package uk.gov.hmcts.reform.deedpoll.api.caller.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.security.core.GrantedAuthority;
import uk.gov.hmcts.reform.deedpoll.config.StaffArea;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Capability;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.RoleCapabilities;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * What this service will let the caller do: which staff areas admit them, and which capabilities they hold.
 *
 * <p>The admin app renders from this rather than from IDAM claims, because once AM is a role source the claims no
 * longer say what the api enforces. Each answer is computed by the same rule that enforces it, so the two cannot
 * drift: the flags are {@link StaffArea#admits}, over the same authorities as the caseworker and judicial chain
 * rules, and the capabilities are {@link RoleCapabilities#forRoles}, which {@link CurrentUser#can} uses. A caller
 * {@code staff: false} is refused on {@code /caseworker/**}; there is no longer a mode in which they are not (OWASP
 * plan 01 Task 12, 2026-11-17).
 *
 * <p><strong>No identity, on purpose.</strong> No subject, name, raw role names or AM assignments, and nothing
 * about whether a role came from IDAM or AM. The admin app has identity from IDAM already, and a second route
 * publishing the subject is the kind of leak the Gazette release review found through attribution.
 *
 * <p>{@code capabilities} is in {@link Capability} declaration order with no duplicates, so one caller always gets
 * the same bytes.
 */
public record CallerAccessResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Whether the caseworker area admits the caller")
    boolean staff,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Whether the judicial area admits the caller")
    boolean judicial,
    @ArraySchema(schema = @Schema(implementation = Capability.class), arraySchema = @Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        description = "The caller's capabilities, in declaration order"))
    List<Capability> capabilities) {

    public CallerAccessResponse {
        capabilities = List.copyOf(capabilities);
    }

    /**
     * The answer for a caller, from the authorities the filter chain authorised them on and their resolved roles.
     */
    public static CallerAccessResponse of(Collection<? extends GrantedAuthority> authorities, CurrentUser caller) {
        Set<Capability> held = RoleCapabilities.forRoles(caller.roles());
        return new CallerAccessResponse(
            StaffArea.CASEWORKER.admits(authorities),
            StaffArea.JUDICIAL.admits(authorities),
            Arrays.stream(Capability.values()).filter(held::contains).toList());
    }
}
