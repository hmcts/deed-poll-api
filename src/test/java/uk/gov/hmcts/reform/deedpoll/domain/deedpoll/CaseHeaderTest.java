package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ports every test block in {@code reference/ts/deed-poll/case-header.test.ts} -- the five header facts
 * and the GOV.UK timestamp, including its British Summer Time behaviour.
 */
class CaseHeaderTest {

    /** Flattens the header rows to key = value, as the reference's own helper does. */
    private static Map<String, String> flatten(CaseHeader header) {
        Map<String, String> flat = new LinkedHashMap<>();
        header.rows().forEach(row -> flat.put(row.key(), row.value()));
        return flat;
    }

    /** The reference's `record` fixture. */
    private static DeedPoll record() {
        DeedPoll record = new DeedPoll();
        record.setType(DeedPollType.Adult);
        record.setState(DeedPollState.Received);
        record.setCreatedBy("Bence Luo");
        record.setModifiedBy("# svc_HMCTS_LCPT_Dev");
        record.setModifiedOn(LocalDateTime.parse("2026-05-13T08:44:00"));
        return record;
    }

    @Nested
    @DisplayName("formatTimestamp")
    class FormatTimestamp {

        @Test
        @DisplayName("formats a timestamp GOV.UK style with a lowercase meridiem")
        void formatsGovUkStyleWithALowercaseMeridiem() {
            // 09:44 UTC in May is 10:44 British Summer Time.
            assertThat(CaseHeader.formatTimestamp(LocalDateTime.parse("2026-05-13T09:44:00")))
                .isEqualTo("13 May 2026 at 10:44am");
        }

        @Test
        @DisplayName("renders British Summer Time, not UTC")
        void rendersBritishSummerTimeNotUtc() {
            // The regression this guards: a save made at 09:44 local BST is stored as 08:44Z and must read
            // back as 9:44am, not 8:44am.
            assertThat(CaseHeader.formatTimestamp(LocalDateTime.parse("2026-05-13T08:44:00")))
                .isEqualTo("13 May 2026 at 9:44am");
        }

        @Test
        @DisplayName("renders GMT correctly outside summer time")
        void rendersGmtCorrectlyOutsideSummerTime() {
            assertThat(CaseHeader.formatTimestamp(LocalDateTime.parse("2026-01-13T09:44:00")))
                .isEqualTo("13 January 2026 at 9:44am");
        }

        @Test
        @DisplayName("formats an afternoon time as pm")
        void formatsAnAfternoonTimeAsPm() {
            assertThat(CaseHeader.formatTimestamp(LocalDateTime.parse("2026-01-13T15:05:00")))
                .isEqualTo("13 January 2026 at 3:05pm");
        }

        @Test
        @DisplayName("accepts an ISO string as well as a date")
        void acceptsAnIsoStringAsWellAsADate() {
            assertThat(CaseHeader.formatTimestamp("2026-01-13T15:05:00Z"))
                .isEqualTo("13 January 2026 at 3:05pm");
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "not a date"})
        @DisplayName("renders an absent or unreadable value as the placeholder")
        void rendersAnUnreadableValueAsThePlaceholder(String raw) {
            // The reference also covers `undefined`, which Java does not distinguish from null.
            assertThat(CaseHeader.formatTimestamp(raw)).isEqualTo(CaseHeader.NOT_RECORDED);
        }
    }

    @Nested
    @DisplayName("buildCaseHeader")
    class BuildCaseHeader {

        @Test
        @DisplayName("reproduces every field from the Dynamics header")
        void reproducesEveryFieldFromTheDynamicsHeader() {
            assertThat(flatten(CaseHeader.buildCaseHeader(record()))).containsExactly(
                Map.entry("Type", "Adult"),
                Map.entry("State", "Received"),
                Map.entry("Modified By", "# svc_HMCTS_LCPT_Dev"),
                Map.entry("Modified On", "13 May 2026 at 9:44am"),
                Map.entry("Created By", "Bence Luo"));
        }

        @Test
        @DisplayName("captions the record with the entity name")
        void captionsTheRecordWithTheEntityName() {
            assertThat(CaseHeader.buildCaseHeader(record()).entityLabel()).isEqualTo(CaseHeader.ENTITY_LABEL);
        }

        @Test
        @DisplayName("marks only State as a tag")
        void marksOnlyStateAsATag() {
            assertThat(CaseHeader.buildCaseHeader(record()).rows().stream()
                .filter(CaseHeader.CaseHeaderRow::tag)
                .map(CaseHeader.CaseHeaderRow::key)
                .toList())
                .containsExactly("State");
        }

        @Test
        @DisplayName("labels enums rather than leaking member names")
        void labelsEnumsRatherThanLeakingMemberNames() {
            DeedPoll record = record();
            record.setType(DeedPollType.AdultTransgender);
            record.setState(DeedPollState.AwaitingFurtherDocs);

            Map<String, String> header = flatten(CaseHeader.buildCaseHeader(record));
            assertThat(header.get("Type")).isEqualTo("Adult Transgender");
            assertThat(header.get("State")).isEqualTo("Awaiting Further Docs/Info");
        }

        @Test
        @DisplayName("renders a row predating the audit columns as Not recorded")
        void rendersARowPredatingTheAuditColumnsAsNotRecorded() {
            // The backward-compatibility case: rows created before createdBy/modifiedBy existed have null
            // in both, and must not render "null" or an empty cell.
            DeedPoll record = record();
            record.setCreatedBy(null);
            record.setModifiedBy(null);

            Map<String, String> header = flatten(CaseHeader.buildCaseHeader(record));
            assertThat(header.get("Created By")).isEqualTo(CaseHeader.NOT_RECORDED);
            assertThat(header.get("Modified By")).isEqualTo(CaseHeader.NOT_RECORDED);
        }

        @Test
        @DisplayName("treats a whitespace-only author as unrecorded")
        void treatsAWhitespaceOnlyAuthorAsUnrecorded() {
            DeedPoll record = record();
            record.setCreatedBy("   ");

            assertThat(flatten(CaseHeader.buildCaseHeader(record)).get("Created By"))
                .isEqualTo(CaseHeader.NOT_RECORDED);
        }

        @Test
        @DisplayName("keeps all five rows even when nothing is populated")
        void keepsAllFiveRowsEvenWhenNothingIsPopulated() {
            // The header is a fixed set of facts: rows must not appear and disappear per case the way the
            // tab sections' hidden fields do.
            CaseHeader header = CaseHeader.buildCaseHeader(new DeedPoll());
            assertThat(header.rows()).extracting(CaseHeader.CaseHeaderRow::key)
                .containsExactly("Type", "State", "Modified By", "Modified On", "Created By");
            assertThat(header.rows()).allMatch(row -> CaseHeader.NOT_RECORDED.equals(row.value()));
        }
    }
}
