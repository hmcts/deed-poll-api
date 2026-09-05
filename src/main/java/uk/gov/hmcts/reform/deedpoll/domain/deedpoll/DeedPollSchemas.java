package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MaritalStatus;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Nationality;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NationalitySection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.PostCounter;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Privacy;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferToMaster;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Shape and format validation for a create payload and an update patch.
 *
 * <p>Ported from {@code reference/ts/deed-poll/schemas.ts} and the primitives it builds on
 * ({@code reference/ts/schema-primitives.ts}). Every field is optional -- a case is logged incrementally,
 * mirroring the Dynamics form where only a few fields are mandatory at create and the rest are filled
 * through the lifecycle.
 *
 * <p>Type-conditional mandatoriness is NOT enforced here. That lives in {@link DeedPollValidation},
 * which knows which fields a given Type demands; this stays a pure shape and format guard, exactly as
 * the schema it replaces did.
 *
 * <p>{@code referencenumber} and {@code displayReference} are intentionally absent -- they are allocated
 * by {@link ReferenceNumbers}. {@code state} is accepted only for seeding and import; normally it is
 * derived by {@link CaseState} from the populated dates.
 *
 * <p>Two behaviours of the reference's primitives are load-bearing and reproduced exactly:
 *
 * <ul>
 *   <li>Unknown keys are stripped rather than rejected, which is what the prototype's whitelisting
 *       validation pipe did. {@link DeedPollPatch#of} is where that happens.</li>
 *   <li>A field the caller omits and a field the caller explicitly clears are both accepted where the
 *       column is nullable ({@code .nullish()}), and null is REFUSED where the column is not
 *       ({@code .optional()}) -- the two enum defaults, the three multiselects and the four booleans.
 *       Better a validation error than a database error with less to say.</li>
 * </ul>
 *
 * <p>The reference offers {@code parse} (throws) and {@code safeParse} (returns a result). Only the
 * throwing form is ported: {@link InvalidInputError} already carries the per-field failures a
 * {@code safeParse} caller would inspect, and the ported tests assert the throw where the reference
 * asserts {@code success === false}.
 */
public final class DeedPollSchemas {

    /**
     * An ISO 8601 calendar date, matching {@code z.iso.date()}.
     */
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /**
     * The reference's 1000-character cap on the six name fields, from {@code nvarchar(1000)}.
     */
    private static final int NAME_MAX_LENGTH = 1000;

    /** Every accepted field, in the reference schema's declaration order, with its rule. */
    private static final Map<String, Rule> SCHEMA = schema();

    private DeedPollSchemas() {
    }

    /** What a field accepts. */
    private enum Kind {
        TEXT,
        ENUM,
        BOOLEAN,
        INT,
        DECIMAL,
        ISO_DATE_STRING,
        OPTION_SET
    }

    /**
     * One field's rule: what it accepts, whether null is one of the things it accepts, and any cap.
     *
     * <p>{@code nullable} is the reference's {@code .nullish()} versus {@code .optional()} distinction,
     * and it is about the COLUMN, not about the caller: a column with a database default has no legal
     * null, so accepting one here would only move the failure later.
     */
    private record Rule(Kind kind, boolean nullable, int maxLength, Class<? extends Enum<?>> enumType) {
    }

    /**
     * Validates a create payload.
     *
     * @throws InvalidInputError with one entry per offending field
     */
    public static DeedPollPatch parseCreate(Map<String, Object> raw) {
        return parse(raw);
    }

    /**
     * Validates an update patch.
     *
     * <p>The reference declares this as {@code createDeedPollSchema.partial()}. Every field of the create
     * schema is already optional, so the two are the same rules applied to whatever is present -- and
     * this method exists to say so rather than to leave a caller wondering which to use.
     *
     * @throws InvalidInputError with one entry per offending field
     */
    public static DeedPollPatch parseUpdate(Map<String, Object> raw) {
        return parse(raw);
    }

    private static DeedPollPatch parse(Map<String, Object> raw) {
        Map<String, Object> accepted = new LinkedHashMap<>();
        List<FieldError> errors = new ArrayList<>();

        raw.forEach((field, value) -> {
            Rule rule = SCHEMA.get(field);
            if (rule == null) {
                // Stripped, not reported. A caller sending displayReference is not making an error the
                // form can render against an input -- the field simply is not theirs to set.
                return;
            }
            if (value == null) {
                if (rule.nullable()) {
                    accepted.put(field, null);
                } else {
                    errors.add(new FieldError(field, field + " cannot be null"));
                }
                return;
            }
            try {
                accepted.put(field, coerce(field, value, rule));
            } catch (FieldRejected rejected) {
                errors.add(new FieldError(field, rejected.getMessage()));
            }
        });

        if (!errors.isEmpty()) {
            throw new InvalidInputError("Case payload is not valid", errors);
        }
        return DeedPollPatch.of(accepted);
    }

    private static Object coerce(String field, Object value, Rule rule) {
        return switch (rule.kind()) {
            case TEXT -> text(field, value, rule.maxLength());
            case ENUM -> enumMember(field, value, rule.enumType());
            case BOOLEAN -> requireType(field, value, Boolean.class, "must be true or false");
            case INT -> integer(field, value);
            case DECIMAL -> decimal(field, value);
            case ISO_DATE_STRING -> isoDateString(field, value);
            case OPTION_SET -> optionSet(field, value);
        };
    }

    private static String text(String field, Object value, int maxLength) {
        String string = requireType(field, value, String.class, "must be text");
        if (maxLength > 0 && string.length() > maxLength) {
            throw new FieldRejected(field + " must be " + maxLength + " characters or fewer");
        }
        return string;
    }

    private static Enum<?> enumMember(String field, Object value, Class<? extends Enum<?>> type) {
        if (type.isInstance(value)) {
            return (Enum<?>) value;
        }
        if (value instanceof String name) {
            for (Enum<?> member : type.getEnumConstants()) {
                if (member.name().equals(name)) {
                    return member;
                }
            }
        }
        throw new FieldRejected(field + " is not one of the accepted values");
    }

    /**
     * A whole number, matching {@code z.int()}.
     *
     * <p>A fractional value is refused rather than truncated: the reference refuses {@code 7.5} for
     * {@code ageOfMinor}, and an age silently becoming 7 is a worse answer than a validation error.
     */
    private static Integer integer(String field, Object value) {
        if (value instanceof Integer number) {
            return number;
        }
        if (value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())) {
            return number.intValue();
        }
        throw new FieldRejected(field + " must be a whole number");
    }

    private static BigDecimal decimal(String field, Object value) {
        if (value instanceof BigDecimal amount) {
            return amount;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        throw new FieldRejected(field + " must be a number");
    }

    /**
     * An ISO 8601 date or date-time string, matching what the reference's {@code isoDateString} accepts.
     *
     * <p>Dates stay strings through validation on purpose: {@link DeedPollPatch#coerceDates} turns them
     * into date-times immediately before they are read as dates, and the forms submit
     * {@code YYYY-MM-DD}. A bare date, a UTC or offset date-time, and a local date-time without a zone
     * are all accepted.
     */
    private static String isoDateString(String field, Object value) {
        if (value instanceof LocalDateTime dateTime) {
            // Already a date -- an internal caller, or a patch coerced before validation. Kept rather
            // than refused, and rendered in the same text the caller would have had to send.
            return dateTime.toString();
        }
        String text = requireType(field, value, String.class, "must be an ISO 8601 date");
        if (ISO_DATE.matcher(text).matches()) {
            try {
                LocalDate.parse(text);
                return text;
            } catch (DateTimeParseException notADate) {
                throw new FieldRejected(field + " must be an ISO 8601 date");
            }
        }
        if (text.contains("T") && (parses(text, true) || parses(text, false))) {
            return text;
        }
        throw new FieldRejected(field + " must be an ISO 8601 date");
    }

    private static boolean parses(String text, boolean withOffset) {
        try {
            if (withOffset) {
                OffsetDateTime.parse(text);
            } else {
                LocalDateTime.parse(text);
            }
            return true;
        } catch (DateTimeParseException notADateTime) {
            return false;
        }
    }

    /**
     * A multiselect option set, matching {@code z.array(z.int())}.
     *
     * <p>The elements must be whole numbers, not text: the values are Dynamics option-set integers, and
     * the reference refuses {@code ["1"]} so a client cannot get away with sending a form-shaped payload
     * to the JSON API.
     */
    private static List<Integer> optionSet(String field, Object value) {
        Collection<?> raw;
        if (value instanceof Collection<?> collection) {
            raw = collection;
        } else if (value instanceof Object[] array) {
            raw = List.of(array);
        } else {
            throw new FieldRejected(field + " must be a list of whole numbers");
        }
        List<Integer> numbers = new ArrayList<>();
        for (Object element : raw) {
            if (!(element instanceof Integer number)) {
                throw new FieldRejected(field + " must be a list of whole numbers");
            }
            numbers.add(number);
        }
        return List.copyOf(numbers);
    }

    private static <T> T requireType(String field, Object value, Class<T> type, String requirement) {
        if (!type.isInstance(value)) {
            throw new FieldRejected(field + " " + requirement);
        }
        return type.cast(value);
    }

    private static Map<String, Rule> schema() {
        Map<String, Rule> schema = new LinkedHashMap<>();

        // --- General / classification ---
        schema.put("type", nullishEnum(DeedPollType.class));
        schema.put("privacy", requiredEnum(Privacy.class));
        schema.put("urgency", requiredEnum(Urgency.class));
        schema.put("urgencyreason", optionalText());
        schema.put("postCounter", nullishEnum(PostCounter.class));
        schema.put("inPerson", nullish(Kind.BOOLEAN));
        schema.put("migratedRecord", notNull(Kind.BOOLEAN));
        schema.put("retainPermanently", notNull(Kind.BOOLEAN));
        schema.put("maritalStatus", nullishEnum(MaritalStatus.class));
        schema.put("feeReference", optionalText());
        schema.put("onlineApplicationReference", optionalText());

        // --- Applicant name (nvarchar 1000 in the source) ---
        schema.put("newForename", optionalTextMax(NAME_MAX_LENGTH));
        schema.put("newMiddleName", optionalTextMax(NAME_MAX_LENGTH));
        schema.put("newSurname", optionalTextMax(NAME_MAX_LENGTH));
        schema.put("oldForename", optionalTextMax(NAME_MAX_LENGTH));
        schema.put("oldMiddleName", optionalTextMax(NAME_MAX_LENGTH));
        schema.put("oldSurname", optionalTextMax(NAME_MAX_LENGTH));

        // --- Applicant address and contact ---
        schema.put("applicantBuildingNoName", optionalText());
        schema.put("applicantEmail", optionalText());
        schema.put("applicantTelephone", optionalText());
        schema.put("applicantAddress1", optionalText());
        schema.put("applicantAddress2", optionalText());
        schema.put("applicantTownCity", optionalText());
        schema.put("applicantPostcode", optionalText());

        // --- Representative address and contact ---
        schema.put("representativeName", optionalText());
        schema.put("representativeBuildingNoName", optionalText());
        schema.put("representativeCompany", optionalText());
        schema.put("representativeAddress1", optionalText());
        schema.put("representativeEmail", optionalText());
        schema.put("representativeAddress2", optionalText());
        schema.put("representativeTelephone", optionalText());
        schema.put("representativeTownCity", optionalText());
        schema.put("representativePostcode", optionalText());

        // --- Minor ---
        schema.put("ageOfMinor", nullish(Kind.INT));
        schema.put("ageOfMinorMonths", nullish(Kind.INT));
        schema.put("minorDateOfBirth", nullish(Kind.ISO_DATE_STRING));
        schema.put("parents", optionalText());

        // --- Nationality ---
        schema.put("nationality", nullishEnum(Nationality.class));
        schema.put("nationalitySection", nullishEnum(NationalitySection.class));
        schema.put("nationalitySectionOther", optionalText());

        // --- Declarations (multiselects, Dynamics integer values preserved) ---
        schema.put("iShall", notNull(Kind.OPTION_SET));
        schema.put("iHaveSubscribed", notNull(Kind.OPTION_SET));
        schema.put("andAlsoMyOld", notNull(Kind.OPTION_SET));
        schema.put("dateOfDeclaration", nullish(Kind.ISO_DATE_STRING));

        // --- Dates driving state ---
        schema.put("dateOfApplication", nullish(Kind.ISO_DATE_STRING));
        schema.put("dateReceived", nullish(Kind.ISO_DATE_STRING));
        schema.put("awaitingFurtherDocsInfo", nullish(Kind.ISO_DATE_STRING));
        schema.put("signatureRequestedDate", nullish(Kind.ISO_DATE_STRING));
        schema.put("signatureReceivedDate", nullish(Kind.ISO_DATE_STRING));
        schema.put("dateReadyForMaster", nullish(Kind.ISO_DATE_STRING));
        schema.put("dateReferred", nullish(Kind.ISO_DATE_STRING));
        schema.put("dateEnroled", nullish(Kind.ISO_DATE_STRING));
        schema.put("dateReturned", nullish(Kind.ISO_DATE_STRING));
        schema.put("dateNoticeSentToLondonGazette", nullish(Kind.ISO_DATE_STRING));

        // --- Master / referral ---
        schema.put("referToMasterChoice", nullishEnum(ReferToMaster.class));
        schema.put("masterName", optionalText());
        schema.put("masterDirections", optionalText());
        schema.put("masterDecision", nullishEnum(MasterDecision.class));
        schema.put("masterDateOfDecision", nullish(Kind.ISO_DATE_STRING));
        schema.put("dateDecisionDirectionsReceived", nullish(Kind.ISO_DATE_STRING));
        schema.put("referralNotes", optionalText());
        schema.put("publishToLondonGazette", notNull(Kind.BOOLEAN));

        // --- Return / refund ---
        schema.put("returnReason", optionalText());
        schema.put("refundAmount", nullish(Kind.DECIMAL));
        schema.put("dateOfRefund", nullish(Kind.ISO_DATE_STRING));
        schema.put("refundReference", optionalText());

        schema.put("state", nullishEnum(DeedPollState.class));

        return Collections.unmodifiableMap(schema);
    }

    private static Rule nullish(Kind kind) {
        return new Rule(kind, true, 0, null);
    }

    private static Rule notNull(Kind kind) {
        return new Rule(kind, false, 0, null);
    }

    private static Rule optionalText() {
        return new Rule(Kind.TEXT, true, 0, null);
    }

    private static Rule optionalTextMax(int maxLength) {
        return new Rule(Kind.TEXT, true, maxLength, null);
    }

    private static Rule nullishEnum(Class<? extends Enum<?>> type) {
        return new Rule(Kind.ENUM, true, 0, type);
    }

    private static Rule requiredEnum(Class<? extends Enum<?>> type) {
        return new Rule(Kind.ENUM, false, 0, type);
    }

    /**
     * One field's rejection, collected by {@link #parse} rather than thrown at the caller.
     */
    private static final class FieldRejected extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private FieldRejected(String message) {
            super(message);
        }
    }
}
