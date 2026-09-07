package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MaritalStatus;
import uk.gov.hmcts.reform.deedpoll.entity.enums.PostCounter;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferToMaster;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The record subset {@link DeedPollValidation} inspects.
 *
 * <p>Ported from the {@code ValidationInput} interface in
 * {@code reference/ts/deed-poll/deed-poll-validation.ts}. That interface names the fields the gates
 * read AND carries an index signature ({@code [key: string]: unknown}), because {@code validate()}
 * looks fields up by the names in the rule matrix rather than by property access. A record of named
 * components cannot do the second half, so this wraps the field map and adds typed readers for the
 * fields the gates name -- both halves of the reference's type, with neither invented.
 *
 * <p>A field that is absent and a field that is present-and-null are the same thing here, exactly as
 * they are for the reference's {@code == null} checks. The distinction that DOES matter -- a PATCH
 * omitting a field versus clearing it -- belongs to {@link DeedPollPatch}, upstream of this.
 */
public record ValidationInput(Map<String, Object> values) {

    public ValidationInput(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** Nothing populated: every rule that can impose nothing imposes nothing. */
    public static ValidationInput empty() {
        return new ValidationInput(Map.of());
    }

    /** Wraps a field map, as the SSR form path and the tests supply one. */
    public static ValidationInput of(Map<String, Object> values) {
        return new ValidationInput(values);
    }

    /**
     * The fields of a persisted case.
     *
     * <p>On update the caller must pass the MERGED record -- the entity after the patch has been
     * applied -- which is what every gate's javadoc means by "the merged type". A managed entity is
     * that merge, so there is nothing to overlay.
     */
    public static ValidationInput from(DeedPoll record) {
        return new ValidationInput(DeedPollFields.readAll(record));
    }

    /**
     * One field by name, or {@code null} if absent.
     */
    public Object get(String field) {
        return values.get(field);
    }

    /**
     * The case type, or {@code null} when this input does not settle one.
     */
    public DeedPollType type() {
        return read("type", DeedPollType.class);
    }

    /**
     * The urgency, gating {@code urgencyreason}.
     */
    public Urgency urgency() {
        return read("urgency", Urgency.class);
    }

    /**
     * The intake channel, gating {@code onlineApplicationReference}.
     */
    public PostCounter postCounter() {
        return read("postCounter", PostCounter.class);
    }

    /** The marital status, which a minor create defaults to Single. */
    public MaritalStatus maritalStatus() {
        return read("maritalStatus", MaritalStatus.class);
    }

    /** The refer-to-master choice, which a relinquishment create defaults to Yes. */
    public ReferToMaster referToMasterChoice() {
        return read("referToMasterChoice", ReferToMaster.class);
    }

    /** Whether the record was migrated from the previous system, which relaxes the required set. */
    public boolean migratedRecord() {
        return Boolean.TRUE.equals(read("migratedRecord", Boolean.class));
    }

    /**
     * Whether to publish to the London Gazette, gating the notice date.
     *
     * <p>{@link Boolean} rather than {@code boolean}: the gate fires on an explicit {@code false} and
     * must leave an absent value alone, which is the reference's
     * {@code publishToLondonGazette === false}.
     */
    public Boolean publishToLondonGazette() {
        return read("publishToLondonGazette", Boolean.class);
    }

    /**
     * One field as the type a gate reads it at, coercing the text form a form body produces.
     *
     * <p>{@link DeedPollFields#asType} carries the reasoning: a merged record may hold a field as a domain
     * type or as the member name a caller sent, and narrowing on {@code isInstance} alone would read the
     * second as "not settled" -- so a form save that changed the type would leave every gate depending on
     * it imposing nothing, silently.
     */
    private <T> T read(String field, Class<T> type) {
        return DeedPollFields.asType(type, values.get(field));
    }
}
