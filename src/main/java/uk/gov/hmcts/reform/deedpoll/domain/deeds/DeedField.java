package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.enums.DynamicsValues;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * A field the deed templates bind, and where its value comes from.
 *
 * <p>One member per distinct Dataverse element name the two templates' content controls name in their XPath --
 * 18 on LOC020 across 30 controls, 13 on LOC022 across 13. The element name is the whole of the contract with
 * the template: {@code DeedControls} reads the name out of each binding's {@code w:xpath} and looks it up here,
 * so a template re-authored with a field this enum does not know fails the generate rather than printing a
 * blank where a name should be.
 *
 * <p><strong>The witness blocks are deliberately absent.</strong> Both deeds leave them empty because witnesses
 * sign by hand on paper, so nothing in {@code DeedPollWitness} is merged into either document. That is worth
 * knowing before somebody adds it.
 *
 * <p>Three of these mappings were inferred from the template and then confirmed, and the reasoning is recorded
 * because the element names do not say it:
 *
 * <ul>
 *   <li>{@code deedpoll_referencenumber} is {@code displayReference}, the rendered {@code KBDP2026-00001}, and
 *       never the {@code referencenumber} integer -- the serial is an internal component of the reference and is
 *       not what the applicant was ever shown.</li>
 *   <li>{@code deedpoll_status} is {@code maritalStatus}. Dataverse named the column {@code status} and the
 *       supplied data part holds no {@code deedpoll_maritalstatus} element at all, so there is no other
 *       candidate; the adult deed prints the control under the label "Status", between "Surname(s)" and
 *       "Nationality" in the applicant's own details, which a record-state code would not be. Dataverse's own
 *       {@code statuscode} is a different element and is bound by neither deed.</li>
 *   <li>{@code deedpoll_date} is {@code dateReceived} -- see {@link DeedValues#date} for why it is not
 *       {@code createdOn}, and why a null prints nothing.</li>
 * </ul>
 */
enum DeedField {

    /** The reference the applicant and the caseworker both recognise, printed in the header. */
    REFERENCE_NUMBER("deedpoll_referencenumber", deedPoll -> DeedValues.text(deedPoll.getDisplayReference()),
        DeedTemplate.ADULT, DeedTemplate.MINOR),

    /**
     * The application's date.
     *
     * <p><strong>The two deeds label this same bound field differently</strong>, and a content designer should
     * see that once rather than discover it in a complaint: LOC020 prints it under "Date you completed the
     * form", LOC022 under "Date the deed is made". {@code dateReceived} is a fair reading of the first and a
     * poor one of the second -- the deed is made when the applicant signs it, which is after this file is
     * generated -- but no column holds a date that has not happened yet, so it is the best available answer for
     * both.
     */
    DATE("deedpoll_date", deedPoll -> DeedValues.date(deedPoll.getDateReceived()),
        DeedTemplate.ADULT, DeedTemplate.MINOR),

    /** The new forename, printed three times on the adult deed and once on the minor's. */
    NEW_FORENAME("deedpoll_newforename", deedPoll -> DeedValues.text(deedPoll.getNewForename()),
        DeedTemplate.ADULT, DeedTemplate.MINOR),

    /** The new middle name. */
    NEW_MIDDLE_NAME("deedpoll_newmiddlename", deedPoll -> DeedValues.text(deedPoll.getNewMiddleName()),
        DeedTemplate.ADULT, DeedTemplate.MINOR),

    /** The new surname. */
    NEW_SURNAME("deedpoll_newsurname", deedPoll -> DeedValues.text(deedPoll.getNewSurname()),
        DeedTemplate.ADULT, DeedTemplate.MINOR),

    /** The first line of the applicant's address: building number or name. Adult deed only. */
    APPLICANT_BUILDING_NO_NAME("deedpoll_applicantbuildingnoname",
        deedPoll -> DeedValues.text(deedPoll.getApplicantBuildingNoName()), DeedTemplate.ADULT),

    /** The applicant's first address line. Adult deed only. */
    APPLICANT_ADDRESS1("deedpoll_applicantaddress1",
        deedPoll -> DeedValues.text(deedPoll.getApplicantAddress1()), DeedTemplate.ADULT),

    /** The applicant's second address line. Adult deed only. */
    APPLICANT_ADDRESS2("deedpoll_applicantaddress2",
        deedPoll -> DeedValues.text(deedPoll.getApplicantAddress2()), DeedTemplate.ADULT),

    /** The applicant's town or city. Adult deed only. */
    APPLICANT_TOWN_CITY("deedpoll_applicanttowncity",
        deedPoll -> DeedValues.text(deedPoll.getApplicantTownCity()), DeedTemplate.ADULT),

    /** The applicant's postcode. Adult deed only. */
    APPLICANT_POSTCODE("deedpoll_applicantpostcode",
        deedPoll -> DeedValues.text(deedPoll.getApplicantPostcode()), DeedTemplate.ADULT),

    /**
     * The applicant's email address, printed on the adult deed under "Your email address (if you have one)".
     *
     * <p>Printed here and also the address the <em>forms for completion</em> email is sent to. Those are two
     * different uses of one column and neither implies the other: the deed prints what the applicant gave, and
     * a blank is what the template's own "(if you have one)" allows for.
     */
    APPLICANT_EMAIL("deedpoll_applicantemail",
        deedPoll -> DeedValues.text(deedPoll.getApplicantEmail()), DeedTemplate.ADULT),

    /** The old forename, printed three times on the adult deed and once on the minor's. */
    OLD_FORENAME("deedpoll_oldforename", deedPoll -> DeedValues.text(deedPoll.getOldForename()),
        DeedTemplate.ADULT, DeedTemplate.MINOR),

    /** The old middle name. */
    OLD_MIDDLE_NAME("deedpoll_oldmiddlename", deedPoll -> DeedValues.text(deedPoll.getOldMiddleName()),
        DeedTemplate.ADULT, DeedTemplate.MINOR),

    /** The old surname. */
    OLD_SURNAME("deedpoll_oldsurname", deedPoll -> DeedValues.text(deedPoll.getOldSurname()),
        DeedTemplate.ADULT, DeedTemplate.MINOR),

    /** The applicant's marital status, printed under "Status". Adult deed only -- see this enum's javadoc. */
    STATUS("deedpoll_status",
        deedPoll -> DeedValues.label(deedPoll.getMaritalStatus(), DynamicsValues.MARITAL_STATUS_LABEL),
        DeedTemplate.ADULT),

    /**
     * The citizenship held, as its label: "British Citizen", not {@code BritishCitizen}.
     */
    NATIONALITY("deedpoll_nationality",
        deedPoll -> DeedValues.label(deedPoll.getNationality(), DynamicsValues.NATIONALITY_LABEL),
        DeedTemplate.ADULT, DeedTemplate.MINOR),

    /**
     * The British Nationality Act section, as its label.
     *
     * <p><strong>The label reads oddly in place and is nonetheless right.</strong> The body text around the
     * control is "Under section&nbsp;… of the British Nationality Act 1981", so the label "Section 1" renders
     * as "Under section Section 1 of the …". That is what Dataverse printed, because it bound the control to
     * the option's label, so it is what the existing process produced and what this port must reproduce. The
     * alternative -- printing {@code Section1} -- is not better, it is merely differently wrong. Changing the
     * words is a change to the court's template and belongs with a content designer.
     */
    NATIONALITY_SECTION("deedpoll_section",
        deedPoll -> DeedValues.label(deedPoll.getNationalitySection(), DynamicsValues.NATIONALITY_SECTION_LABEL),
        DeedTemplate.ADULT, DeedTemplate.MINOR),

    /** Whatever the applicant wrote when no listed section applied. */
    NATIONALITY_SECTION_OTHER("deedpoll_sectionother",
        deedPoll -> DeedValues.text(deedPoll.getNationalitySectionOther()),
        DeedTemplate.ADULT, DeedTemplate.MINOR),

    /** The child's age in whole years. Minor deed only. */
    AGE_OF_MINOR("deedpoll_ageofminor", deedPoll -> DeedValues.number(deedPoll.getAgeOfMinor()),
        DeedTemplate.MINOR),

    /** The parents, as the application names them. Minor deed only. */
    PARENTS("deedpoll_parents", deedPoll -> DeedValues.text(deedPoll.getParents()),
        DeedTemplate.MINOR);

    private static final Map<String, DeedField> BY_ELEMENT = byElement();

    private final String element;
    private final Function<DeedPoll, String> resolver;
    private final Set<DeedTemplate> templates;

    DeedField(String element, Function<DeedPoll, String> resolver, DeedTemplate... templates) {
        this.element = element;
        this.resolver = resolver;
        // Set.of rather than EnumSet, because it refuses a duplicate argument: a field declared as bound by
        // the adult template twice is a typo, and EnumSet would accept it silently.
        this.templates = Set.of(templates);
    }

    /**
     * The Dataverse element name, which is what a content control's {@code w:xpath} names.
     */
    String element() {
        return element;
    }

    /** Whether this field is bound by the given template. */
    boolean boundBy(DeedTemplate template) {
        return templates.contains(template);
    }

    /** This field's value for a case, as the deed prints it -- never null, empty for an unset column. */
    String valueFor(DeedPoll deedPoll) {
        return resolver.apply(deedPoll);
    }

    /** The field a content control's element name refers to, or empty when this enum does not know it. */
    static Optional<DeedField> forElement(String element) {
        return Optional.ofNullable(BY_ELEMENT.get(element));
    }

    /**
     * Every field a template binds, in declaration order.
     *
     * <p>Declaration order is roughly the order the adult deed prints them, so the data part the generator
     * writes reads like the document rather than like a hash.
     */
    static List<DeedField> fieldsFor(DeedTemplate template) {
        return Arrays.stream(values()).filter(field -> field.boundBy(template)).toList();
    }

    /** A case's values for every field a template binds, in declaration order. */
    static Map<DeedField, String> resolve(DeedTemplate template, DeedPoll deedPoll) {
        Map<DeedField, String> values = new LinkedHashMap<>();
        for (DeedField field : fieldsFor(template)) {
            values.put(field, field.valueFor(deedPoll));
        }
        return Collections.unmodifiableMap(values);
    }

    private static Map<String, DeedField> byElement() {
        Map<String, DeedField> out = new LinkedHashMap<>();
        for (DeedField field : values()) {
            DeedField clash = out.put(field.element(), field);
            if (clash != null) {
                throw new IllegalStateException(
                    "Two fields bind " + field.element() + ": " + clash.name() + " and " + field.name());
            }
        }
        return Collections.unmodifiableMap(out);
    }
}
