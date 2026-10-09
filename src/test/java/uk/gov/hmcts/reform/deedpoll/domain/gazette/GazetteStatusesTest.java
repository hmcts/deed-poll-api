package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.repository.GazetteStatusRepository;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Direct callers must meet the same permission gate as HTTP callers, before any repository read. */
class GazetteStatusesTest {

    static Stream<List<Role>> refusedRoles() {
        return Stream.of(List.of(), List.of(Role.CASEWORKER), List.of(Role.JUDICIAL),
            List.of(Role.CASEWORKER, Role.JUDICIAL));
    }

    @ParameterizedTest
    @MethodSource("refusedRoles")
    void refusesEveryStatusReadBeforeRepositoryAccess(List<Role> roles) {
        GazetteStatusRepository repository = mock(GazetteStatusRepository.class);
        GazetteStatuses statuses = new GazetteStatuses(repository);
        CurrentUser caller = CurrentUser.currentUser(roles, "Staff", "subject");
        UUID batchId = UUID.randomUUID();

        assertThatThrownBy(() -> statuses.list(caller, "1", "25", null))
            .isInstanceOf(ForbiddenError.class);
        assertThatThrownBy(() -> statuses.detail(caller, batchId))
            .isInstanceOf(ForbiddenError.class);
        assertThatThrownBy(() -> statuses.members(caller, batchId, "1", "25"))
            .isInstanceOf(ForbiddenError.class);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @MethodSource("refusedRoles")
    void refusesBeforeParsingStatusFilters(List<Role> roles) {
        GazetteStatusRepository repository = mock(GazetteStatusRepository.class);
        GazetteStatuses statuses = new GazetteStatuses(repository);
        CurrentUser caller = CurrentUser.currentUser(roles, "Staff", "subject");

        assertThatThrownBy(() -> statuses.list(caller, "invalid", "invalid", "invalid"))
            .isInstanceOf(ForbiddenError.class);
        verifyNoInteractions(repository);
    }
}
