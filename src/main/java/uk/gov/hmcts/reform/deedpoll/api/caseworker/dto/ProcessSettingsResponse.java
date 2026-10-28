package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettings;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettingsTimes;

import java.time.LocalDateTime;

/** Revision zero and null attribution mean the seeded values have never been changed. */
public record ProcessSettingsResponse(
    @Schema(description = "The processing window: how long a Master has to give a direction before the referral is"
        + " unassigned, in minutes (1 to 30 days). The wire name is the former claim window's.",
        minimum = "1440", maximum = "43200", format = "int32") int referralClaimExpiryMinutes,
    @Schema(description = "How often a Master is reminded of their undirected referrals, in hours.",
        minimum = "1", maximum = "168", format = "int32") int reminderIntervalHours,
    @Schema(description = "How many referrals one Master may be assigned per referral window.",
        minimum = "1", maximum = "100", format = "int32") int masterWindowCap,
    @Schema(description = "The morning referral window's start, Europe/London, HH:mm.",
        pattern = ProcessSettingsTimes.PATTERN) String morningWindowStart,
    @Schema(description = "The morning referral window's end, Europe/London, HH:mm.",
        pattern = ProcessSettingsTimes.PATTERN) String morningWindowEnd,
    @Schema(description = "The afternoon referral window's start, Europe/London, HH:mm.",
        pattern = ProcessSettingsTimes.PATTERN) String afternoonWindowStart,
    @Schema(description = "The afternoon referral window's end, Europe/London, HH:mm.",
        pattern = ProcessSettingsTimes.PATTERN) String afternoonWindowEnd,
    int revision,
    @Schema(nullable = true) LocalDateTime updatedAt,
    @Schema(nullable = true) String updatedBy) {

    public static ProcessSettingsResponse from(ProcessSettings settings) {
        return new ProcessSettingsResponse(settings.referralClaimExpiryMinutes(), settings.reminderIntervalHours(),
            settings.masterWindowCap(), ProcessSettingsTimes.format(settings.morningWindowStart()),
            ProcessSettingsTimes.format(settings.morningWindowEnd()),
            ProcessSettingsTimes.format(settings.afternoonWindowStart()),
            ProcessSettingsTimes.format(settings.afternoonWindowEnd()), settings.revision(), settings.updatedAt(),
            settings.updatedBy());
    }
}
