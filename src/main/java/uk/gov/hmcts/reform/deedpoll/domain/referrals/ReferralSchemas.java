package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Shape validation for a referral create payload.
 *
 * <p><strong>Not a port.</strong> Referrals are new behaviour, so like {@code SavedQuerySchemas} -- and
 * unlike {@code DeedPollSchemas}, {@code NoteSchemas} and {@code WitnessSchemas} -- there is no
 * {@code reference/ts} schema behind this one and none to look for.
 *
 * <p>There is no update parse, and that is the shape of the feature rather than a gap. A referral is not
 * edited in place: amending one is withdrawing it and raising another, so the only write that carries a body
 * is the create. Withdrawal is a sub-resource with no payload at all.
 *
 * <p><strong>Every refusal is reported at once</strong>, as {@code SavedQuerySchemas} reports them: a
 * payload with no reasons <em>and</em> an urgent referral that does not say why comes back naming both,
 * rather than costing the caseworker a round trip per mistake.
 *
 * <p><strong>Two rules here are conditional, and both are conditional on another field of the same
 * payload</strong> -- which is why they live here and not on the entity, where a database constraint would
 * have to be relaxed for the backfill:
 *
 * <ul>
 *   <li>{@link #NOTES} is required when {@link ReferralReason#Other} is among the reasons. "Other" with no
 *       explanation is the one selection that tells the judiciary nothing.</li>
 *   <li>{@link #URGENCY_REASON} is required when {@link #URGENCY} is {@link Urgency#Urgent}, so an urgent
 *       referral always says why it jumped the queue.</li>
 * </ul>
 *
 * <p>A backfilled referral satisfies neither and is stored anyway: it comes from
 * {@code V7__backfill_referrals.sql}, not from here, and its empty reason set is flagged {@code migrated}
 * rather than validated into something invented.
 *
 * <p>Unknown keys are stripped rather than reported, as elsewhere: only the names below are read, and
 * {@link CreateReferralInput} has nowhere to put anything else -- notably {@code state}, {@code migrated}
 * and any target judge.
 */
public final class ReferralSchemas {

    /**
     * {@link #NOTES}' cap.
     *
     * <p>The column is unbounded {@code text}, so this is not the database's limit -- it is
     * {@code NoteForm.MAX_NOTE_LENGTH}'s. Raising a referral writes a note onto the case timeline that
     * quotes this text, and that note's column <em>is</em> bounded, so without a cap here a long enough
     * referral would be refused by the notes validator half way through a write that had already stored the
     * referral. {@link ReferralNotes} is where the two are held to agree.
     */
    public static final int MAX_NOTES_LENGTH = 1000;

    /**
     * {@link #URGENCY_REASON}'s cap, narrower than the notes' because it answers one question.
     */
    public static final int MAX_URGENCY_REASON_LENGTH = 500;

    /** The summary every refusal from this class carries; the per-field detail is in the errors. */
    private static final String INVALID_REFERRAL = "Referral is not valid";

    private static final String REASONS = "reasons";
    private static final String NOTES = "notes";
    private static final String URGENCY = "urgency";
    private static final String URGENCY_REASON = "urgencyReason";

    private ReferralSchemas() {
    }

    /**
     * Validates a create payload.
     *
     * <p>{@link #REASONS} is the only required field of its own accord; {@link #NOTES} and
     * {@link #URGENCY_REASON} become required through the other two. {@link #URGENCY} defaults to
     * {@link Urgency#Normal} rather than being demanded, because most referrals are not urgent and a
     * required field whose usual answer is the default is a field every caller fills in without reading.
     *
     * @throws InvalidInputError with one entry per offending field
     */
    public static CreateReferralInput parseCreateReferral(Map<String, Object> raw) {
        List<FieldError> errors = new ArrayList<>();

        List<ReferralReason> reasons = reasons(raw, errors);
        Optional<String> notes = boundedText(raw, NOTES, MAX_NOTES_LENGTH, errors);
        Urgency urgency = urgency(raw, errors).orElse(Urgency.Normal);
        Optional<String> urgencyReason =
            boundedText(raw, URGENCY_REASON, MAX_URGENCY_REASON_LENGTH, errors);

        // Checked after the fields they depend on, and only where those fields were themselves acceptable:
        // demanding an explanation for a reason that was misspelled would name a second field the caller
        // cannot act on until they have fixed the first.
        if (reasons.contains(ReferralReason.Other) && notes.isEmpty()) {
            errors.add(new FieldError(NOTES, NOTES + " is required when " + ReferralReason.Other
                + " is among the " + REASONS));
        }
        if (urgency == Urgency.Urgent && urgencyReason.isEmpty()) {
            errors.add(new FieldError(URGENCY_REASON, URGENCY_REASON + " is required when " + URGENCY
                + " is " + Urgency.Urgent));
        }

        refuseIfAny(errors);
        return new CreateReferralInput(reasons, notes, urgency, urgencyReason);
    }

    // --- reasons ---

    /**
     * The reasons as enum members, de-duplicated, in the order the caller sent them.
     *
     * <p>De-duplicated rather than refused: a multi-select posting the same box twice is a client bug with
     * one obvious correct reading, and the stored array is a set in everything but type. Order is kept so a
     * round trip renders the checkboxes as the caseworker ticked them.
     *
     * @return the reasons, or an empty list with the reason recorded -- so the {@code Other} rule below sees
     *     no {@code Other} in a payload it could not read
     */
    private static List<ReferralReason> reasons(Map<String, Object> raw, List<FieldError> errors) {
        Object value = raw.get(REASONS);
        if (value == null) {
            // Absent and explicitly null take the same message: what the caller has to do about it is
            // identical, and neither is a referral anybody could act on.
            errors.add(new FieldError(REASONS, REASONS + " must name at least one reason for the referral"));
            return List.of();
        }
        Optional<List<String>> wire = wireStrings(value);
        if (wire.isEmpty()) {
            errors.add(new FieldError(REASONS, REASONS + " must be a list of text"));
            return List.of();
        }
        if (wire.get().isEmpty()) {
            errors.add(new FieldError(REASONS, REASONS + " must name at least one reason for the referral"));
            return List.of();
        }

        LinkedHashSet<ReferralReason> accepted = new LinkedHashSet<>();
        for (String name : wire.get()) {
            reason(name.trim(), errors).ifPresent(accepted::add);
        }
        return List.copyOf(accepted);
    }

    /**
     * One reason by name, or empty with the unknown name quoted back.
     *
     * <p>Matched exactly rather than case-insensitively. The names are the wire form <em>and</em> the stored
     * form -- see {@link ReferralReason} -- so accepting {@code "other"} here would store a value that no
     * later reader matches against the enum.
     */
    private static Optional<ReferralReason> reason(String name, List<FieldError> errors) {
        return Arrays.stream(ReferralReason.values())
            .filter(candidate -> candidate.name().equals(name))
            .findFirst()
            .or(() -> {
                errors.add(new FieldError(REASONS, REASONS + " names no referral reason: " + quoted(name)
                    + ". One of " + members(ReferralReason.values())));
                return Optional.empty();
            });
    }

    // --- urgency ---

    /**
     * The urgency, or empty for absent -- which the caller reads as {@link Urgency#Normal}.
     *
     * <p>The enum is reused rather than a "Standard/Urgent" synonym being minted: {@link Urgency#Normal} is
     * the existing member and means the same thing, and the label a service displays is that service's
     * choice. Refused rather than coerced for an unknown value, for the reason a saved query's
     * {@code shared} flag is: silently reading it as {@code Normal} would drop the one signal that gets a
     * referral looked at today.
     */
    private static Optional<Urgency> urgency(Map<String, Object> raw, List<FieldError> errors) {
        Object value = raw.get(URGENCY);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof String name)) {
            errors.add(new FieldError(URGENCY, URGENCY + " must be text"));
            return Optional.empty();
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        return Arrays.stream(Urgency.values())
            .filter(candidate -> candidate.name().equals(trimmed))
            .findFirst()
            .or(() -> {
                errors.add(new FieldError(URGENCY, URGENCY + " names no urgency: " + quoted(trimmed)
                    + ". One of " + members(Urgency.values())));
                return Optional.empty();
            });
    }

    // --- notes and urgencyReason: optional bounded text, where blank means none ---

    /**
     * An optional text field, trimmed, absent when the caller sent nothing or sent only whitespace.
     *
     * <p>Blank becomes absent rather than an empty string, as it does for a saved query's description, and
     * here it is what makes the two conditional rules above work: {@code notes: "   "} must not satisfy
     * {@code Other}, because a stored explanation of nothing reads as an explanation until somebody opens
     * it.
     */
    private static Optional<String> boundedText(
        Map<String, Object> raw, String field, int maxLength, List<FieldError> errors) {

        Object value = raw.get(field);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof String text)) {
            errors.add(new FieldError(field, field + " must be text"));
            return Optional.empty();
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        if (trimmed.length() > maxLength) {
            errors.add(new FieldError(field, field + " must be " + maxLength + " characters or fewer"));
            return Optional.empty();
        }
        return Optional.of(trimmed);
    }

    /**
     * The value as a list of strings, or empty if it is not one.
     *
     * <p>A bare string is refused rather than read as a single reason, exactly as a saved query's conditions
     * are: a client sending one reason would otherwise post a different shape from one sending two, and
     * every reader would then have to handle both.
     */
    private static Optional<List<String>> wireStrings(Object value) {
        Collection<?> raw;
        if (value instanceof Collection<?> collection) {
            raw = collection;
        } else if (value instanceof Object[] array) {
            raw = List.of(array);
        } else {
            return Optional.empty();
        }
        List<String> strings = new ArrayList<>();
        for (Object element : raw) {
            if (!(element instanceof String name)) {
                return Optional.empty();
            }
            strings.add(name);
        }
        return Optional.of(List.copyOf(strings));
    }

    private static void refuseIfAny(List<FieldError> errors) {
        if (!errors.isEmpty()) {
            throw new InvalidInputError(INVALID_REFERRAL, errors);
        }
    }

    /** The acceptable values, listed in the refusal so a client does not have to hold the enum. */
    private static String members(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.joining(", "));
    }

    /** A caller's token, quoted and truncated, safe to put in a message. */
    private static String quoted(String token) {
        int maxQuoted = 60;
        String bounded = token.length() <= maxQuoted ? token : token.substring(0, maxQuoted) + "…";
        return "'" + bounded + "'";
    }
}
