package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.EditView.EditContext;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.EditView.EditField;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.EditView.EditSection;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.EditView.SummaryEntry;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ports every test block in {@code reference/ts/deed-poll/edit-view.test.ts}.
 */
class EditViewTest {

    private static final EditContext ADMIN = new EditContext(true);
    private static final EditContext CASEWORKER = new EditContext(false);

    /** The Application tab, which carries privacy and type -- the two fields with special rules. */
    private static final TabDef APPLICATION_TAB = FormFields.FORM_TABS.get(0);

    private static Map<String, Object> record(Object... keysAndValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return values;
    }

    private static List<EditField> fieldsOf(EditView view) {
        return view.sections().stream().flatMap(section -> section.fields().stream()).toList();
    }

    private static Optional<EditField> field(EditView view, String name) {
        return fieldsOf(view).stream().filter(candidate -> name.equals(candidate.name())).findFirst();
    }

    /** The first tab carrying a date field, and that field -- the reference finds both the same way. */
    private static TabDef tabWithDateField(boolean editableOnly) {
        return FormFields.FORM_TABS.stream()
            .filter(tab -> tab.sections().stream()
                .flatMap(section -> section.fields().stream())
                .anyMatch(candidate -> candidate.kind() == FieldKind.DATE && (!editableOnly || !candidate.readOnly())))
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected at least one date field in FORM_TABS"));
    }

    private static FieldDef dateFieldOf(TabDef tab, boolean editableOnly) {
        return tab.sections().stream()
            .flatMap(section -> section.fields().stream())
            .filter(candidate -> candidate.kind() == FieldKind.DATE && (!editableOnly || !candidate.readOnly()))
            .findFirst()
            .orElseThrow();
    }

    @Nested
    @DisplayName("tabFor")
    class TabFor {

        @Test
        @DisplayName("resolves a known tab id")
        void resolvesAKnownTabId() {
            String id = FormFields.FORM_TABS.get(1).id();
            assertThat(EditView.tabFor(id).id()).isEqualTo(id);
        }

        @Test
        @DisplayName("falls back to the first tab for an unrecognised id, since the id comes from a URL")
        void fallsBackToTheFirstTab() {
            // The reference also covers `undefined`, which Java does not distinguish from null.
            assertThat(EditView.tabFor("nonsense").id()).isEqualTo(FormFields.FORM_TABS.get(0).id());
            assertThat(EditView.tabFor(null).id()).isEqualTo(FormFields.FORM_TABS.get(0).id());
        }
    }

    @Nested
    @DisplayName("buildEditView")
    class BuildEditView {

        @Test
        @DisplayName("decorates every field with a value taken from the record")
        void decoratesEveryFieldWithAValueFromTheRecord() {
            EditView view = EditView.buildEditView(
                record("type", DeedPollType.Adult, "newSurname", "Lovelace"),
                APPLICATION_TAB,
                ADMIN);

            assertThat(field(view, "newSurname")).map(EditField::value).contains("Lovelace");
        }

        @Test
        @DisplayName("uses null rather than a sentinel for an absent value, so the template has one empty case")
        void usesNullForAnAbsentValue() {
            // The reference asserts no value is `undefined`; Java has one absence, so the assertion is that
            // every field is decorated at all and an absent one reads as null rather than as a placeholder.
            EditView view = EditView.buildEditView(record(), APPLICATION_TAB, ADMIN);

            assertThat(fieldsOf(view)).isNotEmpty();
            assertThat(fieldsOf(view)).allMatch(candidate -> candidate.value() == null);
        }

        @Test
        @DisplayName("attaches enum options only to enum fields")
        void attachesEnumOptionsOnlyToEnumFields() {
            EditView view = EditView.buildEditView(record("type", DeedPollType.Adult), APPLICATION_TAB, ADMIN);

            for (EditField candidate : fieldsOf(view)) {
                if (candidate.enumSet() != null) {
                    assertThat(candidate.options()).isNotEmpty();
                } else {
                    assertThat(candidate.options()).isEmpty();
                }
            }
        }

