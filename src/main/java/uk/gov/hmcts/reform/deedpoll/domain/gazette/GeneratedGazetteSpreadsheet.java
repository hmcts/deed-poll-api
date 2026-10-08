package uk.gov.hmcts.reform.deedpoll.domain.gazette;

/** One populated workbook, ready for the future batch delivery service. */
public record GeneratedGazetteSpreadsheet(String filename, int caseCount, byte[] content) {

    public GeneratedGazetteSpreadsheet {
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    public String mimeType() {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    @Override
    public String toString() {
        return "GeneratedGazetteSpreadsheet[filename=" + filename + ", caseCount=" + caseCount
            + ", size=" + content.length + "]";
    }
}
