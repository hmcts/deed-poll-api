package uk.gov.hmcts.reform.deedpoll.ccd;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus;
import uk.gov.hmcts.reform.deedpoll.repository.CcdAttemptRow;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The way out of {@code NeedsRepair}, for admins (plan 02b, decisions 7, 8, 10 and 11): see the attempts that are
 * not {@code Linked}, and move one on in either of two ways.
 *
 * <ul>
 *   <li><strong>Reconcile again</strong>, {@code NeedsRepair -> AwaitingConfirmation}: always open. The next sweep
 *       reads the binding and CCD, and links the record or, after the settle window, gives it back.</li>
 *   <li><strong>Try again from scratch</strong>, {@code NeedsRepair -> Pending}: open only while nothing may have
 *       been submitted ({@link CcdLinkingRule#mayRetryFromScratch}), because a fresh start after a submission is how
 *       one record gets two cases.</li>
 * </ul>
 *
 * <p><strong>Neither move calls CCD, and neither touches the binding.</strong> There is no manual bind: nothing here
 * writes, clears or replaces {@code ccdCaseReference}. The move is one conditional statement from
 * {@code NeedsRepair}, so two admins acting at once make one move between them and the second is told so with a 409.
 *
 * <p><strong>Gated on {@link Role#DEEDPOLL_ADMIN} here, in the domain</strong>, as {@code JudicialReferralService}
 * gates on {@link Role#JUDICIAL}: anyone else is a 403 before anything is read. No transaction of its own, because
 * each ledger call is one, and a repair that shared one with its read could not report a lost race truthfully.
 *
 * <p><strong>Every move is logged, made or refused</strong>, in fixed prose: the attempt id, the move and the
 * outcome. Never the admin: the id leads to the row, which records the latest repairer, and a subject in a log line
 * is one copied into an aggregator nobody scoped. No case note is written, because this is a technical state rather
 * than part of the case's timeline.
 */
@Slf4j
@Service
public class CcdAttemptRepairs {

    /** What an unknown id, or one that is not an attempt id at all, is told. */
    static final String NOT_FOUND = "No CCD creation attempt was found with that id";

    /** What a retry from scratch is told when the attempt may already have reached CCD. */
    static final String MAY_HAVE_REACHED = "This attempt may already have reached CCD, so trying it again from scratch "
        + "could create a second case for the record. Send it back to reconciliation instead: that reads CCD first, "
        + "and links the record if its case exists. If nothing is bound to the record, reconciliation cannot link "
        + "it, and it returns here once the settle window has passed.";

    /**
     * What a repair is told when the attempt moved, and came back to {@code NeedsRepair}, while it was being made.
     */
    static final String MOVED_MEANWHILE =
        "This attempt was moved while it was being repaired, and needs repair again: reload the list.";

    private static final String RECONCILE_AGAIN = "NeedsRepair -> AwaitingConfirmation";

    private static final String RETRY_FROM_SCRATCH = "NeedsRepair -> Pending";

    private static final String INVALID_FILTER = "CCD attempt list filter is not valid";

    private final CcdCreationAttempts attempts;

    public CcdAttemptRepairs(CcdCreationAttempts attempts) {
        this.attempts = attempts;
    }

    /**
     * The counts per status, and one page of the attempts that are not {@code Linked}, longest-unmoved first.
     *
     * @param status the raw {@code status} parameter: a status other than {@code Linked}, or null for all of them
     * @throws uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError unless the caller is an admin
     * @throws InvalidInputError for a status that is unknown, or is {@code Linked}
     */
    public CcdAttemptOverview overview(CurrentUser caller, String status, CasePaging paging) {
        caller.requireAnyRole(Role.DEEDPOLL_ADMIN);
        Optional<CcdCreationAttemptStatus> only = listable(status);

        Page<CcdAttemptRow> found = attempts.unlinked(only, PageRequest.of(paging.index(), paging.size()));
        return CcdAttemptOverview.of(
            attempts.countsByStatus(), CcdAttemptSummary.of(found.getContent()), paging, found.getTotalElements());
    }

    /**
     * Sends an attempt {@code NeedsRepair} back to reconciliation.
     *
     * @return the attempt as it now stands
     * @throws uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError unless the caller is an admin
     * @throws NotFoundError  for an unknown id
     * @throws ConflictError  if the attempt is not {@code NeedsRepair}
     */
    public CcdAttemptSummary reconcileAgain(String attemptId, CurrentUser caller) {
        caller.requireAnyRole(Role.DEEDPOLL_ADMIN);
        String repairer = caller.requireUserId();
        UUID id = parsed(attemptId);

        CcdAttemptRow before = found(id, RECONCILE_AGAIN);
        requireNeedsRepair(before, RECONCILE_AGAIN);
        if (!attempts.reconcileAgain(id, repairer)) {
            throw lostRace(id, RECONCILE_AGAIN, false);
        }
        return made(id, RECONCILE_AGAIN);
    }

    /**
     * Sends an attempt {@code NeedsRepair} back to {@code Pending} to be tried again from scratch, if nothing may have
     * been submitted.
     *
     * @return the attempt as it now stands
     * @throws uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError unless the caller is an admin
     * @throws NotFoundError  for an unknown id
     * @throws ConflictError  if the attempt is not {@code NeedsRepair}, or may already have reached CCD
     */
    public CcdAttemptSummary retryFromScratch(String attemptId, CurrentUser caller) {
        caller.requireAnyRole(Role.DEEDPOLL_ADMIN);
        String repairer = caller.requireUserId();
        UUID id = parsed(attemptId);

        CcdAttemptRow before = found(id, RETRY_FROM_SCRATCH);
        requireNeedsRepair(before, RETRY_FROM_SCRATCH);
        requireNothingSubmitted(before);
        if (!attempts.retryFromScratch(id, repairer)) {
            throw lostRace(id, RETRY_FROM_SCRATCH, true);
        }
        return made(id, RETRY_FROM_SCRATCH);
    }

    /**
     * The status filter, refusing what the list cannot show. {@code Linked} is refused rather than answered with an
     * empty page, because the list is of attempts that are not linked and an empty answer would read as none being.
     */
    static Optional<CcdCreationAttemptStatus> listable(String status) {
        if (status == null || status.isBlank()) {
            return Optional.empty();
        }
        Optional<CcdCreationAttemptStatus> named = Arrays.stream(CcdCreationAttemptStatus.values())
            .filter(CcdCreationAttemptStatus::isLive)
            .filter(candidate -> candidate.name().equals(status.trim()))
            .findFirst();
        if (named.isEmpty()) {
            // The value is not echoed: it is caller-supplied text, and naming what is accepted is what makes the
            // refusal actionable.
            String accepted = Arrays.stream(CcdCreationAttemptStatus.values())
                .filter(CcdCreationAttemptStatus::isLive)
                .map(Enum::name)
                .collect(Collectors.joining(", "));
            throw new InvalidInputError(INVALID_FILTER, List.of(new FieldError("status", "is one of " + accepted)));
        }
        return named;
    }

    /** An attempt id; one that is not a UUID is an id nothing was stored under, and is answered as unknown. */
    private static UUID parsed(String attemptId) {
        try {
            return UUID.fromString(attemptId);
        } catch (IllegalArgumentException | NullPointerException notAnId) {
            // Not logged: the text is the caller's, and there is no attempt it could lead anybody to.
            throw new NotFoundError(NOT_FOUND);
        }
    }

    private CcdAttemptRow found(UUID id, String move) {
        return attempts.row(id).orElseThrow(() -> refused(id, move, "404", new NotFoundError(NOT_FOUND)));
    }

    private static void requireNeedsRepair(CcdAttemptRow row, String move) {
        if (row.status() != CcdCreationAttemptStatus.NeedsRepair) {
            throw refused(row.id(), move, "409", notNeedingRepair(row.status()));
        }
    }

    private static void requireNothingSubmitted(CcdAttemptRow row) {
        if (!CcdLinkingRule.mayRetryFromScratch(row.status(), row.submissionMayHaveReached())) {
            throw refused(row.id(), RETRY_FROM_SCRATCH, "409", new ConflictError(MAY_HAVE_REACHED));
        }
    }

    /**
     * The statement matched nothing, so something moved the attempt between the read and the write: another admin,
     * or the sweep. Read again, and refuse in the words the attempt now calls for.
     */
    private RuntimeException lostRace(UUID id, String move, boolean retry) {
        Optional<CcdAttemptRow> now = attempts.row(id);
        if (now.isEmpty()) {
            return refused(id, move, "404", new NotFoundError(NOT_FOUND));
        }
        if (now.get().status() != CcdCreationAttemptStatus.NeedsRepair) {
            return refused(id, move, "409", notNeedingRepair(now.get().status()));
        }
        // Still NeedsRepair and still refused. For the retry that is its second condition; for reconciliation it can
        // only be an attempt that left NeedsRepair and came back to it between the two statements.
        return refused(id, move, "409", new ConflictError(retry ? MAY_HAVE_REACHED : MOVED_MEANWHILE));
    }

    private CcdAttemptSummary made(UUID id, String move) {
        log.info("CCD creation attempt {}: repair {} made", id, move);
        return attempts.row(id).map(CcdAttemptSummary::of).orElseThrow(() -> new NotFoundError(NOT_FOUND));
    }

    private static <E extends RuntimeException> E refused(UUID id, String move, String status, E error) {
        log.info("CCD creation attempt {}: repair {} refused ({})", id, move, status);
        return error;
    }

    private static ConflictError notNeedingRepair(CcdCreationAttemptStatus status) {
        return new ConflictError("This attempt is " + status + " rather than NeedsRepair, so there is nothing to "
            + "repair. Another admin or the linking sweep may have moved it: reload the list.");
    }
}
