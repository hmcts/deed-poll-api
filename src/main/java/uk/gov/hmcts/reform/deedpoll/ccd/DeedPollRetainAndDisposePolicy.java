package uk.gov.hmcts.reform.deedpoll.ccd;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.ccd.sdk.RetainAndDisposePolicy;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionWorkRepository;

import java.util.Collection;
import java.util.Set;

/**
 * This service's Retain and Dispose policy (plan 20261118-dm-application-deletion, Task 5): the SDK's
 * {@code RetainAndDisposeTask} disposes of a CCD case only because an admitted application deletion asked for it.
 *
 * <p>The task is off unless {@code ccd.decentralised-runtime.retain-and-dispose.mode} is {@code dry-run} or
 * {@code live}, and with it off this bean is never called. When it runs, it marks each candidate
 * ({@code MarkForDisposal}, which {@link DisposalEventsHandler} admits again), confirms it ({@code ConfirmDisposal},
 * which sets {@code SystemTTL} to today), and on a later run, once CCD's disposer has removed the case and data-store
 * answers 404, calls {@link #dispose} in the same transaction as its delete of {@code ccd.case_data}.
 *
 * <p>There is no deletion by policy here: no retention period, nothing derived from the case's age or state. The only
 * candidates are requests whose own filesystem cleanup is verified, so a case CCD disposes of leaves no file the
 * platform does not know about.
 */
@Component
public class DeedPollRetainAndDisposePolicy implements RetainAndDisposePolicy {

    private final CcdProperties properties;
    private final DeletionWorkRepository work;

    public DeedPollRetainAndDisposePolicy(CcdProperties properties, DeletionWorkRepository work) {
        this.properties = properties;
        this.work = work;
    }

    @Override
    public Set<String> caseTypes() {
        return Set.of(properties.caseTypeId());
    }

    @Override
    public Collection<Long> findCandidatesForDisposal() {
        return work.disposalCandidates();
    }

    /**
     * Records that CCD has disposed of the case: its CCD step and its CDAM documents (which CCD's disposer removed by
     * case reference) are done, and the request is due for finalisation. Local data is not touched here; finalisation
     * removes it, under the original Delivery Manager's name.
     *
     * @throws IllegalStateException if no deletion request is waiting for this case's disposal, which rolls the SDK's
     *                               transaction back so its local copy of the case is kept
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void dispose(long caseReference) {
        String reference = CcdCaseReference.of(caseReference)
            .orElseThrow(() -> new IllegalStateException("Retain and Dispose offered an invalid case reference"));
        if (work.recordDisposed(reference, AuditClock.now()) != 1) {
            throw new IllegalStateException("No application deletion is waiting for this case's disposal");
        }
    }
}
