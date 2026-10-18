package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;

import java.util.List;

/** Fixed categories for a refused admission; never provider messages or applicant field values. */
public class GazetteRefusal extends ConflictError {
    private static final long serialVersionUID = 1L;
    private final Code code;
    private final List<MemberConflict> conflicts;

    public GazetteRefusal(Code code) {
        this(code, List.of());
    }

    public GazetteRefusal(Code code, List<MemberConflict> conflicts) {
        super(code == Code.GAZETTE_UNAVAILABLE ? "London Gazette sending is unavailable."
            : "London Gazette confirmation could not be accepted. Check the selection and confirm again.");
        this.code = code;
        this.conflicts = List.copyOf(conflicts);
    }

    public Code code() {
        return code;
    }

    public List<MemberConflict> conflicts() {
        return conflicts;
    }

    public enum Code {
        RECIPIENT_NOT_CONFIGURED, RECIPIENT_CHANGED, SELECTION_CHANGED, CASE_NOT_READY,
        CASE_TYPE_UNSUPPORTED, CASE_RESERVED, REQUEST_KEY_REUSED, CONFIRMATION_EXPIRED, GAZETTE_UNAVAILABLE
    }

    public enum Reason {
        NOT_FOUND, NOT_READY, TYPE_UNSUPPORTED, REFERENCE_MISSING, DATA_CHANGED, RESERVED
    }

    public record MemberConflict(String caseId, Reason reason) {
    }
}
