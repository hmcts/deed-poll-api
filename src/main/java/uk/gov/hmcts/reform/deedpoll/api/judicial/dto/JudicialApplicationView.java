package uk.gov.hmcts.reform.deedpoll.api.judicial.dto;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollValidation;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Nationality;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NationalitySection;

import java.util.List;

/**
 * The application behind a referral, as a Master sees it: the names, the type, the nationality, the parents of a
 * minor, and its readable supporting documents. Deliberately still not a case.
 *
 * <p><strong>Narrow by decision again (Master Sullivan's feedback, Steve, 2026-10-27).</strong> The history:
 * eight fields under decision 8 (2026-09-17); then the whole application, contact details, representative,
 * declarations and witnesses included, by the business decision of 2026-10-19
 * ({@code docs/plans/20261019-judicial-application-view-and-revised-directions.md}); and now only what the Masters
 * asked to see when directing a referral. Steve chose to narrow the data here and not only to hide it on the page,
 * so a field the Master's screen does not show never reaches a Master's browser. The components are in the order
 * the Masters asked for them, and {@code JudicialApplicationViewTest} pins that list.
 *
 * <p><strong>{@code displayReference} stays although the "General" list omits it</strong>: it is what admin captions
 * the referral, direction and withdrawal pages with, and what a Master quotes. No route accepts it.
 *
 * <p><strong>{@code parents} only for a minor's application</strong> ({@link DeedPollValidation#isMinor}). The case
 * write path already clears it on an adult case (the minor gate), but a row imported from the previous system need
 * not have been through that path, and the Masters asked for the field only on minors' applications.
 *
 * <p><strong>What stays out</strong>, and {@code JudicialApplicationViewTest} asserts no judicial DTO carries any of
 * it: the case id and reference number (the judicial surface is keyed on the referral); payment and refund data;
 * the office's processing record; and, since 2026-10-27, the state, privacy, urgency, in-person and marital status,
 * the minor's age and date of birth, every contact and representative field, the declaration and workflow dates,
 * the previous system's referral fields, the witnesses, and the application's case notes (on
 * {@code JudicialReferralDetail}).
 *
 * <p><strong>A separate type from {@code CaseDetailResponse} and never a subset of it.</strong>
 * {@code NamespaceSeparationSecurityTest} asserts that in bytecode: a field added to the caseworker response for a
 * caseworker reason must not silently appear here.
 *
 * @param displayReference the rendered case reference, or null for a seeded or imported case that has none
 * @param newForename the forename being changed to, or null
 * @param newMiddleName the middle name being changed to, or null
 * @param newSurname the surname being changed to, or null
 * @param oldForename the forename being changed from, or null
 * @param oldMiddleName the middle name being changed from, or null
 * @param oldSurname the surname being changed from, or null
 * @param type the kind of application, which decides what a lawful direction can be
 * @param nationality the applicant's nationality, or null
 * @param nationalitySection the section of the nationality legislation relied on, or null
 * @param nationalitySectionOther the section, where it is none of those listed, or null
 * @param parents the minor's parents, as one free-text field; null on an adult's application
 * @param documents the application's documents with a file this service can serve, oldest first
 */
public record JudicialApplicationView(
    String displayReference,

    String newForename,
    String newMiddleName,
    String newSurname,
    String oldForename,
    String oldMiddleName,
    String oldSurname,

    DeedPollType type,

    Nationality nationality,
    NationalitySection nationalitySection,
    String nationalitySectionOther,

    String parents,

    List<JudicialDocument> documents) {

    /**
     * The application as a Master sees it.
     *
     * @param application the case, resolved from the referral
     * @param documents its readable documents only, with their types initialised
     */
    public static JudicialApplicationView from(DeedPoll application, List<DeedPollSupportingDocument> documents) {
        return new JudicialApplicationView(
            application.getDisplayReference(),

            application.getNewForename(),
            application.getNewMiddleName(),
            application.getNewSurname(),
            application.getOldForename(),
            application.getOldMiddleName(),
            application.getOldSurname(),

            application.getType(),

            application.getNationality(),
            application.getNationalitySection(),
            application.getNationalitySectionOther(),

            DeedPollValidation.isMinor(application.getType()) ? application.getParents() : null,

            JudicialDocument.from(documents));
    }
}
