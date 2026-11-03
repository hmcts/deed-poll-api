package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import uk.gov.hmcts.reform.deedpoll.domain.mi.VolumeReport;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;

import java.time.LocalDate;
import java.util.List;

/**
 * K1, volume: cases received per period by type, and the open caseload at each period's end. Counts only; no case
 * is identified.
 */
public record MiVolumeResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    MiParametersResponse parameters,
    @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Every period of the range in order, empty ones included."))
    List<Period> periods,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    Excluded excluded) {

    /** One period, the first and last clipped to the range. */
    @Schema(name = "MiVolumePeriod")
    public record Period(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The period's first day, inclusive.")
        LocalDate start,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The period's last day, inclusive.")
        LocalDate end,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Cases whose dateReceived falls in the period.")
        long received,
        @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "received by type, every type in declaration order, zeros included; a case with no type "
                + "counts as Unknown."))
        List<TypeCount> receivedByType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Cases received on or before the period's end with neither dateEnroled nor dateReturned on "
                + "or before it.")
        long openAtEnd) {
    }

    /** One type's share of a period's receipts. */
    @Schema(name = "MiVolumeTypeCount")
    public record TypeCount(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        DeedPollType type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long count) {
    }

    /** What was left out, and why. */
    @Schema(name = "MiVolumeExcluded")
    public record Excluded(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Cases with no dateReceived, which no period can hold, whatever their dates.")
        long noDateReceived,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Migrated cases left out because includeMigrated is false: those received in the range, "
                + "and those received before it and still open at the end of its first period, which openAtEnd "
                + "would otherwise have counted; 0 otherwise.")
        long migrated) {
    }

    public static MiVolumeResponse from(VolumeReport report) {
        return new MiVolumeResponse(MiParametersResponse.from(report.parameters()),
            report.periods().stream().map(period -> new Period(period.period().start(), period.period().end(),
                period.received(),
                period.receivedByType().entrySet().stream()
                    .map(entry -> new TypeCount(entry.getKey(), entry.getValue())).toList(),
                period.openAtEnd())).toList(),
            new Excluded(report.excluded().noDateReceived(), report.excluded().migrated()));
    }
}
