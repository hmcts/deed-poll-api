package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gov.hmcts.reform.deedpoll.domain.mi.MiGranularity;
import uk.gov.hmcts.reform.deedpoll.domain.mi.MiParameters;

import java.time.LocalDate;

/**
 * The parameters a management-information figure was computed for, as resolved: echoed by every operation so a
 * downloaded table says what it is.
 */
public record MiParametersResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The first day of the range, inclusive.")
    LocalDate from,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The last day of the range, inclusive.")
    LocalDate to,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "How the range was divided into periods.")
    MiGranularity granularity,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Whether migrated cases were counted; true unless the request said false.")
    boolean includeMigrated) {

    public static MiParametersResponse from(MiParameters parameters) {
        return new MiParametersResponse(parameters.from(), parameters.to(), parameters.granularity(),
                                        parameters.includeMigrated());
    }
}
