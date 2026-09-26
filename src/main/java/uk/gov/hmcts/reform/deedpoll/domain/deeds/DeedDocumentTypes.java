package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.enums.DynamicsValues;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;

import java.util.List;

/**
 * What a generated deed is filed as: the {@code deedpoll_supportingdocumenttype} row each template's output
 * belongs to.
 *
 * <p><strong>Two rows, not one.</strong> LOC020 and LOC022 are different documents -- they carry different
 * fields, they are sworn by different people, and a caseworker reading a case file needs to see which one was
 * produced -- so each has its own type rather than both being filed as "deed". That is also what makes
 * {@code applicableTypes} meaningful: a row that applied to every case type would say nothing.
 *
 * <p><strong>The rows are created on first use rather than seeded by a migration.</strong> Nothing seeds this
 * table -- {@code V3__children.sql} creates it empty and no migration has ever put a row in it -- so a deed
 * generated in a fresh environment has no type to reference, and a caseworker cannot create one because the
 * type write operations deliberately have no HTTP surface ({@code DocumentTypeService}). A migration insert
 * would not remove the runtime check either: the row could still be missing, and a service that then threw
 * would refuse to send an applicant their deed over reference data. So {@code DeedDocumentService} finds the
 * row and creates it when it is absent, which is idempotent and self-healing.
 *
 * <p><strong>{@link #name} is the key that lookup uses, and that is safe only while nothing can rename a
 * type.</strong> There is no rename endpoint, and the row's id is what a stored document actually references,
 * so an existing deed keeps its type whatever happens to the name. But a future rename operation would make a
 * renamed row invisible here -- the next deed would create a second type row, and worse, the "has this case
 * already got a deed" lookup would stop seeing the first one. If that endpoint is ever exposed, this key has
 * to become a stable column rather than the display name.
 *
 * <p><strong>{@link #caseTypes} states which case types each deed is <em>for</em>, and it is the only copy of
 * that in this repository.</strong> Decision 3 of {@code docs/plans/notify-4b-generate-the-deed.md} is
 * emphatic that "which {@code DeedPollType} takes which deed" must be stated once -- an
 * {@code AdultTransgender} applicant swearing a minor's deed is the failure it guards against -- and
 * {@link DeedTemplate} therefore says nothing about it. The document-type row needs the answer, so it is here,
 * and the email routing this plan's task 4 builds must be derived from or pinned against this rather than
 * restating it.
 *
 * <p>{@code DeedPollType.DeedOfRelinquishment} appears on neither list, deliberately: whether that journey
 * needs a generated deed at all is open (notify-4a's flag 5), and guessing would file a document under a deed
 * the court may not use. {@code Unknown} appears on neither either, because there is no safe default.
 */
final class DeedDocumentTypes {

    private DeedDocumentTypes() {
    }

    /**
     * The type row's name, which is what a caseworker sees in the documents grid.
     *
     * <p>Derived from the template rather than written out, so the form code and the wording cannot drift from
     * {@link DeedTemplate}'s. Well inside the column's 100 characters: the longer of the two is 38.
     */
    static String name(DeedTemplate template) {
        return template.formCode() + " " + template.displayName();
    }

    /**
     * The case types this deed is for.
     *
     * <p>A {@code switch} with no {@code default}, so a third template cannot be added without someone saying
     * who it is for -- which is the same reason decision 3 gives for the routing switch itself.
     */
    static List<DeedPollType> caseTypes(DeedTemplate template) {
        return switch (template) {
            case ADULT -> List.of(DeedPollType.Adult, DeedPollType.AdultTransgender);
            case MINOR -> List.of(DeedPollType.Minor, DeedPollType.MinorTransgender);
        };
    }

    /**
     * {@link #caseTypes} as the {@code integer[]} the column holds.
     *
     * <p>The Dynamics option-set integers, from {@link DynamicsValues} rather than from {@link Enum#ordinal()}
     * -- the column's other values came out of Dataverse, so a member inserted in the wrong place must not
     * silently renumber what this row means.
     */
    static Integer[] applicableTypes(DeedTemplate template) {
        return caseTypes(template).stream()
            .map(DynamicsValues.DEED_POLL_TYPE_VALUE::get)
            .toArray(Integer[]::new);
    }
}