        @Test
        @DisplayName("attaches date parts only to date fields")
        void attachesDatePartsOnlyToDateFields() {
            EditView view = EditView.buildEditView(record("type", DeedPollType.Adult), APPLICATION_TAB, ADMIN);

            for (EditField candidate : fieldsOf(view)) {
                if (candidate.kind() == FieldKind.DATE) {
                    assertThat(candidate.dateParts()).isNotNull();
                } else {
                    assertThat(candidate.dateParts()).isNull();
                }
            }
        }

        @Test
        @DisplayName("splits a stored date into parts for pre-filling")
        void splitsAStoredDateIntoParts() {
            TabDef tab = tabWithDateField(false);
            FieldDef dateField = dateFieldOf(tab, false);

            EditView view = EditView.buildEditView(
                record(dateField.name(), "2026-03-01T00:00:00.000Z"),
                tab,
                ADMIN);

            assertThat(field(view, dateField.name())).map(EditField::dateParts)
                .contains(new DateParts("1", "3", "2026"));
        }

        @Test
        @DisplayName("locks privacy for a non-admin on an existing record")
        void locksPrivacyForANonAdmin() {
            EditView view = EditView.buildEditView(record("type", DeedPollType.Adult), APPLICATION_TAB, CASEWORKER);

            assertThat(field(view, "privacy")).map(EditField::locked).contains(true);
        }

        @Test
        @DisplayName("does not lock privacy for an admin")
        void doesNotLockPrivacyForAnAdmin() {
            EditView view = EditView.buildEditView(record("type", DeedPollType.Adult), APPLICATION_TAB, ADMIN);

            assertThat(field(view, "privacy")).map(EditField::locked).contains(false);
        }

        @Test
        @DisplayName("locks nothing except privacy, which is the only role-gated field")
        void locksNothingExceptPrivacy() {
            EditView view = EditView.buildEditView(record("type", DeedPollType.Adult), APPLICATION_TAB, CASEWORKER);

            assertThat(fieldsOf(view))
                .filteredOn(candidate -> !"privacy".equals(candidate.name()))
                .allMatch(candidate -> !candidate.locked());
        }

        @Test
        @DisplayName("carries the full tab list so the form can render its own navigation")
        void carriesTheFullTabList() {
            EditView view = EditView.buildEditView(record(), APPLICATION_TAB, ADMIN);

            assertThat(view.tabs()).extracting(EditView.TabRef::id)
                .isEqualTo(FormFields.FORM_TABS.stream().map(TabDef::id).toList());
        }

        @Nested
        @DisplayName("replaying a rejected submission")
        class ReplayingARejectedSubmission {

            @Test
            @DisplayName("shows what was submitted rather than what is stored")
            void showsWhatWasSubmitted() {
                // Showing stored values would discard the caseworker's work and leave them looking at an
                // error against a field that now appears fine.
                EditView view = EditView.buildEditView(
                    record("newSurname", "Stored"),
                    APPLICATION_TAB,
                    ADMIN,
                    record("newSurname", "Typed"));

                assertThat(field(view, "newSurname")).map(EditField::value).contains("Typed");
            }

            @Test
            @DisplayName("keeps the offending date parts verbatim, not normalised")
            void keepsTheOffendingDatePartsVerbatim() {
                TabDef tab = tabWithDateField(true);
                FieldDef dateField = dateFieldOf(tab, true);

                EditView view = EditView.buildEditView(
                    record(dateField.name(), "2026-03-01T00:00:00.000Z"),
                    tab,
                    ADMIN,
                    record(
                        dateField.name() + "-day", "99",
                        dateField.name() + "-month", "13",
                        dateField.name() + "-year", "abcd"));

                assertThat(field(view, dateField.name())).map(EditField::dateParts)
                    .contains(new DateParts("99", "13", "abcd"));
            }

            @Test
            @DisplayName("still reads read-only fields from the record, since the body cannot speak for them")
            void stillReadsReadOnlyFieldsFromTheRecord() {
                TabDef tab = FormFields.FORM_TABS.stream()
                    .filter(candidate -> candidate.sections().stream()
                        .flatMap(section -> section.fields().stream())
                        .anyMatch(FieldDef::readOnly))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected a read-only field in FORM_TABS"));
                FieldDef readOnly = tab.sections().stream()
                    .flatMap(section -> section.fields().stream())
                    .filter(FieldDef::readOnly)
                    .findFirst()
                    .orElseThrow();

                EditView view = EditView.buildEditView(
                    record(readOnly.name(), "Stored"),
                    tab,
                    ADMIN,
                    record(readOnly.name(), "Injected"));

                assertThat(field(view, readOnly.name())).map(EditField::value).contains("Stored");
            }

