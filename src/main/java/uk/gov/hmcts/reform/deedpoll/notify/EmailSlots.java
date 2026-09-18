package uk.gov.hmcts.reform.deedpoll.notify;

import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shape validation for the one payload an email send carries: the caseworker's slots, and nothing else.
 *
 * <p>The idiom is {@code ReferralSchemas}': a raw map in, a validated value out, and
 * {@link InvalidInputError} carrying one {@link FieldError} per offending field so a form can render each
 * failure against its own input. <strong>Every refusal is reported at once</strong> -- a payload that both
 * omits the judge's reason and tries to set the reference number comes back naming both, rather than costing
 * the caseworker a round trip per mistake.
 *
 * <p><strong>Unknown keys are refused and named here, not stripped.</strong> That is the one place this
 * departs from every other schema in this service, and it is decision 12: elsewhere an unknown key is a client
 * sending a field the input record has nowhere to put, which is harmless. Here the interesting unknown keys are
 * exactly the ones a client must not send -- {@code reference_number}, {@code applied_online} -- and stripping
 * them would silently honour the request while ignoring the part that mattered, so a caseworker who had tried
 * to change the reference an applicant is told would get a success and a different email. A named refusal is
 * also how the Email tab learns it is out of step with the template.
 *
 * <p><strong>{@link EmailSlot#JUDGES_REASON} is pre-populated from the referral's direction.</strong> The
 * caseworker's form opens with the judge's words already in it, so the usual case is that they send them
 * unaltered -- but the value is theirs to edit, because whether the office quotes a direction verbatim is not
 * confirmed and the design works either way. The pre-populated value is validated exactly as a typed one is:
 * an over-long direction is refused rather than truncated, because a truncated quotation of a judge is worse
 * than a caseworker being asked to shorten one.
 */
public final class EmailSlots {

    /** The summary every refusal from this class carries; the per-field detail is in the errors. */
    private static final String INVALID_CONTENT = "Email content is not valid";

    private EmailSlots() {
    }

    /**
     * Validates the slot payload for {@code template}.
     *
     * @param template the email being composed, which declares its slots and its resolved keys
     * @param raw the request body, keyed by placeholder name. Null and empty are the same thing: a caseworker
     *     who sent nothing, which is refused only because a required slot is then missing
     * @param directionsText the referral's direction, used to pre-populate
     *     {@link EmailSlot#JUDGES_REASON} when the caseworker sent none. Null for a referral with no direction
     *     recorded, which leaves the slot to the caseworker
     * @return the accepted slots, trimmed, with absent optional slots simply not present
     * @throws InvalidInputError with one entry per offending field, every failure at once
     */
    public static Map<EmailSlot, String> parse(
        EmailTemplate template, Map<String, Object> raw, String directionsText) {

        Map<String, Object> body = raw == null ? Map.of() : raw;
        List<FieldError> errors = new ArrayList<>();

        refuseKeysThatAreNotSlots(template, body, errors);

        Map<EmailSlot, String> accepted = new EnumMap<>(EmailSlot.class);
        for (EmailSlot slot : template.slots()) {
            value(slot, body, directionsText, errors).ifPresent(text -> accepted.put(slot, text));
        }

        if (!errors.isEmpty()) {
            throw new InvalidInputError(INVALID_CONTENT, errors);
        }
        return accepted;
    }

    /**
     * Names every key the caller sent that this template does not take as a slot.
     *
     * <p>A resolved key gets its own message, because "that is not a field you may set" and "that is not a
     * field at all" are different mistakes: the first is a client reaching for something real, and telling it
     * why it cannot have it is what stops the attempt being repeated.
     */
    private static void refuseKeysThatAreNotSlots(
        EmailTemplate template, Map<String, Object> body, List<FieldError> errors) {

        for (String key : body.keySet()) {
            if (template.slot(key).isPresent()) {
                continue;
            }
            if (template.resolves(key)) {
                errors.add(new FieldError(key, key + " is resolved from the case and cannot be set by the"
                    + " caller. Only " + slots(template) + " may be supplied"));
            } else {
                errors.add(new FieldError(key, quoted(key) + " is not a slot of " + template.key()
                    + ". One of " + slots(template)));
            }
        }
    }

    /**
     * One slot's value: what the caller sent, or the direction, or nothing.
     *
     * <p>Blank becomes absent rather than an empty string, as it does for a referral's notes, and here that is
     * what makes the required rule mean anything: {@code judges_reason: "   "} must not satisfy it, because an
     * email quoting a judge as having said nothing reads as a quotation until somebody opens it.
     */
    private static Optional<String> value(
        EmailSlot slot, Map<String, Object> body, String directionsText, List<FieldError> errors) {

        Object sent = body.get(slot.placeholder());
        if (sent != null && !(sent instanceof String)) {
            errors.add(new FieldError(slot.placeholder(), slot.placeholder() + " must be text"));
            return Optional.empty();
        }

        String supplied = sent == null ? null : ((String) sent).trim();
        if (supplied != null && !supplied.isEmpty()) {
            return bounded(slot, supplied, false, errors);
        }

        // Nothing usable from the caller, so fall back to the direction for the slot that has one. Read
        // unaltered: it was already trimmed and bounded when the judge recorded it, and re-trimming a stored
        // value would make this the second place that decides what a direction says.
        if (slot == EmailSlot.JUDGES_REASON && directionsText != null && !directionsText.isBlank()) {
            return bounded(slot, directionsText, true, errors);
        }

        if (slot.required()) {
            errors.add(new FieldError(slot.placeholder(), slot.placeholder()
                + " is required: this email quotes the judge, and there is no direction recorded to"
                + " pre-populate it from"));
        }
        return Optional.empty();
    }

    /**
     * The value if it fits the slot, or empty with the reason recorded.
     *
     * @param prePopulated whether the value came from the direction rather than from the caller, which changes
     *     only the message -- a caseworker who typed nothing needs to be told why the pre-filled text is the
     *     problem
     */
    private static Optional<String> bounded(
        EmailSlot slot, String text, boolean prePopulated, List<FieldError> errors) {

        if (text.length() <= slot.maxLength()) {
            return Optional.of(text);
        }
        errors.add(new FieldError(slot.placeholder(), prePopulated
            ? slot.placeholder() + " is pre-populated from the judge's direction, which is longer than the "
                + slot.maxLength() + " characters this email can carry. Shorten it before sending"
            : slot.placeholder() + " must be " + slot.maxLength() + " characters or fewer"));
        return Optional.empty();
    }

    /** The slots this template takes, listed in a refusal so a client does not have to hold the enum. */
    private static String slots(EmailTemplate template) {
        return template.slots().stream().map(EmailSlot::placeholder).reduce((one, two) -> one + ", " + two)
            .orElse("no slots at all");
    }

    /** A caller's key, quoted and truncated, safe to put in a message. */
    private static String quoted(String token) {
        int maxQuoted = 60;
        String bounded = token.length() <= maxQuoted ? token : token.substring(0, maxQuoted) + "…";
        return "'" + bounded + "'";
    }
}
