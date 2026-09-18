package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CardLayout.CardColumn;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.enums.DynamicsValues;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CardLayout.CardColumn.FULL;
import static uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CardLayout.CardColumn.LEFT;
import static uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CardLayout.CardColumn.RIGHT;

/**
 * Form-field metadata -- the single source of truth for the editable case form.
 *
 * <p>Ported from {@code reference/ts/deed-poll/form-fields.ts}. Mirrors the Dynamics main form
 * ({@code customizations.xml}, {@code <forms type="main">}): tabs, then sections, then fields in
 * document order. The rendered form and the accepted payload both drive off this, so they cannot drift
 * apart.
 *
 * <p>Faithfulness rules baked in (see the source form and the ported workflows):
 *
 * <ul>
 *   <li>Nothing is form-required here; required-ness comes from
 *       {@link DeedPollValidation#getFieldRules}.</li>
 *   <li>Read-only fields (the reference number, the computed day counts) render disabled.</li>
 *   <li>{@code state} is derived and has no editable field -- it recomputes on save.</li>
 *   <li>Notes, Witnesses and Documents are subgrids, handled separately as nested CRUD.</li>
 * </ul>
 *
 * <p>Every data-entry section is positioned explicitly, read out of the {@code <cell>} grid of the
 * matching {@code <section>} in {@code power_app/.../customizations.xml} -- a {@code colspan="2"} cell
 * is {@code FULL}, otherwise the cell index is the column. Read the XML rather than eyeballing a
 * screenshot: the halving fallback had put Return Reason beside Date returned, and the four later tabs
 * were laid out by it because we had no screenshot of them. If you add a section, position it from the
 * XML.
 */
public final class FormFields {

