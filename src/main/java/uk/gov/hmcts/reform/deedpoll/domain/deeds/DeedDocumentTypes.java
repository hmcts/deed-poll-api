package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.enums.DynamicsValues;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;

import java.util.List;
import java.util.Set;

/**
 * What a generated deed is filed as: the {@code deedpoll_supportingdocumenttype} row each template's output
 * belongs to.
 *
 * <p><strong>Two rows, not one.</strong> LOC020 and LOC022 are different documents -- they carry different
 * fields, they are sworn by different people, and a caseworker reading a case file needs to see which one was
 * produced -- so each has its own type rather than both being filed as "deed". That is also what makes
 * {@code applicableTypes} meaningful: a row that applied to every case type would say nothing.
 *
 * <p><strong>The rows are seeded, and still created on first use.</strong> {@code V39} seeds the business's
 * supporting-document types, these two included, under the names below. The runtime check stays because the row
 * could still be missing, and a service that threw would refuse to send an applicant their deed over reference
 * data. So {@code DeedDocumentService} finds the row and creates it when it is absent, which is idempotent and
 * self-healing.
 *
 * <p><strong>The names are the business's, and {@link #legacyName} is the one each used to have.</strong> Before
 * V39 the api named these rows "LOC020 Change of name deed" and "LOC022 Change of name deed for a child". V39
 * renames any such row in place, keeping its id and the documents filed under it. Every lookup also accepts the
 * legacy name for one release. Without that, a pod still on the old code during a rolling deploy could create a
 * second, legacy-named row, and "has this case already got a deed" would stop seeing the deed filed under it.
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
     * <p>The business's own names, as V39 seeds them, so a caseworker sees one LOC020 and one LOC022 type in the
     * list rather than the api's and the business's side by side. Well inside the column's 100 characters.
     */
    static String name(DeedTemplate template) {
        return switch (template) {
            case ADULT -> "Change of Name Deed (LOC020)";
            case MINOR -> "Change of Name Deed for a minor (LOC022)";
        };
    }

    /**
     * The name the row had before V39, which a row created by a pod still on the old code would still carry.
     *
     * <p>Kept so that every lookup sees such a row for one release. Remove it, with {@link #names}, once every
     * environment has run V39 and no old pod can still be serving.
     */
    static String legacyName(DeedTemplate template) {
        return template.formCode() + " " + template.displayName();
    }

    /**
     * {@link #name} and {@link #legacyName}: every name a row of this deed's type can carry.
     */
    static Set<String> names(DeedTemplate template) {
        return Set.of(name(template), legacyName(template));
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
