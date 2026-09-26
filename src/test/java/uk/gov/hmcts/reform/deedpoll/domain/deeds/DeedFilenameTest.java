package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The name the applicant's own computer shows.
 *
 * <p>Container-free, because none of it needs a database -- and it is worth stating separately from
 * {@code DeedDocumentServiceTest}, which does: the filename is the only part of storing a deed that a person
 * outside this service reads, and the rest of its assertions only run where Docker does.
 */
class DeedFilenameTest {

    private static final String REFERENCE = "KBDP2026-00001";

    @Test
    @DisplayName("names the adult deed by its form code, what it is, and the case reference")
    void namesTheAdultDeed() {
        assertThat(DeedFilename.of(caseWith(REFERENCE), deed(DeedTemplate.ADULT)))
            .isEqualTo("LOC020-change-of-name-deed-KBDP2026-00001.docx");
    }

    @Test
    @DisplayName("names the minor's deed as the minor's deed, not merely as a deed")
    void namesTheMinorDeed() {
        // The two files can arrive in the same household. If both were called "change of name deed" the only
        // thing telling them apart would be the reference, which the applicant has to look up.
        assertThat(DeedFilename.of(caseWith(REFERENCE), deed(DeedTemplate.MINOR)))
            .isEqualTo("LOC022-change-of-name-deed-for-a-child-KBDP2026-00001.docx");
    }

    @ParameterizedTest
    @EnumSource(DeedTemplate.class)
    @DisplayName("takes the extension from the format rather than assuming .docx")
    void takesTheExtensionFromTheFormat(DeedTemplate template) {
        // Decision 5's seam: `.docx` is a deferral, so a PDF step should change the extension in
        // DeedOutputFormat and nowhere else.
        for (DeedOutputFormat format : DeedOutputFormat.values()) {
            assertThat(DeedFilename.of(caseWith(REFERENCE), new GeneratedDeed(template, format, new byte[0])))
                .endsWith("." + format.extension());
        }
    }

    @ParameterizedTest
    @EnumSource(DeedTemplate.class)
    @DisplayName("leaves the reference out entirely when the case has none, rather than trailing a separator")
    void leavesTheReferenceOutWhenTheCaseHasNone(DeedTemplate template) {
        // A seeded or imported case can have no displayReference. "...deed-.docx" or "...deed-null.docx" is
        // what an applicant would otherwise be sent.
        String withoutReference = DeedFilename.of(caseWith(null), deed(template));

        assertThat(withoutReference).doesNotContain("null").doesNotContain("-.");
        assertThat(withoutReference).isEqualTo(
            DeedFilename.of(caseWith(""), deed(template)));
    }

    @Test
    @DisplayName("cleans the reference rather than trusting it, so a stored value cannot become a path")
    void cleansTheReference() {
        // The column holds a generated reference today. It is still a column, and this name reaches a
        // Content-Disposition header and a Notify attachment.
        String filename = DeedFilename.of(caseWith("../../etc/passwd"), deed(DeedTemplate.ADULT));

        assertThat(filename).isEqualTo("LOC020-change-of-name-deed-etc-passwd.docx");
        assertThat(filename).doesNotContain("/").doesNotContain("..");
    }

    @ParameterizedTest
    @EnumSource(DeedTemplate.class)
    @DisplayName("is nothing like a storage key")
    void isNothingLikeAStorageKey(DeedTemplate template) {
        String filename = DeedFilename.of(caseWith(REFERENCE), deed(template));

        // The whole point of naming it: the store keys blobs by UUID, and the download offers this back.
        assertThat(filename).contains(template.formCode()).contains(REFERENCE);
        assertThat(filename).matches("[A-Za-z0-9.-]+");
    }

    private static DeedPoll caseWith(String displayReference) {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDisplayReference(displayReference);
        return deedPoll;
    }

    private static GeneratedDeed deed(DeedTemplate template) {
        // The bytes are irrelevant here: the name comes from the case and the template.
        return new GeneratedDeed(template, DeedOutputFormat.DOCX, new byte[0]);
    }
}
