package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteSettings;

import java.time.LocalDateTime;

/** Revision zero and null recipient/attribution mean configuration has not yet been supplied. */
public record GazetteSettingsResponse(
    @Schema(nullable = true) String recipientEmail,
    int revision,
    @Schema(nullable = true) LocalDateTime updatedAt,
    @Schema(nullable = true) String updatedBy) {

    public static GazetteSettingsResponse from(GazetteSettings settings) {
        return new GazetteSettingsResponse(settings.recipientEmail(), settings.revision(),
            settings.updatedAt(), settings.updatedBy());
    }
}
