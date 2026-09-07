package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import lombok.Builder;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The subset of date fields the state cascade reads.
 *
 * <p>Ported from the {@code CaseStateInput} interface in {@code reference/ts/deed-poll/case-state.ts}.
 * The component order is the cascade's evaluation order rather than the case form's, because that
 * order is load-bearing in {@link CaseState#deriveState} and reading the two side by side is how a
 * reviewer checks it.
 *
 * <p>A missing date is {@code null}. The reference also has to defend against an <em>invalid</em>
 * {@code Date} -- {@code new Date("nonsense")} -- which a {@link LocalDateTime} cannot hold, so
 * "unset" here has one representation instead of two.
 */
@Builder
public record CaseStateInput(
    LocalDateTime dateReturned,
    LocalDateTime dateEnroled,
    LocalDateTime signatureReceivedDate,
    LocalDateTime signatureRequestedDate,
    LocalDateTime dateReferred,
    LocalDateTime dateReadyForMaster,
    LocalDateTime awaitingFurtherDocsInfo,
    LocalDateTime dateReceived) {

    /**
     * The state-driving date fields, named as the reference's {@code STATE_DATE_FIELDS} names them.
     *
     * <p>Kept because it is the documented answer to "which dates decide the state", which the record
     * components alone only imply.
     */
    public static final List<String> STATE_DATE_FIELDS = List.of(
        "dateReturned",
        "dateEnroled",
        "signatureReceivedDate",
        "signatureRequestedDate",
        "dateReferred",
        "dateReadyForMaster",
        "awaitingFurtherDocsInfo",
        "dateReceived");

    /**
     * No dates at all, which the cascade resolves to {@code Received}.
     */
    public static CaseStateInput empty() {
        return builder().build();
    }

    /**
     * The state-driving dates of a case.
     *
     * <p>This is the reference's {@code stateInputFrom(existing overlaid with patch)}. There is no
     * overlaying to do here: a patch is applied to the managed entity before the state is derived, so
     * the entity <em>is</em> the merged record the reference has to assemble by hand.
     */
    public static CaseStateInput from(DeedPoll record) {
        return builder()
            .dateReturned(record.getDateReturned())
            .dateEnroled(record.getDateEnroled())
            .signatureReceivedDate(record.getSignatureReceivedDate())
            .signatureRequestedDate(record.getSignatureRequestedDate())
            .dateReferred(record.getDateReferred())
            .dateReadyForMaster(record.getDateReadyForMaster())
            .awaitingFurtherDocsInfo(record.getAwaitingFurtherDocsInfo())
            .dateReceived(record.getDateReceived())
            .build();
    }

    /**
     * The state-driving dates of a merged field map.
     *
     * <p>This is the reference's {@code stateInputFrom(source)} verbatim: pull the eight named dates out
     * of whatever the caller assembled, defaulting a missing one to null. It is the overload the write
     * path uses, because the merge there is existing-overlaid-with-patch and exists only as a map.
     */
    public static CaseStateInput from(ValidationInput merged) {
        return builder()
            .dateReturned(date(merged, "dateReturned"))
            .dateEnroled(date(merged, "dateEnroled"))
            .signatureReceivedDate(date(merged, "signatureReceivedDate"))
            .signatureRequestedDate(date(merged, "signatureRequestedDate"))
            .dateReferred(date(merged, "dateReferred"))
            .dateReadyForMaster(date(merged, "dateReadyForMaster"))
            .awaitingFurtherDocsInfo(date(merged, "awaitingFurtherDocsInfo"))
            .dateReceived(date(merged, "dateReceived"))
            .build();
    }

    private static LocalDateTime date(ValidationInput merged, String field) {
        Object value = merged.get(field);
        return value instanceof LocalDateTime dateTime ? dateTime : null;
    }
}
