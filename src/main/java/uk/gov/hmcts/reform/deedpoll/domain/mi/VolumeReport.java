package uk.gov.hmcts.reform.deedpoll.domain.mi;

import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;

import java.util.List;
import java.util.Map;

/**
 * K1: cases received per period, split by type, and the open caseload at the end of each period.
 *
 * @param periods every period of the request, empty ones included
 */
public record VolumeReport(MiParameters parameters, List<Period> periods, Excluded excluded) {

    /**
     * One period's volume.
     *
     * @param received       cases whose {@code dateReceived} falls in the period
     * @param receivedByType the same, by every {@link DeedPollType} in declaration order; a case with no type is
     *                       counted as {@code Unknown}
     * @param openAtEnd      cases received on or before the period's end with neither {@code dateEnroled} nor
     *                       {@code dateReturned} on or before it
     */
    public record Period(MiPeriod period, long received, Map<DeedPollType, Long> receivedByType, long openAtEnd) {
    }

    /**
     * What was left out, and why.
     *
     * @param noDateReceived cases with no {@code dateReceived}, which no period can hold, migrated or not
     * @param migrated       migrated cases received in the range, left out because {@code includeMigrated} is false
     */
    public record Excluded(long noDateReceived, long migrated) {
    }
}
