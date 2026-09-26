package uk.gov.hmcts.reform.deedpoll.notify;

import uk.gov.hmcts.reform.deedpoll.domain.deeds.DeedRouting;
import uk.gov.hmcts.reform.deedpoll.domain.deeds.DeedTemplate;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;

import java.util.Optional;

/**
 * Which forms email a case is offered, and which of the court's deeds that email carries.
 *
 * <p><strong>The only place {@link EmailTemplate} and {@link DeedTemplate} meet, and it holds no case-type
 * list.</strong> Decision 3 of {@code docs/plans/notify-4b-generate-the-deed.md} requires that "which
 * {@code DeedPollType} takes which deed" is stated once; {@code DeedDocumentTypes.caseTypes} is where, because the
 * document-type row's {@code applicableTypes} column cannot be filled without it, and {@link DeedRouting} is the
 * derivation of it. So {@link #emailFor} routes a case type to a <em>deed</em> through those, and only then turns
 * the deed into the email that carries it. An adult case reaching the minor email would be an applicant swearing
 * a deed about a child who does not exist, and the way that happens is somebody writing the lists twice.
 *
 * <p><strong>{@link #deedOf} is a {@code switch} with no {@code default}</strong>, so a sixth
 * {@link EmailTemplate} member does not silently inherit a deed: it fails to compile until somebody says whether
 * it carries one. That matters most for the relinquishment template, whose documents are not identified -- it must
 * answer empty rather than fall through to LOC020.
 *
 * <p>Package-private: this is the notify package's own routing, not a general answer to "what does this case
 * need". Everything outside it asks {@link DeedRouting}.
 */
final class FormsForCompletion {

    private FormsForCompletion() {
    }

    /**
     * The forms email a case of this type is offered, or empty if it is offered none.
     *
     * <p>Empty for {@link DeedPollType#DeedOfRelinquishment}, {@link DeedPollType#Unknown} and an unset type, for
     * the reasons {@link DeedRouting} gives -- and the caller has to say which of them it is looking at, because
     * the sentence a caseworker reads is different for each.
     */
    static Optional<EmailTemplate> emailFor(DeedPollType type) {
        return DeedRouting.deedFor(type).map(FormsForCompletion::emailCarrying);
    }

    /**
     * The deed this email carries, or empty if it carries none.
     *
     * <p>The inverse of {@link #emailCarrying}, and the one a send uses: it is what
     * {@code DeedDocumentService.storeDeed} is asked for.
     */
    static Optional<DeedTemplate> deedOf(EmailTemplate template) {
        return switch (template) {
            case FORMS_FOR_COMPLETION_ADULT -> Optional.of(DeedTemplate.ADULT);
            case FORMS_FOR_COMPLETION_MINOR -> Optional.of(DeedTemplate.MINOR);
            // Three emails about the state of an application rather than about the deed: a refusal, a request to
            // confirm, and a chase for documents. None carries a file, and their documentKeys() are empty.
            case APPLICATION_REJECTED, REQUEST_CONFIRMATION_TO_PROCEED, CHASE_MISSING_DOCUMENTS ->
                Optional.empty();
        };
    }

    /**
     * The email that carries this deed.
     *
     * <p>A {@code switch} with no {@code default} for the same reason {@link #deedOf} is one, and the pair is
     * asserted to be mutually inverse in {@code FormsForCompletionTest} -- two one-way switches that disagreed
     * would offer a case one email and generate the other email's deed for it.
     */
    private static EmailTemplate emailCarrying(DeedTemplate deed) {
        return switch (deed) {
            case ADULT -> EmailTemplate.FORMS_FOR_COMPLETION_ADULT;
            case MINOR -> EmailTemplate.FORMS_FOR_COMPLETION_MINOR;
        };
    }
}
