package uk.gov.hmcts.reform.deedpoll.ccd;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.CaseView;
import uk.gov.hmcts.ccd.sdk.CaseViewRequest;
import uk.gov.hmcts.reform.deedpoll.repository.CcdCaseProjection;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;

import java.util.Set;

/**
 * What CCD shows of a deed poll case: the two fields, composed from the relational record every time CCD loads it.
 *
 * <p>The record is the truth (plan 02, decision 1). The SDK stores {@code {}} in {@code ccd.case_data.data} for a
 * decentralised event, and this view is where the case's data comes from on every read and on the snapshot the SDK
 * writes into {@code ccd.case_event}. The blob it is offered is ignored for the same reason.
 *
 * <p>Read through {@link CcdCaseProjection}, so no entity crosses into the SDK. The one view bean for the case type:
 * the runtime refuses to start with none, or with two for one case type, and {@link #caseTypeIds} names ours rather
 * than leaving the SDK to match it by generics.
 */
@Component
public class DeedPollCaseView implements CaseView<DeedPollCaseData, DeedPollCaseState> {

    private final DeedPollRepository deedPolls;
    private final CcdProperties properties;

    public DeedPollCaseView(DeedPollRepository deedPolls, CcdProperties properties) {
        this.deedPolls = deedPolls;
        this.properties = properties;
    }

    @Override
    public Set<String> caseTypeIds() {
        return Set.of(properties.caseTypeId());
    }

    /**
     * The case's data, from the record its reference is bound to.
     *
     * @throws IllegalStateException if no record carries the reference. The handler binds it in the transaction
     *     that creates the case and a failure rolls both back, so a case with no record is a broken invariant, and
     *     answering with empty data would hide it
     */
    @Override
    public DeedPollCaseData getCase(CaseViewRequest<DeedPollCaseState> request) {
        CcdCaseProjection record = CcdCaseReference.of(request.caseRef())
            .flatMap(deedPolls::findCcdCaseProjection)
            .orElseThrow(() -> new IllegalStateException("A CCD case has no deed poll record bound to it"));
        DeedPollCaseData data = new DeedPollCaseData();
        data.setDeedPollId(record.deedPollId());
        data.setDeedPollReference(record.displayReference());
        return data;
    }
}
