package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import lombok.extern.slf4j.Slf4j;
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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
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
 *
 * <p><strong>One rule is not a port.</strong> {@code returnReason}, {@code referralNotes} and
 * {@code masterDirections} are capped at {@link #NARRATIVE_MAX_LENGTH}, which the reference does not do: admin
 * enforces that cap in its edit form ({@code TEXTAREA_MAX_LENGTH} in {@code form-input.ts}), not in the domain
 * schema vendored here, and api matches it so that no other client can store more than the form allows. Admin
 * measures a textarea as a browser does, with each line break counted once, and then submits it with CRLF line
 * breaks, so these three fields count a CRLF or a lone CR as one character; measured raw, text admin accepted
 * would be refused here and the caseworker shown a generic failure rather than a field error. A stored value
 * over the cap is untouched until a write carries the field.
 */
@Slf4j
public final class DeedPollSchemas {

    /**
     * An ISO 8601 calendar date, matching {@code z.iso.date()}.
     */
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** A CRLF or a lone CR, each read as one LF when a narrative field is measured. */
    private static final Pattern LINE_BREAK = Pattern.compile("\\r\\n?");

    /**
     * The reference's 1000-character cap on the six name fields, from {@code nvarchar(1000)}.
     */
    private static final int NAME_MAX_LENGTH = 1000;

    /**
     * Api's own cap on the three narrative fields, matching admin's edit form -- not the reference's. Measured
     * with each line break counted once; see the class doc.
     */
    public static final int NARRATIVE_MAX_LENGTH = 2000;

    /**
     * One past the widest amount a {@code numeric(12, 2)} column holds -- ten integral digits.
     */
    private static final BigDecimal DECIMAL_LIMIT = new BigDecimal("10000000000");

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
    private record Rule(
        Kind kind, boolean nullable, int maxLength, boolean lineBreaksCountOnce, Class<? extends Enum<?>> enumType) {
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

    /** The JSON type a write body's field accepts. */
    public enum JsonType {
        STRING,
        BOOLEAN,
        INTEGER,
        NUMBER,
        /** An ISO 8601 date or date-time, carried as a string. */
        DATE,
        /** A Dynamics multiselect, carried as an array of option-set values. */
        INTEGER_ARRAY
    }

    /**
     * One field of a case write body, as something outside the domain needs to describe it.
     *
     * @param type          what the field accepts
     * @param nullable      whether an explicit null is accepted, which clears the column
     * @param maxLength     the cap on a text field, or 0 for none
     * @param lineBreaksCountOnce whether the cap counts a CRLF or a lone CR as one character
     * @param allowedValues the accepted names for an enum field, empty for any other kind
     */
    public record FieldSchema(
        JsonType type, boolean nullable, int maxLength, boolean lineBreaksCountOnce, List<String> allowedValues) {
    }

    /**
     * Every field a case write body may carry, in the reference schema's declaration order.
     *
     * <p>Exposed for one caller: the published OpenAPI document, which describes the create and patch bodies
     * from this table rather than from a list kept alongside it. The bodies bind as a keyed map (see
     * {@code KeyedRequestBody}, and the three-state argument for why they must), so springdoc has no fields to
     * reflect over and would otherwise publish {@code {}} -- a request-body type with no properties, which tells
     * a consumer generating a client nothing at all. Deriving the description from the same table that does the
     * validating is what stops the document and the service disagreeing later.
     *
     * @return field name to what that field accepts, unmodifiable
     */
    public static Map<String, FieldSchema> writeBodyFields() {
        Map<String, FieldSchema> described = new LinkedHashMap<>();
        SCHEMA.forEach((field, rule) -> described.put(field, describe(rule)));
        return Collections.unmodifiableMap(described);
    }

    private static FieldSchema describe(Rule rule) {
        List<String> allowed = rule.enumType() == null
            ? List.of()
            : Arrays.stream(rule.enumType().getEnumConstants()).map(Enum::name).toList();
        return new FieldSchema(
            jsonType(rule.kind()), rule.nullable(), rule.maxLength(), rule.lineBreaksCountOnce(), allowed);
    }

    private static JsonType jsonType(Kind kind) {
        return switch (kind) {
            // An enum is a string that happens to be constrained, and its members travel in allowedValues.
            case TEXT, ENUM -> JsonType.STRING;
            case BOOLEAN -> JsonType.BOOLEAN;
            case INT -> JsonType.INTEGER;
            case DECIMAL -> JsonType.NUMBER;
            case ISO_DATE_STRING -> JsonType.DATE;
            case OPTION_SET -> JsonType.INTEGER_ARRAY;
        };
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
            } catch (RuntimeException failed) {
                // Fail closed. Every coercion above refuses a value it does not accept by raising
                // FieldRejected, so arriving here means a coercion itself is wrong rather than the value --
                // but letting it escape would answer 500, lose the other fields' errors with it, and tell the
                // caller nothing about which field was at fault. Logged so the coercion bug stays visible.
                log.warn("Coercing field {} failed unexpectedly; reported as a field error", field, failed);
                errors.add(new FieldError(field, field + " is not valid"));
            }
        });

        if (!errors.isEmpty()) {
            throw new InvalidInputError("Case payload is not valid", errors);
        }
        return DeedPollPatch.of(accepted);
    }

    private static Object coerce(String field, Object value, Rule rule) {
        return switch (rule.kind()) {
            case TEXT -> text(field, value, rule);
            case ENUM -> enumMember(field, value, rule.enumType());
            case BOOLEAN -> requireType(field, value, Boolean.class, "must be true or false");
            case INT -> integer(field, value);
            case DECIMAL -> decimal(field, value);
            case ISO_DATE_STRING -> isoDateString(field, value);
            case OPTION_SET -> optionSet(field, value);
        };
    }

    private static String text(String field, Object value, Rule rule) {
        String string = requireType(field, value, String.class, "must be text");
        int length = rule.lineBreaksCountOnce() ? LINE_BREAK.matcher(string).replaceAll("\n").length()
            : string.length();
        if (rule.maxLength() > 0 && length > rule.maxLength()) {
            throw new FieldRejected(field + " must be " + rule.maxLength() + " characters or fewer");
        }
        // Stored as sent: the folding is only how the text is measured.
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
     *
     * <p><strong>The range is checked as well as the wholeness</strong>, and by the same argument. Jackson
     * binds an untyped JSON integer too large for an {@code int} to a {@code Long} (or a
     * {@code BigInteger}), and {@code 1e20} to a {@code Double} -- all of which are whole, so wholeness
     * alone would accept them and {@code intValue()} would then wrap or saturate. {@code ageOfMinor} of
     * {@code 4294967296} stored as {@code 0} is the same silent wrong answer as {@code 7.5} stored as
     * {@code 7}, reached by a different route.
     */
    private static Integer integer(String field, Object value) {
        if (value instanceof Integer number) {
            return number;
        }
        if (value instanceof Number number
            && number.doubleValue() == Math.rint(number.doubleValue())
            && number.doubleValue() >= Integer.MIN_VALUE
            && number.doubleValue() <= Integer.MAX_VALUE) {
            return number.intValue();
        }
        throw new FieldRejected(field + " must be a whole number");
    }

    /**
     * A decimal amount, matching {@code z.number()} over a {@code numeric(12, 2)} column.
     *
     * <p><strong>Guarded the same two ways {@link #integer} is, and by the same argument.</strong> Jackson
     * binds an untyped JSON float to a {@code Double}, so {@code 1e400} arrives as
     * {@code Double.POSITIVE_INFINITY} and {@code new BigDecimal("Infinity")} raises a
     * {@code NumberFormatException} -- which is not a {@link FieldRejected}, so it would escape the error
     * collection in {@link #parse} entirely and answer 500 without naming the field. A finite but oversized
     * {@code 1e300} gets further and overflows the column instead, which is the same 500 one layer later.
     * Both are refused here, where the field's name is still known and the caller can be told which one it
     * was.
     */
    private static BigDecimal decimal(String field, Object value) {
        if (value instanceof BigDecimal amount) {
            return inRange(field, amount);
        }
        // Non-finite before the general Number arm: Infinity and NaN are Numbers, and BigDecimal has no
        // representation for either.
        if (value instanceof Double number && !Double.isFinite(number)) {
            throw new FieldRejected(field + " must be a number");
        }
        if (value instanceof Float number && !Float.isFinite(number)) {
            throw new FieldRejected(field + " must be a number");
        }
        if (value instanceof Number number) {
            return inRange(field, new BigDecimal(number.toString()));
        }
        throw new FieldRejected(field + " must be a number");
    }

    /**
     * Refuses an amount the {@code numeric(12, 2)} column cannot hold.
     *
     * <p>Precision minus scale is ten integral digits, so the column's range is
     * {@code (-10^10, 10^10)}. A long fraction is not itself an error -- Postgres rounds to the column's
     * scale rather than refusing -- but it is rounded BEFORE the precision is checked, so the rounding
     * can carry into an eleventh integral digit: {@code 9999999999.999} becomes {@code 10000000000.00}
     * and overflows. The value is therefore compared as the column will store it, not as it arrived,
     * because a near-miss on the boundary is otherwise the same numeric field overflow -- and so the
     * same uninformative 500 -- that this check exists to turn into a 400 naming the field.
     */
    private static BigDecimal inRange(String field, BigDecimal amount) {
        if (amount.setScale(2, RoundingMode.HALF_UP).abs().compareTo(DECIMAL_LIMIT) >= 0) {
            throw new FieldRejected(field + " must be less than " + DECIMAL_LIMIT.toPlainString());
        }
        return amount;
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
        schema.put("masterDirections", narrativeText());
        schema.put("masterDecision", nullishEnum(MasterDecision.class));
        schema.put("masterDateOfDecision", nullish(Kind.ISO_DATE_STRING));
        schema.put("dateDecisionDirectionsReceived", nullish(Kind.ISO_DATE_STRING));
        schema.put("referralNotes", narrativeText());
        schema.put("publishToLondonGazette", notNull(Kind.BOOLEAN));

        // --- Return / refund ---
        schema.put("returnReason", narrativeText());
        schema.put("refundAmount", nullish(Kind.DECIMAL));
        schema.put("dateOfRefund", nullish(Kind.ISO_DATE_STRING));
        schema.put("refundReference", optionalText());

        schema.put("state", nullishEnum(DeedPollState.class));

        return Collections.unmodifiableMap(schema);
    }

    private static Rule nullish(Kind kind) {
        return new Rule(kind, true, 0, false, null);
    }

    private static Rule notNull(Kind kind) {
        return new Rule(kind, false, 0, false, null);
    }

    private static Rule optionalText() {
        return new Rule(Kind.TEXT, true, 0, false, null);
    }

    private static Rule optionalTextMax(int maxLength) {
        return new Rule(Kind.TEXT, true, maxLength, false, null);
    }

    /**
     * Api's own rule, not the reference's: see {@link #NARRATIVE_MAX_LENGTH}.
     */
    private static Rule narrativeText() {
        return new Rule(Kind.TEXT, true, NARRATIVE_MAX_LENGTH, true, null);
    }

    private static Rule nullishEnum(Class<? extends Enum<?>> type) {
        return new Rule(Kind.ENUM, true, 0, false, type);
    }

    private static Rule requiredEnum(Class<? extends Enum<?>> type) {
        return new Rule(Kind.ENUM, false, 0, false, type);
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