    /** The Application tab: General, Names, Addresses, Nationality, Declarations. */
    private static final TabDef APPLICATION_TAB = TabDef.of("application", "Application", List.of(
        // Column positions replicate power_app/screenshots/application_general.png, read down the left
        // column then the right. Three departures, all deliberate:
        //   - `displayReference` and `urgencyreason` have no place on the source card. They are ours
        //     (the reference the user actually quotes, and the reason a case was flagged urgent), so
        //     they follow their related field.
        //   - The source card also shows "Refer to Master?", which we own on the Referral tab because
        //     it drives that tab's visibility. Duplicating one field across two editable forms would
        //     give it two save paths.
        //   - `currentReferralState` is ours too, and it is here rather than on the Referral tab
        //     because it is the case's own summary of where its live referral has got to -- the
        //     question "State" answers for the case, asked about the referral. It is registered as a
        //     form field at all because that is what makes it queryable: `CaseQuery.queryableField`
        //     admits a name only if FIELD_BY_NAME holds it. Rendering it read-only beside State is the
        //     cost of that, and the Referral tab hides itself on a case not referred to the Master, so
        //     a field a dashboard filters on would have been invisible on exactly the cases it counts.
        new SectionDef("General", List.of(
            number("referencenumber", "Reference number", LEFT).asReadOnly(),
            text("displayReference", "Display reference", LEFT).asReadOnly(),
            date("dateOfApplication", "Date of application", LEFT),
            bool("inPerson", "In person", LEFT),
            enumField("postCounter", "Post / Counter", "postCounter", LEFT),
            text("feeReference", "Fee reference", LEFT),
            date("dateReceived", "Date received", LEFT),
            enumField("type", "Type", "type", LEFT),
            enumField("maritalStatus", "Marital status", "maritalStatus", LEFT),

            // The minor fields close the left column, exactly as in
            // power_app/screenshots/application_general_minor.png. They are NOT a separate card: all
            // four are hidden by getFieldRules for a non-minor type, so on an Adult case the left
            // column simply ends at Marital status.
            date("minorDateOfBirth", "Minor's date of birth", LEFT),
            number("ageOfMinor", "Age of minor years", LEFT),
            number("ageOfMinorMonths", "Age of minor months", LEFT),
            text("parents", "Parents", LEFT),

            enumField("privacy", "Privacy", "privacy", RIGHT).asNotNullable(),
            enumField("state", "State", "state", RIGHT).asReadOnly(),
            enumField("currentReferralState", "Current referral state", "currentReferralState", RIGHT)
                .asReadOnly(),
            enumField("urgency", "Urgency", "urgency", RIGHT).asNotNullable(),
            text("urgencyreason", "Urgency reason", RIGHT),
            text("onlineApplicationReference", "Online application reference", RIGHT),
            bool("retainPermanently", "Retain permanently", RIGHT),
            date("awaitingFurtherDocsInfo", "Awaiting further docs/info", RIGHT))),

        // Column positions replicate power_app/screenshots/application_names.png: the new name down the
        // left, the old name down the right. The source form's cells are row-major (newforename,
        // oldforename, newmiddlename, oldmiddlename, …), which is the same layout read the other way
        // round.
        //
        // The halve-in-order fallback already produced this split, since the fields happen to be listed
        // new-then-old. Positions are set anyway so the layout survives someone reordering the list --
        // an accidental match is not a spec.
        new SectionDef("Names", List.of(
            text("newForename", "New forename", LEFT),
            text("newMiddleName", "New middle name", LEFT),
            text("newSurname", "New surname", LEFT),

            text("oldForename", "Old forename", RIGHT),
            text("oldMiddleName", "Old middle name", RIGHT),
            text("oldSurname", "Old surname", RIGHT))),

        // Column positions replicate power_app/screenshots/application_address.png: the postal address
        // down the left, the two contact fields at the top right. Note this is NOT the halve-in-order
        // split, which put Postcode on the right.
        //
        // Labels drop the source form's "Applicant" prefix ("Applicant Address 1" becomes "Address line
        // 1") because the card title already says whose address it is, and the full label wraps in a
        // half-width column -- the wrapping is visible on the source screenshot's own "Applicant
        // Building No/Name".
        new SectionDef("Applicant address and contact details", List.of(
            text("applicantBuildingNoName", "Building number/name", LEFT),
            text("applicantAddress1", "Address line 1", LEFT),
            text("applicantAddress2", "Address line 2", LEFT),
            text("applicantTownCity", "Town/city", LEFT),
            text("applicantPostcode", "Postcode", LEFT),

            text("applicantEmail", "Email", RIGHT),
            text("applicantTelephone", "Telephone", RIGHT))),

        // Column positions replicate power_app/screenshots/application_repesentative.png: who they are
        // and how to reach them on the left, their postal address on the right. That is MIRRORED from
        // the applicant card above, where the address is on the left -- the source form really is
        // inconsistent between the two (its cell order is name, building, company, address1, email,
        // address2, …), and we copy the inconsistency because muscle memory is the point of this
        // exercise.
        new SectionDef("Representative address and contact details", List.of(
            text("representativeName", "Name", LEFT),
            text("representativeCompany", "Company", LEFT),
            text("representativeEmail", "Email", LEFT),
            text("representativeTelephone", "Telephone", LEFT),

            text("representativeBuildingNoName", "Building number/name", RIGHT),
            text("representativeAddress1", "Address line 1", RIGHT),
            text("representativeAddress2", "Address line 2", RIGHT),
            text("representativeTownCity", "Town/city", RIGHT),
            text("representativePostcode", "Postcode", RIGHT))),

        // `<section name="sectionNationality">` stacks all three in the left cell. The halving fallback
        // used to put Section (other) on the right, away from the Section it qualifies.
        new SectionDef("Nationality", List.of(
            enumField("nationality", "Nationality", "nationality", LEFT),
            enumField("nationalitySection", "Section", "nationalitySection", LEFT),
            text("nationalitySectionOther", "Section (other)", LEFT))),

        // `<section name="sectionDeclarations">` cells are row-major (I shall / I have subscribed, then
        // Date of declaration / And also my old); read column-wise that is I shall + Date of declaration
        // left, the two subscribed/old ones right. Grouped by column below, as the Names section is --
        // the list order also sets the stacking order on the edit form, where column-major reads better.
        new SectionDef("Declarations", List.of(
            multiselect("iShall", "I shall", "iShall", LEFT),
            date("dateOfDeclaration", "Date of declaration", LEFT),

            multiselect("iHaveSubscribed", "I have subscribed", "iHaveSubscribed", RIGHT),
            multiselect("andAlsoMyOld", "And also my old", "andAlsoMyOld", RIGHT)))));

    /** Referral to Master tab. */
    private static final TabDef REFERRAL_TAB = TabDef.of("referral", "Referral to Master", List.of(
        // Positions read out of `<section name="sectionReferral">` in the source form
        // (customizations.xml). Every field sits in the section's LEFT cell with the right one empty,
        // except the two long free-text fields, which carry `colspan="2" rowspan="5"` -- hence FULL.
        // `referToMasterChoice` has no cell in this section at all (the source keeps it on the General
        // card); it is ours, per the note on the Application tab, so it leads the left column.
        new SectionDef("Referral to Master", List.of(
            enumField("referToMasterChoice", "Refer to Master", "referToMaster", LEFT),
            date("dateReadyForMaster", "Date ready for Master", LEFT),
            date("dateReferred", "Date referred", LEFT),
            textarea("referralNotes", "Referral notes", FULL),
            text("masterName", "Master name", LEFT),
            textarea("masterDirections", "Master directions", FULL),
            enumField("masterDecision", "Master decision", "masterDecision", LEFT),
            date("masterDateOfDecision", "Master date of decision", LEFT),
            date("dateDecisionDirectionsReceived", "Date decision/directions received", LEFT),
            bool("publishToLondonGazette", "Publish to London Gazette", LEFT)))));

    /** Signature tab. */
    private static final TabDef SIGNATURE_TAB = TabDef.of("signature", "Signature", List.of(
        // `<section name="signature_tab_section_1">` puts these side by side -- the one section on the
        // later tabs that genuinely uses both cells. Positioned explicitly because the halving fallback
        // only happened to agree with it: a third field on this tab would have moved Received under
        // Requested.
        new SectionDef("Signature", List.of(
            date("signatureRequestedDate", "Signature requested date", LEFT),
            date("signatureReceivedDate", "Signature received date", RIGHT)))));

