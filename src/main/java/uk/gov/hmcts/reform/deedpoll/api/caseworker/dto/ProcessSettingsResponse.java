package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettings;

import java.time.LocalDateTime;

/** Revision zero and null attribution mean the seeded values have never been changed. */
public record ProcessSettingsResponse(
    @Schema(description = "How long a judge's untouched claim survives before it returns to the pool, in minutes.",
        minimum = "30", maximum = "1440", format = "int32") int referralClaimExpiryMinutes,
    int revision,
    @Schema(nullable = true) LocalDateTime updatedAt,
    @Schema(nullable = true) String updatedBy) {

    public static ProcessSettingsResponse from(ProcessSettings settings) {
        return new ProcessSettingsResponse(settings.referralClaimExpiryMinutes(), settings.revision(),
            settings.updatedAt(), settings.updatedBy());
    }
}
