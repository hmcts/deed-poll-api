package uk.gov.hmcts.reform.deedpoll.domain.deeds;

/**
 * What a generated deed is a file of.
 *
 * <p><strong>One member, and the enum exists because there will be a second.</strong> {@code .docx} was chosen
 * on 2026-09-25 because it duplicates the process the Power App already ran -- the applicant downloads, prints
 * and signs in wet ink, and a Word document prints -- so the deed does not have to be a PDF today. That is a
 * deferral rather than a finding that PDF is never wanted (this plan's decision 5), so the mime type and the
 * extension live here instead of becoming literals in the generator, the document row, the filename and the
 * download header. If PDF is asked for later it is a conversion step after the generator plus a member here,
 * not a rewrite of either.
 */
public enum DeedOutputFormat {

    /** An Office Open XML word-processing document -- the format both templates already are. */
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");

    private final String mimeType;
    private final String extension;

    DeedOutputFormat(String mimeType, String extension) {
        this.mimeType = mimeType;
        this.extension = extension;
    }

    /** The mime type to record on the document row and to send on a download. */
    public String mimeType() {
        return mimeType;
    }

    /** The filename extension, with no dot. */
    public String extension() {
        return extension;
    }
}
