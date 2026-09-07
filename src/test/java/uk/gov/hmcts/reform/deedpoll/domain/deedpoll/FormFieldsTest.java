package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.enums.DynamicsValues;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The structural guarantees {@code form-fields.ts} gets from the TypeScript compiler and this port cannot.
 *
 * <p>The reference types a field's {@code enumSet} as {@code keyof typeof LABELS} and its {@code subgrid}
 * as a three-member union, so a typo in either fails to compile. Java has a {@link String} and a lookup,
 * which fails at render time instead -- with a select rendering no options, or a subgrid tab reading as
 * empty on a case that has children. These assertions put the checks back.
 *
 * <p>The same reasoning covers {@link DeedPollFields}: the reference reads and writes a record by index
 * signature, so a field on a form tab is readable by construction. Here it is readable only if it is in
 * the registry.
 */
class FormFieldsTest {

    /** The fields that are derived on read and so have no column to read or write. */
    private static final List<String> DERIVED_FIELDS = List.of("daysReceivedToEnroled", "daysReceivedToReturned");

    /**
     * Read-only on the form, but still accepted by a patch.
     *
     * <p>{@code state} alone. The reference's schema accepts it "only for seeding/import" while its form
     * gives it no editable control, because it is derived from the populated dates on every write --
     * {@code DeedPollService} overwrites whatever a caller sent. Both halves of that are deliberate, so
     * the field is exempted here rather than either half being changed to match the other.
     */
    private static final List<String> SEEDABLE_READ_ONLY_FIELDS = List.of("state");

    @Test
    @DisplayName("every enumSet named on a form field resolves to a label set")
    void everyEnumSetResolvesToALabelSet() {
        for (FieldDef field : FormFields.allFields()) {
            if (field.enumSet() != null) {
                assertThat(DynamicsValues.LABELS)
                    .withFailMessage("%s names label set %s, which does not exist", field.name(), field.enumSet())
                    .containsKey(field.enumSet());
            }
        }
    }

    @Test
    @DisplayName("every enum and multiselect field has an enumSet, so it cannot render unlabelled")
    void everyEnumFieldHasAnEnumSet() {
        assertThat(FormFields.allFields())
            .filteredOn(field -> field.kind() == FieldKind.ENUM || field.kind() == FieldKind.MULTISELECT)
            .isNotEmpty()
            .allSatisfy(field -> assertThat(field.enumSet())
                .withFailMessage("%s is an option set with no label set", field.name())
                .isNotNull());
    }

    @Test
    @DisplayName("every field on a form tab is readable off a case")
    void everyFormFieldIsReadable() {
        // A name absent from the readers would render as "Not provided" on every case, whatever the row
        // holds -- the write-only defect DetailView exists to prevent, arriving from the other direction.
        for (FieldDef field : FormFields.allFields()) {
            if (DERIVED_FIELDS.contains(field.name())) {
                continue;
            }
            assertThat(DeedPollFields.readAll(new uk.gov.hmcts.reform.deedpoll.entity.DeedPoll()))
                .withFailMessage("%s is on a form tab but cannot be read off a case", field.name())
                .containsKey(field.name());
        }
    }

    @Test
    @DisplayName("every editable field on a form tab is writable, and no read-only one is")
    void everyEditableFormFieldIsWritable() {
        for (FieldDef field : FormFields.EDITABLE_FIELDS) {
            if (DERIVED_FIELDS.contains(field.name())) {
                continue;
            }
            assertThat(DeedPollFields.isWritable(field.name()))
                .withFailMessage("%s is editable on a form tab but a patch cannot set it", field.name())
                .isTrue();
        }
        assertThat(FormFields.allFields())
            .filteredOn(FieldDef::readOnly)
            .filteredOn(field -> !SEEDABLE_READ_ONLY_FIELDS.contains(field.name()))
            .isNotEmpty()
            .allSatisfy(field -> assertThat(DeedPollFields.isWritable(field.name()))
                .withFailMessage("%s is read-only on the form but a patch can set it", field.name())
                .isFalse());
    }

    @Test
    @DisplayName("names every field exactly once across the tabs")
    void namesEveryFieldExactlyOnceAcrossTheTabs() {
        // FIELD_BY_NAME is keyed by name, so a field appearing on two tabs would silently lose one of its
        // definitions -- and give the field two save paths, which is the reason referToMasterChoice is on
        // the Referral tab only.
        List<String> names = FormFields.allFields().stream().map(FieldDef::name).toList();

        assertThat(names).doesNotHaveDuplicates();
        assertThat(FormFields.FIELD_BY_NAME).hasSameSizeAs(names);
    }

    @Test
    @DisplayName("gives every subgrid tab a subgrid and no data-entry sections, and every other tab the reverse")
    void separatesSubgridTabsFromDataEntryTabs() {
        for (TabDef tab : FormFields.FORM_TABS) {
            if (tab.subgrid() == null) {
                assertThat(tab.sections()).withFailMessage("%s has no fields and no subgrid", tab.id()).isNotEmpty();
            } else {
                assertThat(tab.sections())
                    .withFailMessage("%s is a subgrid tab but also carries fields", tab.id())
                    .isEmpty();
            }
        }
    }

    @Test
    @DisplayName("builds select options in the label set's declaration order")
    void buildsSelectOptionsInDeclarationOrder() {
        assertThat(FormFields.enumOptions("type"))
            .extracting(FormFields.SelectOption::value)
            .containsExactly("Adult", "AdultTransgender", "Minor", "MinorTransgender", "DeedOfRelinquishment",
                "Unknown");
        assertThat(FormFields.enumOptions("type"))
            .extracting(FormFields.SelectOption::text)
            .startsWith("Adult", "Adult Transgender");
    }
}
