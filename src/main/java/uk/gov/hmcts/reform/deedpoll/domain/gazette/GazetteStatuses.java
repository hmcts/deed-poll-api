package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Capability;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.repository.GazetteStatusRepository;

import java.util.UUID;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class GazetteStatuses {
    private final GazetteStatusRepository repository;

    public GazetteStatuses(GazetteStatusRepository repository) {
        this.repository = repository;
    }

    public Page<GazetteBatchStatus> list(CurrentUser caller, String page, String size, String state) {
        caller.require(Capability.ACCESS_GAZETTE);
        GazetteRequestState filter;
        try {
            filter = state == null ? null : GazetteRequestState.valueOf(state);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidInputError("Invalid Gazette request state.");
        }
        return repository.list(CasePaging.parse(page, size), filter);
    }

    public GazetteBatchStatus detail(CurrentUser caller, UUID id) {
        caller.require(Capability.ACCESS_GAZETTE);
        return repository.detail(id).orElseThrow(() -> new NotFoundError("Gazette batch not found"));
    }

    public Page<GazetteMember> members(CurrentUser caller, UUID id, String page, String size) {
        detail(caller, id);
        return repository.members(id, CasePaging.parse(page, size));
    }
}
