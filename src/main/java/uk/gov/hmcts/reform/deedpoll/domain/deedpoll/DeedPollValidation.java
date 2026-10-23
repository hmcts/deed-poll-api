package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MaritalStatus;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferToMaster;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-type field rules, the write-path gates and request-time validation.
 *
 * <p>Ported from {@code reference/ts/deed-poll/deed-poll-validation.ts}, which folds seven
 * conditional XAML workflows into one validator plus a {@code getFieldRules(type)} the form consumes
 * for show / hide / mandate. Sources ({@code Workflows/*.xaml}):
 *
 * <ul>
 *   <li>{@code Makefieldsrequiredornon-requireddependingonTypeand…} -- the per-type required set</li>
 *   <li>{@code ShowhideandmandatefieldsforMinors} -- the minor fields</li>
 *   <li>{@code ShowhideandmandatefieldsforDeedofRelinquishment} -- old name hidden, and more</li>
 *   <li>{@code ShowandmandateUrgencyReason} -- {@code urgencyreason} gated on urgency = Urgent</li>
 *   <li>{@code ShowOnlineApplicationreferenceandmandate} -- retired after AAT staff feedback;
 *       existing references remain optional for correspondence</li>
 *   <li>{@code ShoworhideDateNoticeSenttoLondonGazette} -- gated on publishtolondongazette</li>
 *   <li>{@code DefaultMinorDeedPolltoSingle} -- a create default</li>
 * </ul>
 *
 * <p>The three {@code apply…} methods return a patch to merge into the write and never mutate their
 * input, as the reference's do. That is deliberate rather than incidental: each is a rule about what
 * the record must become, and returning the change makes it reviewable next to the rule it enforces
 * instead of hidden in a setter call.
 */
public final class DeedPollValidation {

    /** The fields whose visibility and mandatoriness vary by Type. */
    private static final List<String> RULED_FIELDS = List.of(
        "inPerson",
        "maritalStatus",
        "ageOfMinor",
        "ageOfMinorMonths",
        "parents",
        "minorDateOfBirth",
        "urgency",
        "nationality",
        "nationalitySection",
        "nationalitySectionOther",
        "oldForename",
        "oldMiddleName",
        "oldSurname",
        "dateNoticeSentToLondonGazette");

    /**
     * Fields whose mandatoriness the source relaxes to optional when Migrated Record = Yes,
     * regardless of Type (source rule "Make fields required or non-required depending on Type and
     * Migrated Record", the migrated branch).
     */
    private static final List<String> MIGRATED_RELAXED_FIELDS = List.of(
        "inPerson",
        "maritalStatus",
        "ageOfMinor",
        "ageOfMinorMonths",
        "parents",
        "urgency",
        "nationality",
        "nationalitySection");

    /**
     * The four fields the source's "Show hide and mandate fields for Minors" workflow shows only for
     * Type = Minor (3) or Minor Transgender (4).
     *
     * <p>Its else branch hides ALL FOUR -- including Minor's Date of Birth, which this list previously
     * omitted in the reference, leaving that one field stranded on an Adult case's General card.
     */
    private static final List<String> MINOR_ONLY_FIELDS =
        List.of("minorDateOfBirth", "ageOfMinor", "ageOfMinorMonths", "parents");

    /** The fields a relinquishment hides beyond the minor ones. */
    private static final List<String> RELINQUISHMENT_HIDDEN_FIELDS = List.of(
        "oldForename",
        "oldMiddleName",
        "oldSurname",
        "maritalStatus",
        "nationality",
        "nationalitySection",
        "nationalitySectionOther",
        "dateNoticeSentToLondonGazette");

    private static final Set<DeedPollType> MINOR_TYPES =
        EnumSet.of(DeedPollType.Minor, DeedPollType.MinorTransgender);

    private static final Set<DeedPollType> ADULT_TYPES =
        EnumSet.of(DeedPollType.Adult, DeedPollType.AdultTransgender);

    private DeedPollValidation() {
    }

    /** Field rules for a type, with nothing migrated and the Gazette field shown. */
    public static Map<String, FieldRule> getFieldRules(DeedPollType type) {
        return getFieldRules(type, false, true);
    }

    /** Field rules for a type, with the Gazette field shown. */
    public static Map<String, FieldRule> getFieldRules(DeedPollType type, boolean migrated) {
        return getFieldRules(type, migrated, true);
    }

    /**
     * Per-type field-rule matrix.
     *
     * <p>Adult / Minor / Relinquishment each get a distinct required and visible set; Unknown (6) and
     * {@code null} default to everything optional and visible (the source leaves them untouched).
     *
     * <p>{@code migrated} mirrors the source rule's Migrated Record branch: when the record was
     * migrated from the previous Deed Poll system, the whole per-type required set relaxes to optional
     * (In Person, Marital Status, Age of Minor Years/Months, Parents, Urgency, Nationality, Section).
     * Visibility is unaffected -- only mandatoriness relaxes -- so the form still hides the fields the
     * type does not use.
     */
    public static Map<String, FieldRule> getFieldRules(DeedPollType type,
                                                       boolean migrated,
                                                       boolean publishToGazette) {
        Map<String, FieldRule> rules = new LinkedHashMap<>();
        for (String field : RULED_FIELDS) {
            rules.put(field, new FieldRule(true, false));
        }

        if (isAdult(type)) {
            // Adult / Adult Transgender: identity plus classification required, no minor fields.
            setRequired(rules, "inPerson", true);
            setRequired(rules, "maritalStatus", true);
            setRequired(rules, "urgency", true);
            setRequired(rules, "nationality", true);
            setRequired(rules, "nationalitySection", true);
            MINOR_ONLY_FIELDS.forEach(field -> setVisible(rules, field, false));
        } else if (isMinor(type)) {
            // Minor / Minor Transgender: everything the adult needs plus the minor fields.
            setRequired(rules, "inPerson", true);
            setRequired(rules, "maritalStatus", true);
            setRequired(rules, "ageOfMinor", true);
            setRequired(rules, "ageOfMinorMonths", true);
            setRequired(rules, "parents", true);
            setRequired(rules, "urgency", true);
            setRequired(rules, "nationality", true);
            setRequired(rules, "nationalitySection", true);
        } else if (isRelinquishment(type)) {
            // Deed of Relinquishment: only In Person and Urgency required; old-name and
            // marital/nationality/section fields hidden; Gazette date hidden. The minor fields are
            // hidden too -- the Minors workflow's condition is "Type = Minor or Minor Transgender", so
            // its else branch covers relinquishment as much as it covers Adult.
            setRequired(rules, "inPerson", true);
            setRequired(rules, "urgency", true);
            RELINQUISHMENT_HIDDEN_FIELDS.forEach(field -> setVisible(rules, field, false));
            MINOR_ONLY_FIELDS.forEach(field -> setVisible(rules, field, false));
        }

        // Migrated Record = Yes relaxes the entire per-type required set to optional (source rule
        // "Make fields required or non-required depending on Type and Migrated Record"). Applied last
        // so it overrides whatever the type branch set; visibility is left untouched.
        if (migrated) {
            MIGRATED_RELAXED_FIELDS.forEach(field -> setRequired(rules, field, false));
        }

        // "Show or hide Date Notice Sent to London Gazette": when Publish to London Gazette = No, the
        // field is hidden -- for EVERY type, not just relinquishment (the relinquishment branch above
        // forces publish = No, so this is consistent with it). The matching CLEAR of a stored value
        // happens on the write path in applyGazetteGate().
        if (!publishToGazette) {
            setVisible(rules, "dateNoticeSentToLondonGazette", false);
        }

        return Collections.unmodifiableMap(rules);
    }

    /**
     * Write-path half of "Show or hide Date Notice Sent to London Gazette".
     *
     * <p>When Publish to London Gazette = No, clear any stored notice date. Returns a patch to merge
     * into the write; an empty patch when publish is Yes or unset, so an existing date is left
     * untouched.
     */
    public static Map<String, Object> applyGazetteGate(ValidationInput input) {
        if (Boolean.FALSE.equals(input.publishToLondonGazette())) {
            return nulls("dateNoticeSentToLondonGazette");
        }
        return Map.of();
    }

    /**
     * Write-path half of "Show hide and mandate fields for Minors".
     *
     * <p>When Type is not Minor or Minor Transgender, the source's else branch clears all four minor
     * fields as well as hiding them (its Clear Age of Minor Years / Months / Date of Birth / Parents
     * steps). Without this, converting a Minor case to Adult leaves orphaned minor data in the row --
     * invisible on every page but still stored.
     *
     * <p>Returns a patch to merge into the write. It clears only for the types whose rules HIDE the
     * fields -- Adult, Adult Transgender and Relinquishment -- so hiding and clearing can never
     * disagree. Two cases are deliberately left alone:
     *
     * <ul>
     *   <li>No type resolved (absent or null): a patch that does not settle the type must not be read
     *       as "not a minor" and wipe the fields.</li>
     *   <li>Unknown: the source leaves this type untouched throughout the port ({@code getFieldRules}
     *       imposes nothing for it), so clearing here would delete data the same type still
     *       displays.</li>
     * </ul>
     *
     * <p>The type must come from the MERGED record on update, not the raw patch, so that a patch
     * touching only, say, {@code parents} does not clear what it just set.
     */
    public static Map<String, Object> applyMinorGate(ValidationInput input) {
        boolean hidesMinorFields = isAdult(input.type()) || isRelinquishment(input.type());
        if (!hidesMinorFields) {
            return Map.of();
        }
        return nulls("minorDateOfBirth", "ageOfMinor", "ageOfMinorMonths", "parents");
    }

    /**
     * Create-time defaults ported from the workflows.
     *
     * <ul>
     *   <li>{@code DefaultMinorDeedPolltoSingle}: minor type and no marital status gives Single.</li>
     *   <li>Relinquishment: refer-to-master defaults Yes, publish-to-Gazette false.</li>
     * </ul>
     *
     * <p>Returns a patch to merge into the create payload; never mutates the input.
     */
    public static Map<String, Object> applyCreateDefaults(ValidationInput input) {
        Map<String, Object> patch = new LinkedHashMap<>();
        if (isMinor(input.type()) && input.maritalStatus() == null) {
            patch.put("maritalStatus", MaritalStatus.Single);
        }
        if (isRelinquishment(input.type())) {
            if (input.referToMasterChoice() == null) {
                patch.put("referToMasterChoice", ReferToMaster.Yes);
            }
            if (input.publishToLondonGazette() == null) {
                patch.put("publishToLondonGazette", false);
            }
        }
        return Collections.unmodifiableMap(patch);
    }

    /**
     * Request-time validation of a (fully-merged) record.
     *
     * <p>Returns the list of violations -- empty when valid. The caller decides whether to hard-fail
     * (create / submit) or treat as advisory (an incremental save).
     *
     * <p>Combines the per-type required matrix ({@link #getFieldRules}) with the urgency-reason gate.
     * The inherited online-reference requirement is intentionally retired; the vendored source
     * remains unchanged as historical evidence.
     *
     * <p>The reference returns its own {@code ValidationError {field, message}}; this returns
     * {@link FieldError}, for the reason given on {@link CaseState}.
     */
    public static List<FieldError> validate(ValidationInput input) {
        List<FieldError> errors = new ArrayList<>();
        Map<String, FieldRule> rules = getFieldRules(input.type(), input.migratedRecord());

        // Type-driven required fields, only when the type is known -- Unknown and null impose nothing,
        // matching the source, which leaves those untouched.
        if (input.type() != null) {
            rules.forEach((field, rule) -> {
                if (rule.required() && rule.visible() && isEmpty(input.get(field))) {
                    errors.add(new FieldError(field, field + " is required"));
                }
            });
        }

        // Conditional gate: urgency reason.
        if (input.urgency() == Urgency.Urgent && isEmpty(input.get("urgencyreason"))) {
            errors.add(new FieldError(
                "urgencyreason",
                "Urgency reason is required when urgency is Urgent"));
        }

        return List.copyOf(errors);
    }

    /** Whether a type is Minor or Minor Transgender. */
    public static boolean isMinor(DeedPollType type) {
        return type != null && MINOR_TYPES.contains(type);
    }

    /** Whether a type is Adult or Adult Transgender. */
    public static boolean isAdult(DeedPollType type) {
        return type != null && ADULT_TYPES.contains(type);
    }

    /** Whether a type is Deed of Relinquishment. */
    public static boolean isRelinquishment(DeedPollType type) {
        return type == DeedPollType.DeedOfRelinquishment;
    }

    /**
     * A patch clearing the named fields.
     *
     * <p>Built by hand rather than with {@code Map.of}, which refuses null values -- and a null value
     * is the whole content of a clearing patch.
     */
    private static Map<String, Object> nulls(String... fields) {
        Map<String, Object> patch = new LinkedHashMap<>();
        for (String field : fields) {
            patch.put(field, null);
        }
        return Collections.unmodifiableMap(patch);
    }

    private static void setRequired(Map<String, FieldRule> rules, String field, boolean required) {
        rules.computeIfPresent(field, (name, rule) -> rule.withRequired(required));
    }

    private static void setVisible(Map<String, FieldRule> rules, String field, boolean visible) {
        rules.computeIfPresent(field, (name, rule) -> rule.withVisible(visible));
    }

    private static boolean isEmpty(Object value) {
        return value == null || (value instanceof String text && text.isBlank());
    }
}