            @Test
            @DisplayName("treats an empty body as a replay, not as absent")
            void treatsAnEmptyBodyAsAReplay() {
                // An empty submitted map is a real rejected POST with everything blanked, so the form must
                // come back blank rather than silently repopulated from storage. Null, not an empty map, is
                // what means "not a replay" here.
                EditView view = EditView.buildEditView(
                    record("newSurname", "Stored"),
                    APPLICATION_TAB,
                    ADMIN,
                    Map.of());

                assertThat(field(view, "newSurname")).isPresent();
                assertThat(field(view, "newSurname").orElseThrow().value()).isNull();
            }
        }
    }

    @Nested
    @DisplayName("buildErrorSummary")
    class BuildErrorSummary {

        @Test
        @DisplayName("links an entry whose field is on the submitted tab")
        void linksAnEntryWhoseFieldIsOnTheSubmittedTab() {
            String onTab = EditView.editableFieldNames(APPLICATION_TAB).iterator().next();

            assertThat(EditView.buildErrorSummary(List.of(new FieldError(onTab, "Bad")), APPLICATION_TAB))
                .containsExactly(new SummaryEntry("Bad", "#" + onTab));
        }

        @Test
        @DisplayName("omits the link when the field is not on this tab, rather than emitting a dead anchor")
        void omitsTheLinkWhenTheFieldIsNotOnThisTab() {
            // The cross-field date rules can be addressed to a field on another tab -- the Enroled+Returned
            // rule targets `state`, on the Application tab. A dead #state link from the Return tab is worse
            // than no link: the message reads fine on its own.
            List<SummaryEntry> summary = EditView.buildErrorSummary(
                List.of(new FieldError("notOnThisTab", "Dates are inconsistent")),
                APPLICATION_TAB);

            assertThat(summary).containsExactly(new SummaryEntry("Dates are inconsistent", null));
        }

        @Test
        @DisplayName("keeps the message text regardless of whether it links")
        void keepsTheMessageTextRegardlessOfWhetherItLinks() {
            List<SummaryEntry> summary =
                EditView.buildErrorSummary(List.of(new FieldError("nope", "Still shown")), APPLICATION_TAB);

            assertThat(summary.get(0).text()).isEqualTo("Still shown");
        }

        @Test
        @DisplayName("returns an empty summary for no errors")
        void returnsAnEmptySummaryForNoErrors() {
            assertThat(EditView.buildErrorSummary(List.of(), APPLICATION_TAB)).isEmpty();
        }
    }

    @Nested
    @DisplayName("editableFieldNames")
    class EditableFieldNames {

        @Test
        @DisplayName("excludes read-only fields, which an error summary must not link to")
        void excludesReadOnlyFields() {
            for (TabDef tab : FormFields.FORM_TABS) {
                Set<String> names = EditView.editableFieldNames(tab);
                List<FieldDef> readOnly = tab.sections().stream()
                    .flatMap(section -> section.fields().stream())
                    .filter(FieldDef::readOnly)
                    .toList();

                for (FieldDef candidate : readOnly) {
                    assertThat(names).isNotEmpty().doesNotContain(candidate.name());
                }
            }
        }
    }

    @Test
    @DisplayName("groups the decorated fields by the tab's own sections")
    void groupsTheDecoratedFieldsByTheTabsSections() {
        // Not a reference block: the reference's own helper flattens sections away, so nothing there checks
        // that the section labels survive. A form that renders one undifferentiated list of 25 inputs is the
        // failure this catches.
        EditView view = EditView.buildEditView(record("type", DeedPollType.Adult), APPLICATION_TAB, ADMIN);

        assertThat(view.sections()).extracting(EditSection::label)
            .isEqualTo(APPLICATION_TAB.sections().stream().map(SectionDef::label).toList());
    }
}
