package uk.gov.hmcts.reform.deedpoll.domain.gazette;

/** Prepared-file evidence only; never workbook bytes or Notify upload tokens. */
public record GazetteFileEvidence(String kind, int caseCount, int byteSize, String sha256) {
    public GazetteFileEvidence {
        if (!("adult".equals(kind) || "minor".equals(kind)) || caseCount < 1 || byteSize < 1
            || byteSize > 2 * 1024 * 1024 || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid Gazette file evidence");
        }
    }

    public String filename() {
        return "gazette-" + kind + ".xlsx";
    }
}
