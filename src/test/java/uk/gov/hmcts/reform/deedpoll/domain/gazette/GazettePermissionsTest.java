package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.PlatformTransactionManager;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditTrail;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseListStatementTimeout;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseQuery;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseSearch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseSort;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.ListFilters;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.ReferenceNumberService;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.GazetteNoticeBatchCaseRepository;
import uk.gov.hmcts.reform.deedpoll.repository.GazetteNoticeBatchRepository;
import uk.gov.hmcts.reform.deedpoll.repository.GazetteRequestRepository;
import uk.gov.hmcts.reform.deedpoll.repository.GazetteSettingsRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Direct callers cannot bypass the HTTP boundary or read protected data before refusal. */
class GazettePermissionsTest {
    @ParameterizedTest
    @MethodSource("uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteStatusesTest#refusedRoles")
    void allHumanDomainOperationsRefuseBeforeAnyCollaboratorAccess(List<Role> roles) {
        CurrentUser caller = CurrentUser.currentUser(roles, "Staff", "subject");
        GazetteRequestRepository requests = mock(GazetteRequestRepository.class);
        DeedPollRepository cases = mock(DeedPollRepository.class);
        NoteService notes = mock(NoteService.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        GazetteConfiguration configuration = mock(GazetteConfiguration.class);
        GazetteSettingsRepository settings = mock(GazetteSettingsRepository.class);
        GazetteNoticeBatchRepository batches = mock(GazetteNoticeBatchRepository.class);
        GazetteNoticeBatchCaseRepository members = mock(GazetteNoticeBatchCaseRepository.class);
        GazetteRequestLedger ledger = new GazetteRequestLedger(requests, cases, notes, transactions, configuration,
            mock(AuditTrail.class));
        GazettePreviews previews = new GazettePreviews(cases, requests, settings, configuration);
        GazetteSettingsService settingsService = new GazetteSettingsService(settings, mock(AuditTrail.class));
        GazetteNoticeHistory history = new GazetteNoticeHistory(batches, members);
        UUID key = UUID.randomUUID();

        assertThatThrownBy(() -> ledger.admit(caller, key, List.of("case"), "token", 1))
            .isInstanceOf(ForbiddenError.class);
        assertThatThrownBy(() -> ledger.repeat(caller, key, List.of("case"), "token", 1))
            .isInstanceOf(ForbiddenError.class);
        assertThatThrownBy(() -> ledger.read(caller, key)).isInstanceOf(ForbiddenError.class);
        assertThatThrownBy(() -> previews.preview(caller, List.of("case"))).isInstanceOf(ForbiddenError.class);
        assertThatThrownBy(() -> settingsService.get(caller)).isInstanceOf(ForbiddenError.class);
        assertThatThrownBy(() -> settingsService.update(caller,
            Map.of("recipientEmail", "gazette@example.com", "revision", 1))).isInstanceOf(ForbiddenError.class);
        assertThatThrownBy(() -> history.history(caller, CasePaging.FIRST)).isInstanceOf(ForbiddenError.class);
        verifyNoInteractions(requests, cases, notes, transactions, configuration, settings, batches, members);
    }

    @ParameterizedTest
    @MethodSource("uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteStatusesTest#refusedRoles")
    void everyNamedFilterOverloadRefusesBeforeRowsCountsOrReferenceQueries(List<Role> roles) {
        CurrentUser caller = CurrentUser.currentUser(roles, "Staff", "subject");
        DeedPollRepository cases = mock(DeedPollRepository.class);
        ReferenceNumberService references = mock(ReferenceNumberService.class);
        DeedPollService service = new DeedPollService(cases, references, mock(AuditTrail.class),
            mock(CaseListStatementTimeout.class));
        ListFilters.ListFilter filter = ListFilters.filterFor("readyForPublication");
        CaseQuery query = CaseQuery.parse(List.of("type:is:Adult"));

        assertThatThrownBy(() -> service.findDeedPolls(caller, filter)).isInstanceOf(ForbiddenError.class);
        assertThatThrownBy(() -> service.findDeedPolls(caller, filter, CaseQuery.NONE))
            .isInstanceOf(ForbiddenError.class);
        assertThatThrownBy(() -> service.findDeedPolls(caller, filter, query)).isInstanceOf(ForbiddenError.class);
        assertThatThrownBy(() -> service.findDeedPolls(caller, filter, query, CaseSort.NEWEST_FIRST, CasePaging.FIRST))
            .isInstanceOf(ForbiddenError.class);
        assertThatThrownBy(() -> service.findDeedPolls(caller, filter, query, CaseSearch.parse("Smith"),
            CaseSort.NEWEST_FIRST, CasePaging.FIRST)).isInstanceOf(ForbiddenError.class);
        verifyNoInteractions(cases, references);
    }
}
