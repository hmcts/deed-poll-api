package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Which of the court's deeds a case takes, given its type -- and "none" where that is the honest answer.
 *
 * <p><strong>Derived from {@link DeedDocumentTypes#caseTypes}, never restated.</strong> Decision 3 of
 * {@code docs/plans/notify-4b-generate-the-deed.md} is emphatic that the adult/minor lists appear once in this
 * repository, because an {@code AdultTransgender} applicant swearing a minor's deed is the failure it guards
 * against -- and a second copy of the lists is exactly how that happens. So this class holds no list: it asks
 * which template <em>declares</em> the type and answers with that one. What it adds is the part the document-type
 * row cannot express, which is that two of the six members take no deed at all.
 *
 * <p><strong>A {@code switch} with no {@code default}, so a seventh {@link DeedPollType} member does not
 * silently inherit somebody else's deed</strong> -- the convention {@code ResolvedValues.isMinor} and
 * {@code CaseEmailService.cause} already follow. The four that have a deed say only <em>that</em> they have one;
 * which one is still {@link DeedDocumentTypes}'.
 *
 * <p><strong>{@link DeedPollType#DeedOfRelinquishment} and {@link DeedPollType#Unknown} take none, for two
 * different reasons.</strong> A relinquishment is a different legal instrument and nobody has identified the
 * forms it needs ({@code notify-4a} flag 5); {@code Unknown} means the type has not been established, and there
 * is no safe default because the wrong deed means an applicant swears the wrong instrument. Whoever asks has to
 * say which of the two they are looking at, so the sentence a caseworker reads is not shared between them.
 *
 * <p>A null type answers empty, and it is {@code Unknown}'s answer rather than a fourth case: a case whose type
 * has never been set is one this service cannot tell which deed belongs to, which is the same position.
 */
public final class DeedRouting {

    private DeedRouting() {
    }

    /**
     * The deed a case of this type takes, or empty if it takes none.
     *
     * @param type the case's {@code type} column, which may be null
     * @throws IllegalStateException if {@link DeedDocumentTypes#caseTypes} does not declare the type exactly
     *     once -- which is a contradiction between this switch and those lists rather than a caller's mistake
     */
    public static Optional<DeedTemplate> deedFor(DeedPollType type) {
        if (type == null) {
            return Optional.empty();
        }
        return switch (type) {
            case Adult, AdultTransgender, Minor, MinorTransgender -> Optional.of(declaredFor(type));
            // Its own arm rather than sharing Unknown's, so that the two cannot be given one sentence by
            // accident: one is waiting on somebody identifying the forms, the other on a caseworker setting the
            // type. See the class comment.
            case DeedOfRelinquishment -> Optional.empty();
            case Unknown -> Optional.empty();
        };
    }

    /**
     * The one template whose {@link DeedDocumentTypes#caseTypes} names this type.
     *
     * <p>Searched rather than mapped, which is what makes this class a derivation instead of a second copy. The
     * two failures are raised rather than defaulted: none means the switch above claims a deed the document-type
     * rows do not, and two means one case type would take two legal instruments. Neither is reachable while
     * {@code DeedDocumentTypesTest} passes -- it asserts both properties directly -- and both are the sort of
     * disagreement that is invisible until an applicant has signed the wrong thing.
     */
    private static DeedTemplate declaredFor(DeedPollType type) {
        List<DeedTemplate> declaring = Arrays.stream(DeedTemplate.values())
            .filter(template -> DeedDocumentTypes.caseTypes(template).contains(type))
            .toList();
        if (declaring.size() != 1) {
            throw new IllegalStateException(
                "DeedPollType " + type + " is declared by " + declaring.size() + " deed templates, and a case "
                    + "takes exactly one deed: see DeedDocumentTypes.caseTypes");
        }
        return declaring.getFirst();
    }
}
