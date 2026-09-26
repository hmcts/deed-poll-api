package uk.gov.hmcts.reform.deedpoll.notify;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the two <em>forms for completion</em> templates need, resolved from the case alone.
 *
 * <p>This is the email that starts the signing: it tells the applicant which forms to print, links the deed this
 * service generated for them, and says where to post the signed papers. Templates ① and ② in
 * {@code NOTIFY_TEMPLATE_TAXONOMY.md}, and both read from this one record -- they differ in their words, in which
 * forms they name and in which deed the link resolves to, and in none of the values below.
 *
 * <p><strong>No slots at all, which is decision 7 and was re-checked against the source documents.</strong>
 * Neither supplied {@code .docx} carries a caseworker marker of any style -- no {@code [Enter …]} and no
 * {@code {insert …}}, unlike ③ and ④ -- so there is nothing for a caseworker to write and
 * {@link EmailSlots#parse} refuses any slot a client sends. A free-text box would be a template change in
 * notify-0 first: a slot the Notify template has no placeholder for makes the send a 400.
 *
 * <p><strong>It addresses the applicant by their OLD name, deliberately.</strong> The document opens
 * <em>"Regarding the application to enrol a deed poll change of name for {@code deedpoll_oldforename}
 * {@code deedpoll_oldsurname}"</em> -- the new name is not theirs until the deed is sealed, and the deed this
 * email carries is the instrument that changes it. Do not "improve" this to the new name.
 *
 * <p><strong>The four common keys are spelled exactly as ③, ④ and ⑤ spell them</strong>, which is why they are
 * not new names: the source documents reference the same {@code deedpoll_referencenumber},
 * {@code deedpoll_onlineapplicationreference}, {@code deedpoll_oldforename} and {@code deedpoll_oldsurname}, so a
 * content designer moving a paragraph between templates renames nothing. {@code deedpoll_applicantemail} is the
 * <strong>recipient</strong> rather than a placeholder -- it is the address the outbox row is sent to -- and is
 * deliberately not a key here.
 *
 * <p><strong>{@link #publishToLondonGazette} is a boolean because the template branches on it</strong> (decision
 * 10). The source hedges the Gazette sentence inline -- <em>"if your name change is being published"</em> --
 * because a Word document cannot branch; Notify can, as {@code ((publish_to_london_gazette??…))}. The column is a
 * non-null {@code boolean} defaulting to true, so the key is always present and never empty.
 *
 * <p><strong>{@link #deedDocument} is a document <em>reference</em>, and the drain is what turns it into a
 * download.</strong> The frozen row stores the supporting-document id; immediately before {@code sendEmail},
 * {@link DocumentUploads} swaps it for {@code NotificationClient.prepareUpload}. A row holding a Notify upload
 * token would be a row that could not be re-sent and a token at rest in the database.
 *
 * <p>A record, so the personalisation is enumerable -- {@code NotificationSecurityTest} asserts {@link #toMap()}
 * against the components, so a component added here without a key fails a test rather than silently never
 * reaching the applicant.
 *
 * @param referenceNumber the case reference as the applicant has seen it
 * @param onlineApplicationReference the reference the applicant quoted when applying online. <strong>Nullable,
 *     and resolved to an empty string rather than omitted</strong>: Notify refuses a send whose personalisation is
 *     missing a key the template names
 * @param oldForename the name being changed from, for the "Regarding..." line
 * @param oldSurname the surname being changed from
 * @param publishToLondonGazette whether the Gazette sentence is rendered
 * @param deedDocument the id of this case's generated deed, which the drain resolves to a download link
 */
public record FormsForCompletionPersonalisation(
    String referenceNumber,
    String onlineApplicationReference,
    String oldForename,
    String oldSurname,
    boolean publishToLondonGazette,
    String deedDocument) {

    public static final String REFERENCE_NUMBER = "reference_number";
    public static final String ONLINE_APPLICATION_REFERENCE = "online_application_reference";
    public static final String OLD_FORENAME = "old_forename";
    public static final String OLD_SURNAME = "old_surname";
    public static final String PUBLISH_TO_LONDON_GAZETTE = "publish_to_london_gazette";

    /**
     * The key the generated deed's download link is rendered under.
     *
     * <p>Named for the <em>role</em> the document plays rather than for its LOC code, so ① and ② use one key for
     * LOC020 and LOC022 respectively and a form reissued under a new code is a change to one mapping rather than
     * to two Notify templates and an enum. Declared as this template's only {@link EmailTemplate#documentKeys()}
     * entry, which is what keeps correspondence history from mistaking an arbitrary personalisation value for a
     * document id.
     *
     * <p><strong>The five blank forms are not keys here.</strong> They are attached to the Notify template
     * itself -- settled 2026-09-25 -- so this service never holds their bytes and never names them. The deed is
     * the only document api supplies.
     */
    public static final String DEED_DOCUMENT = "deed_document";

    /**
     * What a preview puts where the download link goes.
     *
     * <p><strong>A preview must not generate the deed, which is why this exists.</strong>
     * {@code CaseEmailService.preview} is a read: it writes no outbox row and changes no state, and decision 6
     * makes generating the deed a once-per-case write with a stored blob behind it -- so previewing would either
     * have to widen a read-only transaction or silently fail to persist. It also cannot show the real thing
     * anyway: the link does not exist until Notify has been given the bytes, so there is nothing to render.
     *
     * <p>Prose rather than an empty string, so a caseworker reading a preview can see where the link will appear
     * instead of finding a gap and wondering whether the deed is missing.
     */
    public static final String DEED_NOT_YET_GENERATED = "[a download link to the applicant's deed]";

    /**
     * The keys a client may never set, in the order {@link #toMap()} writes them.
     *
     * <p>{@link #DEED_DOCUMENT} is among them, and that is the point of listing it: it is resolved from the case
     * like any other value, so a client naming it is refused by name rather than being allowed to nominate which
     * document an applicant is sent.
     */
    public static final List<String> RESOLVED_KEYS = List.of(
        REFERENCE_NUMBER, ONLINE_APPLICATION_REFERENCE, OLD_FORENAME, OLD_SURNAME, PUBLISH_TO_LONDON_GAZETTE,
        DEED_DOCUMENT);

    /**
     * The one document key either template carries -- see {@link #DEED_DOCUMENT}.
     */
    public static final List<String> DOCUMENT_KEYS = List.of(DEED_DOCUMENT);

    /**
     * Resolves the email's content from the case and the reference of the deed it carries.
     *
     * @param deedPoll the case the email is about
     * @param deedDocument the stored deed's id for a send, or {@link #DEED_NOT_YET_GENERATED} for a preview
     */
    public static FormsForCompletionPersonalisation resolve(DeedPoll deedPoll, String deedDocument) {
        return new FormsForCompletionPersonalisation(
            // displayReference, never the `referencenumber` integer: the serial is an internal component of the
            // rendered reference and is not what the applicant was ever shown -- and it is the same value the
            // deed itself prints, so the email and the document agree.
            ResolvedValues.text(deedPoll.getDisplayReference()),
            ResolvedValues.text(deedPoll.getOnlineApplicationReference()),
            ResolvedValues.text(deedPoll.getOldForename()),
            ResolvedValues.text(deedPoll.getOldSurname()),
            deedPoll.isPublishToLondonGazette(),
            ResolvedValues.text(deedDocument));
    }

    /**
     * The personalisation exactly as Notify receives it, and exactly as the outbox row stores it.
     *
     * <p>A {@link LinkedHashMap} so the stored {@code jsonb} reads in this order rather than in hash order.
     *
     * <p><strong>Every key is present, even when the underlying value was null.</strong> Notify refuses a send
     * whose personalisation is missing a placeholder the template uses, so an absent key would turn a blank column
     * into a permanent failure on the drain; an empty string renders as nothing, which is what a blank column
     * means. The Gazette flag is a {@code Boolean} rather than text because the template's conditional tests it.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> personalisation = new LinkedHashMap<>();
        personalisation.put(REFERENCE_NUMBER, referenceNumber);
        personalisation.put(ONLINE_APPLICATION_REFERENCE, onlineApplicationReference);
        personalisation.put(OLD_FORENAME, oldForename);
        personalisation.put(OLD_SURNAME, oldSurname);
        personalisation.put(PUBLISH_TO_LONDON_GAZETTE, publishToLondonGazette);
        personalisation.put(DEED_DOCUMENT, deedDocument);
        return personalisation;
    }
}
