package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CardLayout.CardBlock;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CardLayout.CardColumn;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CardLayout.ColumnsBlock;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CardLayout.FullBlock;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DetailView.DetailRow;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DetailView.DetailSection;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DetailView.DetailTab;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Nationality;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Privacy;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferToMaster;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ports every test block in {@code reference/ts/deed-poll/detail-view.test.ts} -- value formatting, parity
 * with the edit form, the per-type and conditional filters, the empty states, and the card bands.
 */
class DetailViewTest {

    private static Map<String, Object> record(Object... keysAndValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return values;
    }

    private static Map<String, FieldRule> rulesFor(DeedPollType type) {
        return DeedPollValidation.getFieldRules(type);
    }

    private static FormPresentation presentationFor(PresentationInput input) {
        return FormPresentation.build(input, new PresentationContext(true, false));
    }

    /** Flattens a built tab set to "Tab &gt; Section &gt; Key" = value, as the reference's helper does. */
    private static Map<String, String> flatten(List<DetailTab> tabs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (DetailTab tab : tabs) {
            for (DetailSection section : tab.sections()) {
                for (DetailRow row : section.rows()) {
                    out.put(tab.label() + " > " + section.label() + " > " + row.key(), row.value());
                }
            }
        }
        return out;
    }

    private static FieldDef field(FieldKind kind, String enumSet) {
        return new FieldDef("f", "F", kind, enumSet, false, false, null);
    }

    /** One band as a comparable string, so a band SEQUENCE is asserted rather than just membership. */
    private static String bandShape(CardBlock<DetailRow> block) {
        return switch (block) {
            case FullBlock<DetailRow> full -> "full" + keysOf(full.rows());
            case ColumnsBlock<DetailRow> columns ->
                "left" + keysOf(columns.left()) + " right" + keysOf(columns.right());
        };
    }

    private static List<String> keysOf(List<DetailRow> rows) {
        return rows.stream().map(DetailRow::key).toList();
    }

    private static Stream<Arguments> stackedTabs() {
        return Stream.of(
            Arguments.of("enrolment",
                List.of("Date enroled", "Days received to enroled", "Date notice sent to London Gazette")),
            Arguments.of("refund", List.of("Refund amount", "Date of refund", "Refund reference")));
    }

    @Nested
    @DisplayName("formatValue")
    class FormatValue {

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = "")
        @DisplayName("renders an absent value as the placeholder")
        void rendersAnAbsentValueAsThePlaceholder(String raw) {
            // The reference also covers `undefined`, which Java does not distinguish from null.
            assertThat(DetailView.formatValue(field(FieldKind.TEXT, null), raw)).isEqualTo(DetailView.NOT_PROVIDED);
        }

        @Test
        @DisplayName("formats a date GOV.UK style in UTC")
        void formatsADateGovUkStyleInUtc() {
            assertThat(DetailView.formatValue(field(FieldKind.DATE, null), LocalDateTime.parse("2026-07-31T00:00:00")))
                .isEqualTo("31 July 2026");
        }

        @Test
        @DisplayName("does not shift a date across a day boundary via local time")
        void doesNotShiftADateAcrossADayBoundary() {
            // A midnight-UTC date must not render as the 30th in a negative-offset zone.
            assertThat(DetailView.formatValue(field(FieldKind.DATE, null), "2026-01-01T00:00:00Z"))
                .isEqualTo("1 January 2026");
        }

        @Test
        @DisplayName("renders booleans as Yes/No, including false")
        void rendersBooleansAsYesNo() {
            assertThat(DetailView.formatValue(field(FieldKind.BOOLEAN, null), true)).isEqualTo("Yes");
            // false is meaningful here, not absent -- it must not become "Not provided".
            assertThat(DetailView.formatValue(field(FieldKind.BOOLEAN, null), false)).isEqualTo("No");
        }

        @Test
        @DisplayName("maps an enum to its Dynamics label")
        void mapsAnEnumToItsDynamicsLabel() {
            assertThat(DetailView.formatValue(field(FieldKind.ENUM, "type"), DeedPollType.DeedOfRelinquishment))
                .isEqualTo("Deed of Relinquishment");
        }

