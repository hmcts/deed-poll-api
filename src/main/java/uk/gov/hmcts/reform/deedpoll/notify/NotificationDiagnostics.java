package uk.gov.hmcts.reform.deedpoll.notify;

/** Stack locations and cause types are diagnostic; provider messages may contain correspondence or secrets. */
final class NotificationDiagnostics {
    private NotificationDiagnostics() {
    }

    static Throwable redacted(Throwable failure) {
        return redacted(failure, 0);
    }

    private static Throwable redacted(Throwable failure, int depth) {
        RuntimeException safe = new RuntimeException(failure.getClass().getName());
        safe.setStackTrace(failure.getStackTrace());
        if (failure.getCause() != null && depth < 8) {
            safe.initCause(redacted(failure.getCause(), depth + 1));
        }
        return safe;
    }
}
