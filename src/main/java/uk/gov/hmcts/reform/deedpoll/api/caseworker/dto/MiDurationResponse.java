package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gov.hmcts.reform.deedpoll.domain.mi.DurationFigures;

/**
 * How long a bucket's intervals took, in whole calendar days, with how many there were (plan D4, D6).
 */
public record MiDurationResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "How many intervals the bucket holds.")
    long count,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
        description = "The median, in calendar days, interpolated between the two middle values (percentile_cont); "
            + "null when count is 0, which is not the same as zero days.")
    Double medianDays,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
        description = "The 95th percentile, in calendar days, interpolated likewise; published however small count "
            + "is, and null when it is 0.")
    Double p95Days) {

    public static MiDurationResponse from(DurationFigures figures) {
        return new MiDurationResponse(figures.count(), figures.medianDays(), figures.p95Days());
    }
}