    /** Enrolment tab, including the read-only computed day count and the Gazette date. */
    private static final TabDef ENROLMENT_TAB = TabDef.of("enrolment", "Enrolment", List.of(
        // All three occupy the left cell of `<section name="sectionEnrolment">`, right cell empty -- a
        // single stack, not two columns.
        new SectionDef("Enrolment", List.of(
            date("dateEnroled", "Date enroled", LEFT),
            number("daysReceivedToEnroled", "Days received to enroled", LEFT).asReadOnly(),
            date("dateNoticeSentToLondonGazette", "Date notice sent to London Gazette", LEFT)))));

    /** Return tab. */
    private static final TabDef RETURN_TAB = TabDef.of("return", "Return", List.of(
        // `<section name="sectionReturn">`: both dates in the left cell, then `deedpoll_returnreason`
        // at `colspan="2" rowspan="5"` across the full width. A 2000-character free-text field does not
        // belong in a half column.
        new SectionDef("Return", List.of(
            date("dateReturned", "Date returned", LEFT),
            number("daysReceivedToReturned", "Days received to returned", LEFT).asReadOnly(),
            textarea("returnReason", "Return reason", FULL)))));

    /** Refund tab. */
    private static final TabDef REFUND_TAB = TabDef.of("refund", "Refund", List.of(
        // All three in the left cell of `<section name="sectionRefund">`.
        new SectionDef("Refund", List.of(
            number("refundAmount", "Refund amount", LEFT),
            date("dateOfRefund", "Date of refund", LEFT),
            text("refundReference", "Refund reference", LEFT)))));

    /** All form tabs in source order. Subgrid tabs carry no data-entry sections. */
    public static final List<TabDef> FORM_TABS = List.of(
        APPLICATION_TAB,
        TabDef.subgridTab("notes", "Notes", TabDef.Subgrid.NOTES),
        REFERRAL_TAB,
        SIGNATURE_TAB,
        ENROLMENT_TAB,
        RETURN_TAB,
        REFUND_TAB,
        TabDef.subgridTab("witnesses", "Witnesses", TabDef.Subgrid.WITNESSES),
        TabDef.subgridTab("documents", "Supporting documents", TabDef.Subgrid.DOCUMENTS));

    /** Every editable (non-read-only) field across all data-entry sections. */
    public static final List<FieldDef> EDITABLE_FIELDS = allFields().stream()
        .filter(field -> !field.readOnly())
        .toList();

    /** Lookup of a field definition by name, editable and read-only alike. */
    public static final Map<String, FieldDef> FIELD_BY_NAME = fieldByName();

    private FormFields() {
    }

    /** One option of a select control. */
    public record SelectOption(String value, String text) {
    }

    /**
     * Builds select options for a {@code LABELS} set, in the set's declaration order.
     *
     * <p>The value is the stored member name and the text is its Dynamics label, so a select posts back
     * something the schema accepts without the template having to know the mapping.
     */
    public static List<SelectOption> enumOptions(String set) {
        Map<String, String> labels = DynamicsValues.LABELS.get(set);
        if (labels == null) {
            return List.of();
        }
        return labels.entrySet().stream()
            .map(entry -> new SelectOption(entry.getKey(), entry.getValue()))
            .toList();
    }

    /** Every field on every data-entry section, in tab then section then field order. */
    public static List<FieldDef> allFields() {
        return FORM_TABS.stream()
            .flatMap(tab -> tab.sections().stream())
            .flatMap(section -> section.fields().stream())
            .toList();
    }

    private static Map<String, FieldDef> fieldByName() {
        Map<String, FieldDef> byName = new LinkedHashMap<>();
        allFields().forEach(field -> byName.put(field.name(), field));
        return Collections.unmodifiableMap(byName);
    }

    private static FieldDef text(String name, String label, CardColumn column) {
        return FieldDef.of(name, label, FieldKind.TEXT, column);
    }

    private static FieldDef textarea(String name, String label, CardColumn column) {
        return FieldDef.of(name, label, FieldKind.TEXTAREA, column);
    }

    private static FieldDef date(String name, String label, CardColumn column) {
        return FieldDef.of(name, label, FieldKind.DATE, column);
    }

    private static FieldDef number(String name, String label, CardColumn column) {
        return FieldDef.of(name, label, FieldKind.NUMBER, column);
    }

    private static FieldDef bool(String name, String label, CardColumn column) {
        return FieldDef.of(name, label, FieldKind.BOOLEAN, column);
    }

    private static FieldDef enumField(String name, String label, String enumSet, CardColumn column) {
        return FieldDef.enumOf(name, label, enumSet, column);
    }

    private static FieldDef multiselect(String name, String label, String enumSet, CardColumn column) {
        return FieldDef.multiselectOf(name, label, enumSet, column);
    }
}
