package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.ListView.CaseListRow;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Privacy;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ports every test block in {@code reference/ts/deed-poll/list-view.test.ts}.
 */
class ListViewTest {

    /** The reference's `record` fixture. */
    private static DeedPoll record() {
        DeedPoll record = new DeedPoll();
        record.setId("case-1");
        record.setDisplayReference("KBDP2026-00001");
        record.setPrivacy(Privacy.Normal);
        record.setDateReceived(LocalDateTime.parse("2026-03-01T00:00:00"));
        record.setType(DeedPollType.Adult);
        record.setState(DeedPollState.Received);
        record.setNewForename("Ada");
        return record;
    }

    private static DeedPoll record(Consumer<DeedPoll> change) {
        DeedPoll record = record();
        change.accept(record);
        return record;
    }

    private static CaseListRow rowFor(DeedPoll record) {
        return ListView.buildCaseList(List.of(record)).get(0);
    }

    @Test
    @DisplayName("formats a date as GOV.UK style, not ISO")
    void formatsADateAsGovUkStyle() {
        assertThat(rowFor(record()).dateReceived()).isEqualTo("1 March 2026");
    }

    @Test
    @DisplayName("formats dates in UTC, so a case does not appear received a day early")
    void formatsDatesInUtc() {
        assertThat(rowFor(record(r -> r.setDateReceived(LocalDateTime.parse("2026-01-01T00:00:00")))).dateReceived())
            .isEqualTo("1 January 2026");
    }

    @Test
    @DisplayName("labels enums with their human text rather than the enum member")
    void labelsEnumsWithTheirHumanText() {
        CaseListRow row = rowFor(record(r -> {
            r.setType(DeedPollType.DeedOfRelinquishment);
            r.setState(DeedPollState.AwaitingFurtherDocs);
        }));

        assertThat(row.type()).isEqualTo("Deed of Relinquishment");
        assertThat(row.state()).isEqualTo("Awaiting Further Docs/Info");
    }

    @Test
    @DisplayName("uses the same labels the detail page uses, by going through the shared field metadata")
    void usesTheSameLabelsTheDetailPageUses() {
        // A second formatter here would be a second opinion about what a Type is called. Asserted against
        // formatValue directly so the two cannot diverge without this failing.
        DeedPoll record = record();
        CaseListRow row = rowFor(record);

        assertThat(row.type())
            .isEqualTo(DetailView.formatValue(FormFields.FIELD_BY_NAME.get("type"), record.getType()));
        assertThat(row.state())
            .isEqualTo(DetailView.formatValue(FormFields.FIELD_BY_NAME.get("state"), record.getState()));
        assertThat(row.privacy())
            .isEqualTo(DetailView.formatValue(FormFields.FIELD_BY_NAME.get("privacy"), record.getPrivacy()));
    }

    @Test
    @DisplayName("keeps the reference verbatim rather than reformatting a display string")
    void keepsTheReferenceVerbatim() {
        assertThat(rowFor(record()).reference()).isEqualTo("KBDP2026-00001");
    }

    @Test
    @DisplayName("shows the placeholder for a case with no reference, which the schema permits")
    void showsThePlaceholderForACaseWithNoReference() {
        // createDeedPoll always allocates one, but displayReference is nullable -- a seeded or imported row
        // need not have it, and a reference-less case showing as such is the honest rendering.
        CaseListRow row = rowFor(record(r -> r.setDisplayReference(null)));

        assertThat(row.reference()).isEqualTo(DetailView.NOT_PROVIDED);
        assertThat(row.id()).isEqualTo("case-1");
    }

    @Test
    @DisplayName("flags a private case separately from its label, so colour is not the only signal")
    void flagsAPrivateCaseSeparatelyFromItsLabel() {
        CaseListRow priv = rowFor(record(r -> r.setPrivacy(Privacy.Private)));
        CaseListRow norm = rowFor(record());

        assertThat(priv.isPrivate()).isTrue();
        assertThat(priv.privacy()).isNotEmpty();
        assertThat(norm.isPrivate()).isFalse();
    }

    @Test
    @DisplayName("shows the placeholder for an unset date rather than an empty cell")
    void showsThePlaceholderForAnUnsetDate() {
        assertThat(rowFor(record(r -> r.setDateReceived(null))).dateReceived()).isEqualTo(DetailView.NOT_PROVIDED);
    }

    @Test
    @DisplayName("shows the placeholder for an unset forename")
    void showsThePlaceholderForAnUnsetForename() {
        assertThat(rowFor(record(r -> r.setNewForename(null))).newForename()).isEqualTo(DetailView.NOT_PROVIDED);
    }

    @Test
    @DisplayName("carries the id so the reference can link to the case")
    void carriesTheIdSoTheReferenceCanLink() {
        assertThat(rowFor(record()).id()).isEqualTo("case-1");
    }

    @Test
    @DisplayName("returns an empty list for no cases, so the page renders its own empty state")
    void returnsAnEmptyListForNoCases() {
        assertThat(ListView.buildCaseList(List.of())).isEmpty();
    }

    @Test
    @DisplayName("preserves the order it was given, since the domain decides the sort")
    void preservesTheOrderItWasGiven() {
        DeedPoll second = record(r -> {
            r.setId("case-2");
            r.setDisplayReference("KBDP2026-00002");
        });

        assertThat(ListView.buildCaseList(List.of(record(), second)))
            .extracting(CaseListRow::id)
            .containsExactly("case-1", "case-2");
    }

    @Test
    @DisplayName("has a heading for every cell in a row")
    void hasAHeadingForEveryCellInARow() {
        // Guards the pairing the class comment claims: headings and cells are defined together, so a column
        // added to one must be added to the other. The reference counts the row object's keys; a record's
        // components are the same statement about its shape.
        List<String> cells = Arrays.stream(CaseListRow.class.getRecordComponents())
            .map(RecordComponent::getName)
            .filter(name -> !"id".equals(name) && !"isPrivate".equals(name))
            .toList();

        assertThat(cells).hasSameSizeAs(ListView.CASE_LIST_COLUMNS);
    }

    @Test
    @DisplayName("resolves every column name it formats against the shared field metadata")
    void resolvesEveryColumnNameAgainstTheSharedFieldMetadata() {
        // If a name were absent, format() would fall back to a plain string and quietly lose the label or
        // date formatting for that column.
        for (String name : List.of("privacy", "dateReceived", "type", "state", "newForename")) {
            assertThat(FormFields.FIELD_BY_NAME)
                .withFailMessage("%s is not on any form tab", name)
                .containsKey(name);
        }
    }
}