        @Test
        @DisplayName("falls back to the raw value for an unmapped enum member")
        void fallsBackToTheRawValueForAnUnmappedMember() {
            assertThat(DetailView.formatValue(field(FieldKind.ENUM, "type"), "Wat")).isEqualTo("Wat");
        }

        @Test
        @DisplayName("joins a multiselect through its integer label map")
        void joinsAMultiselectThroughItsIntegerLabelMap() {
            assertThat(DetailView.formatValue(field(FieldKind.MULTISELECT, "iShall"), List.of(1, 4)))
                .isEqualTo("and my wife, and my children");
        }

        @Test
        @DisplayName("renders an empty multiselect as the placeholder")
        void rendersAnEmptyMultiselectAsThePlaceholder() {
            assertThat(DetailView.formatValue(field(FieldKind.MULTISELECT, "iShall"), List.of()))
                .isEqualTo(DetailView.NOT_PROVIDED);
        }

        @Test
        @DisplayName("keeps a zero number rather than treating it as absent")
        void keepsAZeroNumber() {
            assertThat(DetailView.formatValue(field(FieldKind.NUMBER, null), 0)).isEqualTo("0");
        }
    }

    @Nested
    @DisplayName("buildDetailTabs -- parity with the edit form")
    class ParityWithTheEditForm {

        @Test
        @DisplayName("offers every non-conditional tab")
        void offersEveryNonConditionalTab() {
            // The regression this module exists to prevent: the detail page listed six tabs by hand while
            // the form had nine, so whole field groups were write-only. Both sides now derive from
            // FORM_TABS.
            List<DetailTab> tabs = DetailView.buildDetailTabs(
                record("type", DeedPollType.Adult),
                rulesFor(DeedPollType.Adult),
                presentationFor(PresentationInput.builder().type(DeedPollType.Adult).build()));

            assertThat(tabs).extracting(DetailTab::id)
                .isEqualTo(FormFields.FORM_TABS.stream().map(TabDef::id).toList());
        }

        @Test
        @DisplayName("surfaces address, minor and declaration fields that the old page dropped")
        void surfacesFieldsTheOldPageDropped() {
            Map<String, Object> record = record(
                "type", DeedPollType.Minor,
                "applicantAddress1", "1 High Street",
                "applicantTownCity", "Cardiff",
                "applicantPostcode", "CF10 1AA",
                "representativeName", "Leah Okafor",
                "representativeCompany", "Okafor & Co",
                "ageOfMinor", 9,
                "ageOfMinorMonths", 4,
                "parents", "A Okafor, B Okafor",
                "iShall", List.of(5),
                "dateOfDeclaration", LocalDateTime.parse("2026-07-20T00:00:00"));

            Map<String, String> flat = flatten(DetailView.buildDetailTabs(
                record,
                rulesFor(DeedPollType.Minor),
                presentationFor(PresentationInput.builder().type(DeedPollType.Minor).build())));

            assertThat(flat)
                .containsEntry("Application > Applicant address and contact details > Address line 1", "1 High Street")
                .containsEntry("Application > Applicant address and contact details > Postcode", "CF10 1AA")
                .containsEntry("Application > Representative address and contact details > Company", "Okafor & Co")
                .containsEntry("Application > General > Age of minor years", "9")
                .containsEntry("Application > General > Parents", "A Okafor, B Okafor")
                .containsEntry("Application > Declarations > I shall", "and I alone")
                .containsEntry("Application > Declarations > Date of declaration", "20 July 2026");
        }

        @Test
        @DisplayName("shows the computed day-count once it has a value")
        void showsTheComputedDayCountOnceItHasAValue() {
            Map<String, String> flat = flatten(DetailView.buildDetailTabs(
                record(
                    "type", DeedPollType.Adult,
                    "dateEnroled", LocalDateTime.parse("2026-07-15T00:00:00"),
                    "daysReceivedToEnroled", 14),
                rulesFor(DeedPollType.Adult),
                presentationFor(PresentationInput.builder()
                    .type(DeedPollType.Adult)
                    .state(DeedPollState.Enroled)
                    .build())));

            assertThat(flat).containsEntry("Enrolment > Enrolment > Days received to enroled", "14");
        }
    }

    @Nested
    @DisplayName("buildDetailTabs -- per-type field rules")
    class PerTypeFieldRules {

        @Test
        @DisplayName("hides minor fields on an adult case")
        void hidesMinorFieldsOnAnAdultCase() {
            // ageOfMinor is populated but the adult rule set hides it; the read-only page must respect the
            // same rules as the form rather than leaking the value.
            Map<String, String> flat = flatten(DetailView.buildDetailTabs(
                record("type", DeedPollType.Adult, "ageOfMinor", 9),
                rulesFor(DeedPollType.Adult),
                presentationFor(PresentationInput.builder().type(DeedPollType.Adult).build())));

            assertThat(flat).doesNotContainKey("Application > General > Age of minor years");
        }

        @Test
        @DisplayName("hides old-name and nationality fields on a relinquishment")
        void hidesOldNameAndNationalityOnARelinquishment() {
            Map<String, String> flat = flatten(DetailView.buildDetailTabs(
                record(
                    "type", DeedPollType.DeedOfRelinquishment,
                    "oldForename", "Sam",
                    "nationality", Nationality.BritishCitizen,
                    "newForename", "Alex"),
                rulesFor(DeedPollType.DeedOfRelinquishment),
                presentationFor(PresentationInput.builder().type(DeedPollType.DeedOfRelinquishment).build())));

            assertThat(flat).doesNotContainKey("Application > Names > Old forename");
            assertThat(flat).doesNotContainKey("Application > Nationality > Nationality");
            // The fields it does use still show.
            assertThat(flat).containsEntry("Application > Names > New forename", "Alex");
        }
    }

    @Nested
    @DisplayName("buildDetailTabs -- conditional tabs")
    class ConditionalTabs {

        @Test
        @DisplayName("hides Referral to Master when the choice is No")
        void hidesReferralWhenTheChoiceIsNo() {
            List<DetailTab> tabs = DetailView.buildDetailTabs(
                record("type", DeedPollType.Adult, "referToMasterChoice", ReferToMaster.No),
                rulesFor(DeedPollType.Adult),
                presentationFor(PresentationInput.builder()
                    .type(DeedPollType.Adult)
                    .referToMasterChoice(ReferToMaster.No)
                    .build()));

            assertThat(tabs).extracting(DetailTab::id).doesNotContain("referral");
        }

        @Test
        @DisplayName("hides Return when the case is Enroled")
        void hidesReturnWhenTheCaseIsEnroled() {
            List<DetailTab> tabs = DetailView.buildDetailTabs(
                record("type", DeedPollType.Adult),
                rulesFor(DeedPollType.Adult),
                presentationFor(PresentationInput.builder()
                    .type(DeedPollType.Adult)
                    .state(DeedPollState.Enroled)
                    .build()));

            assertThat(tabs).extracting(DetailTab::id).contains("enrolment").doesNotContain("return");
        }

        @Test
        @DisplayName("hides Enrolment when the case is Returned")
        void hidesEnrolmentWhenTheCaseIsReturned() {
            List<DetailTab> tabs = DetailView.buildDetailTabs(
                record("type", DeedPollType.Adult),
                rulesFor(DeedPollType.Adult),
                presentationFor(PresentationInput.builder()
                    .type(DeedPollType.Adult)
                    .state(DeedPollState.Returned)
                    .build()));

            assertThat(tabs).extracting(DetailTab::id).contains("return").doesNotContain("enrolment");
        }
    }

    @Nested
    @DisplayName("buildDetailTabs -- empty states")
    class EmptyStates {

        private List<DetailTab> adultTabs(Map<String, Object> record, Map<String, FieldRule> rules) {
            return DetailView.buildDetailTabs(
                record,
                rules,
                presentationFor(PresentationInput.builder().type(DeedPollType.Adult).build()));
        }

        private Optional<DetailSection> sectionOf(List<DetailTab> tabs, String tabId, String label) {
            return tabs.stream()
                .filter(tab -> tabId.equals(tab.id()))
                .flatMap(tab -> tab.sections().stream())
                .filter(section -> label.equals(section.label()))
                .findFirst();
        }

        @Test
        @DisplayName("keeps an unpopulated field as a placeholder row, so positions are stable")
        void keepsAnUnpopulatedFieldAsAPlaceholderRow() {
            // Superseded the original skip-if-empty rule when the page moved to two-column cards. The cards
            // replicate the Dynamics form so a caseworker finds a given field in the same place on every
            // case; a row that vanishes when blank shifts everything below it and defeats that.
            List<DetailTab> tabs = adultTabs(
                record("type", DeedPollType.Adult, "newForename", "Alex"),
                rulesFor(DeedPollType.Adult));

            Optional<DetailSection> general = sectionOf(tabs, "application", "General");
            assertThat(general).isPresent();
            assertThat(general.get().rows()).contains(new DetailRow("Type", "Adult", CardColumn.LEFT));
            assertThat(general.get().rows())
                .contains(new DetailRow("Fee reference", DetailView.NOT_PROVIDED, CardColumn.LEFT));
        }

        @Test
        @DisplayName("still drops a section whose every field is hidden")
        void stillDropsASectionWhoseEveryFieldIsHidden() {
            // The one remaining reason to drop a section. No real type hides a whole section today (every
            // section keeps at least one visible field for both Adult and Minor), so the rules are supplied
            // explicitly rather than via getFieldRules -- otherwise this branch would go untested until a
            // future rule change reached it.
            List<FieldDef> nationalityFields = FormFields.FORM_TABS.stream()
                .filter(tab -> "application".equals(tab.id()))
                .flatMap(tab -> tab.sections().stream())
                .filter(section -> "Nationality".equals(section.label()))
                .flatMap(section -> section.fields().stream())
                .toList();

            Map<String, FieldRule> rules = new LinkedHashMap<>(rulesFor(DeedPollType.Adult));
            nationalityFields.forEach(field -> rules.put(field.name(), new FieldRule(false, false)));

            List<DetailTab> tabs = adultTabs(record("type", DeedPollType.Adult, "newForename", "Alex"), rules);

            assertThat(tabs.stream()
                .filter(tab -> "application".equals(tab.id()))
                .flatMap(tab -> tab.sections().stream())
                .map(DetailSection::label)
                .toList())
                .doesNotContain("Nationality");
        }

        @Test
        @DisplayName("hides individual fields the type does not use, keeping the rest")
        void hidesIndividualFieldsKeepingTheRest() {
            // All four minor fields sit at the foot of General's left column and all four are hidden for an
            // Adult, so General survives on its remaining fields. This is the per-field hiding path,
            // distinct from the whole-section drop above.
            Optional<DetailSection> general = sectionOf(
                adultTabs(record("type", DeedPollType.Adult, "ageOfMinor", 9), rulesFor(DeedPollType.Adult)),
                "application",
                "General");

            assertThat(general).isPresent();
            assertThat(general.get().rows()).extracting(DetailRow::key)
                .doesNotContain("Age of minor years", "Minor's date of birth")
                .contains("Marital status");
        }

        @Test
        @DisplayName("ends the General left column at the minor fields for a minor case")
        void endsTheGeneralLeftColumnAtTheMinorFields() {
            // power_app/screenshots/application_general_minor.png: the four minor fields close the left
            // column, below Marital status. On an Adult case they vanish and the column simply ends earlier
            // -- no stranded card, no empty rows.
            List<DetailTab> tabs = DetailView.buildDetailTabs(
                record("type", DeedPollType.Minor),
                rulesFor(DeedPollType.Minor),
                presentationFor(PresentationInput.builder().type(DeedPollType.Minor).build()));

            Optional<DetailSection> general = sectionOf(tabs, "application", "General");
            assertThat(general).isPresent();

            List<String> left = new ArrayList<>();
            for (CardBlock<DetailRow> block : general.get().blocks()) {
                if (block instanceof ColumnsBlock<DetailRow> columns) {
                    columns.left().forEach(row -> left.add(row.key()));
                }
            }

            assertThat(left.subList(left.size() - 5, left.size())).containsExactly(
                "Marital status",
                "Minor's date of birth",
                "Age of minor years",
                "Age of minor months",
                "Parents");
        }

        @Test
        @DisplayName("no longer marks a tab empty just because nothing is filled in")
        void noLongerMarksATabEmptyJustBecauseNothingIsFilledIn() {
            // Refund has no hidden-by-type fields, so its rows now always render.
            Optional<DetailTab> refund = adultTabs(record("type", DeedPollType.Adult), rulesFor(DeedPollType.Adult))
                .stream()
                .filter(tab -> "refund".equals(tab.id()))
                .findFirst();

            assertThat(refund).isPresent();
            assertThat(refund.get().empty()).isFalse();
            assertThat(refund.get().sections().get(0).rows())
                .allMatch(row -> DetailView.NOT_PROVIDED.equals(row.value()));
        }

        @Test
        @DisplayName("marks subgrid tabs empty or not from their child collections")
        void marksSubgridTabsEmptyOrNotFromTheirChildCollections() {
            List<DetailTab> tabs = adultTabs(
                record(
                    "type", DeedPollType.Adult,
                    "notes", List.of(Map.of("note", "Chased applicant")),
                    "witnesses", List.of()),
                rulesFor(DeedPollType.Adult));

            assertThat(tabs).filteredOn(tab -> "notes".equals(tab.id())).allMatch(tab -> !tab.empty());
            assertThat(tabs).filteredOn(tab -> "witnesses".equals(tab.id())).allMatch(DetailTab::empty);
            // Absent entirely (not just empty) must also read as empty, not crash.
            assertThat(tabs).filteredOn(tab -> "documents".equals(tab.id())).allMatch(DetailTab::empty);
        }

        @Test
        @DisplayName("carries the subgrid marker so the template can render child records")
        void carriesTheSubgridMarker() {
            List<DetailTab> tabs = adultTabs(record("type", DeedPollType.Adult), rulesFor(DeedPollType.Adult));

            assertThat(tabs).filteredOn(tab -> "documents".equals(tab.id()))
                .allMatch(tab -> tab.subgrid() == TabDef.Subgrid.DOCUMENTS);
            assertThat(tabs).filteredOn(tab -> "application".equals(tab.id()))
                .allMatch(tab -> tab.subgrid() == null);
        }
    }

    @Nested
    @DisplayName("buildDetailTabs -- card columns")
    class CardColumns {

        /**
         * The single two-column band of a section that has no full-width fields.
         *
         * <p>Most sections are one band, so this keeps those assertions about positions rather than about
         * the band sequence -- see the Return/Referral tests below for the sections that genuinely have
         * more than one.
         */
        private ColumnsBlock<DetailRow> cols(DetailSection section) {
            List<ColumnsBlock<DetailRow>> bands = section.blocks().stream()
                .filter(ColumnsBlock.class::isInstance)
                .map(block -> {
                    @SuppressWarnings("unchecked")
                    ColumnsBlock<DetailRow> columns = (ColumnsBlock<DetailRow>) block;
                    return columns;
                })
                .toList();
            assertThat(bands).hasSize(1);
            return bands.get(0);
        }

        private List<String> keys(List<DetailRow> rows) {
            return rows.stream().map(DetailRow::key).toList();
        }

        /** Every tab of a case built to show as many of them as possible. */
        private List<DetailTab> allTabs() {
            return DetailView.buildDetailTabs(
                record("type", DeedPollType.Minor, "referToMasterChoice", ReferToMaster.Yes),
                rulesFor(DeedPollType.Minor),
                presentationFor(PresentationInput.builder()
                    .type(DeedPollType.Minor)
                    .referToMasterChoice(ReferToMaster.Yes)
                    .build()));
        }

        private DetailSection section(String label) {
            return DetailView.buildDetailTabs(
                    record("type", DeedPollType.Adult, "privacy", Privacy.Normal),
                    rulesFor(DeedPollType.Adult),
                    presentationFor(PresentationInput.builder().type(DeedPollType.Adult).build()))
                .stream()
                .filter(tab -> "application".equals(tab.id()))
                .flatMap(tab -> tab.sections().stream())
                .filter(candidate -> label.equals(candidate.label()))
                .findFirst()
                .orElseThrow();
        }

        @Test
        @DisplayName("splits General into the Dynamics form column positions")
        void splitsGeneralIntoTheDynamicsFormColumnPositions() {
            // Read down power_app/screenshots/application_general.png: Reference Number top left, Privacy
            // top right. Three of ours trail their related field (see FormFields), the third being Current
            // referral state under State -- it renders because being a form field is what makes it
            // queryable, which is the visible cost the referral read-model plan accepted. This is the ADULT
            // case, so the four minor fields that would close the left column are hidden -- see the
            // minor-case test for those.
            ColumnsBlock<DetailRow> general = cols(section("General"));
            assertThat(keys(general.left())).containsExactly(
                "Reference number",
                "Display reference",
                "Date of application",
                "In person",
                "Post / Counter",
                "Fee reference",
                "Date received",
                "Type",
                "Marital status");
            assertThat(keys(general.right())).containsExactly(
                "Privacy",
                "State",
                "Current referral state",
                "Urgency",
                "Urgency reason",
                "Online application reference",
                "Retain permanently",
                "Awaiting further docs/info");
        }

        @Test
        @DisplayName("splits Names into new down the left, old down the right")
        void splitsNamesIntoNewLeftOldRight() {
            // power_app/screenshots/application_names.png. The source form's cells are row-major (New
            // Forename / Old Forename, then the middle names, then the surnames); read column-wise that is
            // new-name left, old-name right.
            ColumnsBlock<DetailRow> names = cols(section("Names"));
            assertThat(keys(names.left())).containsExactly("New forename", "New middle name", "New surname");
            assertThat(keys(names.right())).containsExactly("Old forename", "Old middle name", "Old surname");
        }

        @Test
        @DisplayName("splits the applicant address into address left, contact details right")
        void splitsTheApplicantAddress() {
            // power_app/screenshots/application_address.png. Postcode belongs at the FOOT OF THE LEFT
            // column, which is what makes this section worth asserting: the halve-in-order fallback would
            // have put it at the head of the right one, next to Email, breaking the postal address in two.
            ColumnsBlock<DetailRow> address = cols(section("Applicant address and contact details"));
            assertThat(keys(address.left())).containsExactly(
                "Building number/name",
                "Address line 1",
                "Address line 2",
                "Town/city",
                "Postcode");
            assertThat(keys(address.right())).containsExactly("Email", "Telephone");
        }

        @Test
        @DisplayName("mirrors the representative card: contact left, address right")
        void mirrorsTheRepresentativeCard() {
            // power_app/screenshots/application_repesentative.png. Deliberately the OPPOSITE way round from
            // the applicant card asserted above. Both are asserted so that "make the two consistent" cannot
            // pass silently -- the source form is inconsistent here and we match it.
            ColumnsBlock<DetailRow> rep = cols(section("Representative address and contact details"));
            assertThat(keys(rep.left())).containsExactly("Name", "Company", "Email", "Telephone");
            assertThat(keys(rep.right())).containsExactly(
                "Building number/name",
                "Address line 1",
                "Address line 2",
                "Town/city",
                "Postcode");
        }

        @Test
        @DisplayName("stacks Nationality in one column, keeping Section (other) under Section")
        void stacksNationalityInOneColumn() {
            // sectionNationality puts all three in the left cell. The halve-in-order fallback used to strand
            // Section (other) in the right column, away from the Section it qualifies.
            ColumnsBlock<DetailRow> nationality = cols(section("Nationality"));
            assertThat(keys(nationality.left())).containsExactly("Nationality", "Section", "Section (other)");
            assertThat(nationality.right()).isEmpty();
        }

        @Test
        @DisplayName("splits Declarations the way the source cells read column-wise")
        void splitsDeclarationsColumnWise() {
            // sectionDeclarations is row-major: I shall / I have subscribed, then Date of declaration / And
            // also my old. The halving fallback had produced left [I shall, I have subscribed], right [And
            // also my old, Date of declaration].
            ColumnsBlock<DetailRow> declarations = cols(section("Declarations"));
            assertThat(keys(declarations.left())).containsExactly("I shall", "Date of declaration");
            assertThat(keys(declarations.right())).containsExactly("I have subscribed", "And also my old");
        }

        @Test
        @DisplayName("accounts for every row across the bands")
        void accountsForEveryRowAcrossTheBands() {
            // The bands are a view OF rows, not a second source of truth: a field that reached `rows` but no
            // band would be invisible on the page. Asserted over every section of every tab, because a band
            // sequence has more ways to drop a row than a single left/right split did.
            for (DetailTab tab : allTabs()) {
                for (DetailSection section : tab.sections()) {
                    List<String> banded = new ArrayList<>();
                    for (CardBlock<DetailRow> block : section.blocks()) {
                        if (block instanceof FullBlock<DetailRow> full) {
                            full.rows().forEach(row -> banded.add(row.key()));
                        } else if (block instanceof ColumnsBlock<DetailRow> columns) {
                            columns.left().forEach(row -> banded.add(row.key()));
                            columns.right().forEach(row -> banded.add(row.key()));
                        }
                    }
                    assertThat(banded).containsExactlyInAnyOrderElementsOf(keys(section.rows()));
                }
            }
        }
    }

    @Nested
    @DisplayName("buildDetailTabs -- full-width bands")
    class FullWidthBands {

        private DetailSection sectionOf(String tabId) {
            return DetailView.buildDetailTabs(
                    record("type", DeedPollType.Adult, "referToMasterChoice", ReferToMaster.Yes),
                    rulesFor(DeedPollType.Adult),
                    presentationFor(PresentationInput.builder()
                        .type(DeedPollType.Adult)
                        .referToMasterChoice(ReferToMaster.Yes)
                        .build()))
                .stream()
                .filter(tab -> tabId.equals(tab.id()))
                .flatMap(tab -> tab.sections().stream())
                .findFirst()
                .orElseThrow();
        }

        /** The band sequence as one comparable shape, so order is asserted and not just membership. */
        private List<String> shape(DetailSection section) {
            return section.blocks().stream().map(DetailViewTest::bandShape).toList();
        }

        @Test
        @DisplayName("puts Return reason on its own full-width band below the dates")
        void putsReturnReasonOnItsOwnFullWidthBand() {
            // sectionReturn: both dates in the left cell, then deedpoll_returnreason at colspan="2". Before
            // this, the halve-in-order fallback put Return reason in the RIGHT column beside Date returned --
            // the defect this band model exists to fix.
            assertThat(shape(sectionOf("return"))).containsExactly(
                "left[Date returned, Days received to returned] right[]",
                "full[Return reason]");
        }

        @Test
        @DisplayName("interleaves the two full-width Referral fields in source order")
        void interleavesTheTwoFullWidthReferralFields() {
            // The section that proves a card must be a SEQUENCE of bands: Referral notes and Master
            // directions sit between two-column runs, so neither can be hoisted to an end of the card
            // without reordering the form.
            assertThat(shape(sectionOf("referral"))).containsExactly(
                "left[Refer to Master, Date ready for Master, Date referred] right[]",
                "full[Referral notes]",
                "left[Master name] right[]",
                "full[Master directions]",
                "left[Master decision, Master date of decision, Date decision/directions received, "
                    + "Publish to London Gazette] right[]");
        }

        @ParameterizedTest(name = "stacks the {0} tab in one column, as the source does")
        @MethodSource("uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DetailViewTest#stackedTabs")
        void stacksATabInOneColumn(String tabId, List<String> expected) {
            // Both sections leave the right cell empty in the source. The fallback had split them
            // two-left/one-right, moving the third field up beside the first.
            assertThat(shape(sectionOf(tabId))).containsExactly("left" + expected + " right[]");
        }

        @Test
        @DisplayName("keeps the Signature dates side by side")
        void keepsTheSignatureDatesSideBySide() {
            // The one later section that genuinely uses both cells. Positioned explicitly now, so a third
            // field on the tab cannot silently re-split it.
            assertThat(shape(sectionOf("signature")))
                .containsExactly("left[Signature requested date] right[Signature received date]");
        }
    }
}
