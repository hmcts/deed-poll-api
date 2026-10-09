package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import java.util.Arrays;
import java.util.Objects;

/**
 * One populated workbook, ready for the batch delivery service. Equality is by content: a record's
 * generated equals would compare the array by reference, so two identical workbooks would differ.
 */
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
    public boolean equals(Object other) {
        return other instanceof GeneratedGazetteSpreadsheet that
            && caseCount == that.caseCount
            && Objects.equals(filename, that.filename)
            && Arrays.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(filename, caseCount) + Arrays.hashCode(content);
    }

    @Override
    public String toString() {
        return "GeneratedGazetteSpreadsheet[filename=" + filename + ", caseCount=" + caseCount
            + ", size=" + content.length + "]";
    }
}
