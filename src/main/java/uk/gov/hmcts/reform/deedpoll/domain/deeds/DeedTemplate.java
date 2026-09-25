package uk.gov.hmcts.reform.deedpoll.domain.deeds;

/**
 * A deed the court seals, as a Word template vendored in this repo.
 *
 * <p>Two members, because there are two deeds that carry case data: LOC020 for an adult and LOC022 for a
 * minor. The other five documents the <em>forms for completion</em> emails send are blank forms for a
 * declarant and a commissioner of oaths to complete, and are held by Notify rather than generated here --
 * see {@code docs/plans/notify-4b-generate-the-deed.md}, evidence 2.
 *
 * <p><strong>Which {@code DeedPollType} takes which deed is deliberately not stated here.</strong> That rule
 * has six inputs and three answers -- one of which is "no deed at all" -- and it belongs with the email that
 * carries the document ({@code notify-4a} task 2, this plan's decision 3). A second copy of it in this package
 * is exactly how an {@code AdultTransgender} applicant ends up swearing a minor's deed. The generator therefore
 * takes the template as an argument and asks no questions about the case's type.
 *
 * <p><strong>The file is a copy, not a reference.</strong> Both are byte-identical copies of
 * {@code deed-poll-admin}'s {@code .business_context/doc_templates/}, because a deployed pod has only this
 * artifact. {@code src/main/resources/deed-templates/README.md} records the provenance and the cost.
 */
public enum DeedTemplate {

    /**
     * LOC020, the adult change of name deed. 30 content controls binding 18 distinct fields.
     *
     * <p>The wider of the two: it prints the applicant's address, their email and their marital status, none
     * of which the minor's deed asks for.
     */
    ADULT(
        "deed-templates/loc020-adult-change-of-name-deed.docx",
        "LOC020",
        "Change of name deed"),

    /**
     * LOC022, the minor's change of name deed. 13 content controls binding 13 distinct fields.
     *
     * <p>Narrower than the adult deed, and not merely a subset: it prints the child's age and the parents,
     * which LOC020 has no place for.
     */
    MINOR(
        "deed-templates/loc022-minor-change-of-name-deed.docx",
        "LOC022",
        "Change of name deed for a child");

    private final String resourcePath;
    private final String formCode;
    private final String displayName;

    DeedTemplate(String resourcePath, String formCode, String displayName) {
        this.resourcePath = resourcePath;
        this.formCode = formCode;
        this.displayName = displayName;
    }

    /**
     * Where the vendored {@code .docx} sits on the classpath, without a leading slash.
     */
    public String resourcePath() {
        return resourcePath;
    }

    /** The court's form number, which is what a caseworker and an applicant both call this document. */
    public String formCode() {
        return formCode;
    }

    /** The document's own title, for a filename or a document-type row. */
    public String displayName() {
        return displayName;
    }
}
