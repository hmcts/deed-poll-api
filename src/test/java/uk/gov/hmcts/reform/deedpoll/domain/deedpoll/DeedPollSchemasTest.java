package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MaritalStatus;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ports every test block in {@code reference/ts/deed-poll/schemas.test.ts}.
 *
 * <p>The reference's {@code safeParse(...).success === false} assertions become "throws
 * {@link InvalidInputError}" here: only the throwing form is ported, because the thrown error already
 * carries the per-field failures a {@code safeParse} caller would have inspected.
 */
class DeedPollSchemasTest {

    private static Map<String, Object> payload(Object... keysAndValues) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            raw.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return raw;
    }

    private static void rejects(Map<String, Object> raw) {
        assertThatThrownBy(() -> DeedPollSchemas.parseCreate(raw)).isInstanceOf(InvalidInputError.class);
    }

    @Nested
    @DisplayName("createDeedPollSchema")
    class CreateSchema {

        @Test
        @DisplayName("accepts an empty payload, because a case is logged incrementally")
        void acceptsAnEmptyPayload() {
            assertThat(DeedPollSchemas.parseCreate(Map.of()).values()).isEmpty();
        }

        @Test
        @DisplayName("accepts enum members and rejects anything outside the option set")
        void acceptsEnumMembersAndRejectsAnythingElse() {
            assertThat(DeedPollSchemas.parseCreate(payload("type", DeedPollType.Minor)).get("type"))
                .isEqualTo(DeedPollType.Minor);
            rejects(payload("type", "NotAType"));
            assertThat(DeedPollSchemas.parseCreate(payload("maritalStatus", MaritalStatus.Single))
                .get("maritalStatus")).isEqualTo(MaritalStatus.Single);
        }

        @Test
        @DisplayName("enforces the source's 1000-character limit on name fields")
        void enforcesTheThousandCharacterLimit() {
            assertThat(DeedPollSchemas.parseCreate(payload("newForename", "a".repeat(1000))).get("newForename"))
                .isEqualTo("a".repeat(1000));
            rejects(payload("newForename", "a".repeat(1001)));
        }

        @Test
        @DisplayName("keeps dates as ISO strings, accepting bare dates and date-times alike")
        void keepsDatesAsIsoStrings() {
            // The domain coerces these to date-times immediately before the write; the forms submit
            // YYYY-MM-DD while the API tends to send a full timestamp, so both must pass.
            List<String> accepted = List.of(
                "2026-03-01",
                "2026-03-01T09:30:00Z",
                "2026-03-01T09:30:00+01:00",
                "2026-03-01T09:30:00");
            for (String value : accepted) {
                assertThat(DeedPollSchemas.parseCreate(payload("dateReceived", value)).get("dateReceived"))
                    .isEqualTo(value);
            }
        }

        @Test
        @DisplayName("rejects a malformed date")
        void rejectsAMalformedDate() {
            rejects(payload("dateReceived", "01/03/2026"));
            rejects(payload("dateReceived", "not-a-date"));
        }

        @Test
        @DisplayName("rejects a well-shaped date that is not a real one, as a 400 rather than a 500")
        void rejectsACalendarImpossibleDate() {
            // Passes the shape check and then fails to parse, which is the one branch inside the ISO-date
            // arm: without the catch there the exception escapes as a 500 instead of a 400 naming the field.
            rejects(payload("dateReceived", "2026-02-30"));
            rejects(payload("dateReceived", "2026-13-01"));
        }

        @Test
        @DisplayName("accepts booleans and rejects the strings a form-shaped payload would send")
        void acceptsBooleansAndRejectsTheirTextForm() {
            assertThat(DeedPollSchemas.parseCreate(payload("inPerson", true)).get("inPerson")).isEqualTo(true);
            assertThat(DeedPollSchemas.parseCreate(payload("migratedRecord", false)).get("migratedRecord"))
                .isEqualTo(false);
            // The refusal KeyedRequestBody's javadoc promises: the JSON API is not the form, so "yes" and 1
            // are refused with the field named rather than coerced into a flag nobody chose.
            rejects(payload("migratedRecord", "yes"));
            rejects(payload("retainPermanently", 1));
        }

        @Test
        @DisplayName("accepts a whole number for an age, and a decimal amount for the refund")
        void acceptsNumbers() {
            assertThat(DeedPollSchemas.parseCreate(payload("ageOfMinor", 7)).get("ageOfMinor")).isEqualTo(7);
            assertThat(DeedPollSchemas.parseCreate(payload("refundAmount", new BigDecimal("12.50")))
                .get("refundAmount")).isEqualTo(new BigDecimal("12.50"));
            // A JSON number binds to Double, which the decimal rule converts rather than refusing.
            assertThat(DeedPollSchemas.parseCreate(payload("refundAmount", 12.5d)).get("refundAmount"))
                .isEqualTo(new BigDecimal("12.5"));
            rejects(payload("refundAmount", "12.50"));
        }

        @Test
        @DisplayName("rejects a whole number too large for the column rather than silently wrapping it")
        void rejectsAnOutOfRangeWholeNumber() {
            // Jackson binds an untyped integer beyond int range to a Long, and 1e20 to a Double. Both are
            // whole, so a wholeness check alone would accept them and intValue() would then store 0 and
            // 2147483647 respectively -- the same silent wrong answer as 7.5 becoming 7.
            rejects(payload("ageOfMinor", 4294967296L));
            rejects(payload("ageOfMinor", -4294967296L));
            rejects(payload("ageOfMinor", 1e20d));
        }

        @Test
        @DisplayName("rejects a non-finite amount as a field error rather than letting it escape as a 500")
        void rejectsANonFiniteAmount() {
            // Jackson binds an untyped JSON float to a Double, so `{"refundAmount": 1e400}` -- and anything
            // above 1e308 -- arrives as Double.POSITIVE_INFINITY. BigDecimal has no representation for it, so
            // the conversion used to raise a NumberFormatException, which is not a field rejection and so was
            // not collected: the caller got a 500 with nothing naming the field.
            assertThat(Double.isFinite(Double.parseDouble("1e400"))).isFalse();
            rejects(payload("refundAmount", Double.parseDouble("1e400")));
            rejects(payload("refundAmount", Double.POSITIVE_INFINITY));
            rejects(payload("refundAmount", Double.NEGATIVE_INFINITY));
            rejects(payload("refundAmount", Double.NaN));
        }

        @Test
        @DisplayName("rejects an amount too large for numeric(12,2), where the database would have been the guard")
        void rejectsAnOutOfRangeAmount() {
            // Finite, so the finiteness check passes it, and then numeric(12,2) cannot hold it -- a Postgres
            // numeric field overflow, which is a 500 one layer later rather than a 400 naming refundAmount.
            // Ten integral digits is the column's limit.
            rejects(payload("refundAmount", 1e300d));
            rejects(payload("refundAmount", new BigDecimal("10000000000")));
            rejects(payload("refundAmount", new BigDecimal("-10000000000")));

            // Within range unrounded, out of range once stored: Postgres rounds to scale 2 and THEN checks
            // precision, so this carries into an eleventh integral digit and overflows. Checking the value
            // as it arrived would let it through to the 500 this whole check exists to prevent.
            rejects(payload("refundAmount", new BigDecimal("9999999999.999")));
            rejects(payload("refundAmount", new BigDecimal("-9999999999.999")));
            rejects(payload("refundAmount", Double.parseDouble("9999999999.999")));

            // The boundary on the accepting side, so the limit is pinned from both directions.
            assertThat(DeedPollSchemas.parseCreate(payload("refundAmount", new BigDecimal("9999999999.99")))
                .get("refundAmount")).isEqualTo(new BigDecimal("9999999999.99"));

            // A long fraction that rounds DOWN is still fine -- the rounding, not the digit count, is what
            // decides it.
            assertThat(DeedPollSchemas.parseCreate(payload("refundAmount", new BigDecimal("9999999999.994")))
                .get("refundAmount")).isEqualTo(new BigDecimal("9999999999.994"));
        }

        @Test
        @DisplayName("accepts null on a nullable field, so a PATCH can clear it")
        void acceptsNullOnANullableField() {
            DeedPollPatch patch = DeedPollSchemas.parseCreate(payload("dateReceived", null, "masterName", null));
            assertThat(patch.has("dateReceived")).isTrue();
            assertThat(patch.get("dateReceived")).isNull();
            assertThat(patch.has("masterName")).isTrue();
            assertThat(patch.get("masterName")).isNull();
        }

        @Test
        @DisplayName("rejects null where the column is not nullable, rather than letting the database fail later")
        void rejectsNullWhereTheColumnIsNotNullable() {
            // privacy, urgency, the three multiselects and the booleans all have database defaults, so NULL
            // is not a legal value for them -- better a validation error than a database error.
            rejects(payload("privacy", null));
            rejects(payload("iShall", null));
            rejects(payload("publishToLondonGazette", null));
        }

        @Test
        @DisplayName("accepts multiselects as integer lists, preserving the Dynamics values")
        void acceptsMultiselectsAsIntegerLists() {
            assertThat(DeedPollSchemas.parseCreate(payload("iShall", List.of(1, 4))).get("iShall"))
                .isEqualTo(List.of(1, 4));
            rejects(payload("iShall", List.of("1")));
            rejects(payload("ageOfMinor", 7.5));
        }

        @Test
        @DisplayName("strips unknown keys, as the whitelisting validation pipe did")
        void stripsUnknownKeys() {
            DeedPollPatch patch =
                DeedPollSchemas.parseCreate(payload("newForename", "Ada", "displayReference", "KBDP2026-00001"));

            // displayReference is allocated by ReferenceNumbers and must not be settable by a caller.
            assertThat(patch.values()).containsExactly(Map.entry("newForename", "Ada"));
        }
    }

    @Nested
    @DisplayName("updateDeedPollSchema")
    class UpdateSchema {

        @Test
        @DisplayName("accepts an empty patch")
        void acceptsAnEmptyPatch() {
            assertThat(DeedPollSchemas.parseUpdate(Map.of()).values()).isEmpty();
        }

        @Test
        @DisplayName("applies the same format rules to the fields that are present")
        void appliesTheSameFormatRules() {
            assertThatThrownBy(() -> DeedPollSchemas.parseUpdate(payload("newForename", "a".repeat(1001))))
                .isInstanceOf(InvalidInputError.class);
            assertThatThrownBy(() -> DeedPollSchemas.parseUpdate(payload("type", "NotAType")))
                .isInstanceOf(InvalidInputError.class);
        }
    }

    /**
     * The 2,000-character cap on the three narrative fields. <strong>Not a port</strong>: admin enforces it in its
     * edit form rather than its domain schema, so the vendored reference still says {@code optionalText}.
     */
    @Nested
    @DisplayName("narrative fields (api's own cap, matching admin's edit form)")
    class NarrativeFields {

        private static final List<String> NARRATIVE = List.of("returnReason", "referralNotes", "masterDirections");

        @Test
        @DisplayName("accept 2,000 characters on create and update")
        void acceptTwoThousandCharacters() {
            for (String field : NARRATIVE) {
                String atTheCap = "a".repeat(2000);
                assertThat(DeedPollSchemas.parseCreate(payload(field, atTheCap)).get(field)).isEqualTo(atTheCap);
                assertThat(DeedPollSchemas.parseUpdate(payload(field, atTheCap)).get(field)).isEqualTo(atTheCap);
            }
        }

        @Test
        @DisplayName("refuse 2,001 characters on create and update, attributed to the field")
        void refuseTwoThousandAndOne() {
            for (String field : NARRATIVE) {
                String overTheCap = "a".repeat(2001);
                assertThatThrownBy(() -> DeedPollSchemas.parseCreate(payload(field, overTheCap)))
                    .isInstanceOfSatisfying(InvalidInputError.class, error -> assertThat(error.errors())
                        .singleElement()
                        .satisfies(rejected -> {
                            assertThat(rejected.field()).isEqualTo(field);
                            assertThat(rejected.message()).isEqualTo(field + " must be 2000 characters or fewer");
                        }));
                assertThatThrownBy(() -> DeedPollSchemas.parseUpdate(payload(field, overTheCap)))
                    .isInstanceOf(InvalidInputError.class);
            }
        }

        @Test
        @DisplayName("count a CRLF or a lone CR as one character, as admin and a browser do, and keep the text as sent")
        void countALineBreakOnce() {
            // 1,990 letters and ten line breaks: 2,000 to a browser, 2,010 UTF-16 units as CRLF on the wire.
            String crlf = ("a".repeat(199) + "\r\n").repeat(10);
            String cr = ("a".repeat(199) + "\r").repeat(10);
            String lf = ("a".repeat(199) + "\n").repeat(10);
            for (String field : NARRATIVE) {
                for (String text : List.of(crlf, cr, lf)) {
                    assertThat(DeedPollSchemas.parseCreate(payload(field, text)).get(field)).isEqualTo(text);
                }
                rejects(payload(field, crlf + "a"));
                rejects(payload(field, cr + "a"));
            }
        }

        @Test
        @DisplayName("still clear on an explicit null")
        void stillClearOnNull() {
            for (String field : NARRATIVE) {
                DeedPollPatch patch = DeedPollSchemas.parseUpdate(payload(field, null));
                assertThat(patch.values()).containsEntry(field, null);
            }
        }

        @Test
        @DisplayName("publish the cap and the line-break rule, and no other field gains the rule")
        void publishTheCap() {
            Map<String, DeedPollSchemas.FieldSchema> fields = DeedPollSchemas.writeBodyFields();
            for (String field : NARRATIVE) {
                assertThat(fields.get(field).maxLength()).isEqualTo(DeedPollSchemas.NARRATIVE_MAX_LENGTH);
                assertThat(fields.get(field).lineBreaksCountOnce()).isTrue();
            }
            assertThat(fields.entrySet().stream()
                .filter(entry -> entry.getValue().lineBreaksCountOnce())
                .map(Map.Entry::getKey))
                .containsExactlyInAnyOrderElementsOf(NARRATIVE);
            assertThat(fields.get("masterName").maxLength()).as("a neighbouring text field keeps no cap").isZero();
        }
    }
}
