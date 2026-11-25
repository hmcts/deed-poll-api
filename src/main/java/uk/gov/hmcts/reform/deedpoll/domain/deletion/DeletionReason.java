package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import java.util.Arrays;
import java.util.Optional;

/**
 * Why a Delivery Manager is deleting an application: the only two reasons a deletion may be requested for (Steve,
 * 2026-11-18). Stored by {@link #name()} in {@code deedpoll_deletion_request.reason}, whose check constraint names the
 * same two, and in the audit event's {@code deletionReason}, which keeps it for seven years: never rename one.
 */
public enum DeletionReason {
    /** The application should never have been created. */
    CREATED_IN_ERROR,
    /** The application repeats another one, which is kept. No duplicate target is required. */
    DUPLICATE;

    /** The reason with exactly this name, case-sensitively; anything else is no reason at all. */
    public static Optional<DeletionReason> fromName(String name) {
        return Arrays.stream(values()).filter(reason -> reason.name().equals(name)).findFirst();
    }
}
