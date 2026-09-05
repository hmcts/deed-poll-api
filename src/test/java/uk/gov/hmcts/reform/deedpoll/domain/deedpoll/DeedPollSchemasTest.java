package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MaritalStatus;

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
}
