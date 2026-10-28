package uk.gov.hmcts.reform.deedpoll.domain.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditActor;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntities;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntry;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditTrail;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.repository.BankHolidayRepository;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class BankHolidaysTest {

    private static final CurrentUser DELIVERY_MANAGER =
        CurrentUser.currentUser(List.of(Role.DELIVERY_MANAGER), "A Delivery Manager", "dm-subject");
    private static final LocalDate NEW_YEAR = LocalDate.of(2029, 1, 1);

    private final BankHolidayRepository repository = mock(BankHolidayRepository.class);
    private final AuditTrail audit = mock(AuditTrail.class);
    private final BankHolidays holidays = new BankHolidays(repository, audit);

    static Stream<List<Role>> notDeliveryManagers() {
        return Stream.of(List.of(Role.CASEWORKER), List.of(Role.TEAM_LEADER), List.of(Role.DEEDPOLL_ADMIN),
                         List.of(Role.JUDICIAL), List.of());
    }

    @Test
    @DisplayName("a weekday that is not listed is a working day, asking the list each time")
    void weekday() {
        LocalDate wednesday = LocalDate.of(2026, 11, 4);

        assertThat(holidays.isWorkingDay(wednesday)).isTrue();
        given(repository.listed(wednesday)).willReturn(true);
        assertThat(holidays.isWorkingDay(wednesday)).isFalse();
        given(repository.listed(wednesday)).willReturn(false);
        assertThat(holidays.isWorkingDay(wednesday)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-07", "2026-11-08"})
    @DisplayName("a Saturday or a Sunday is never a working day, listed or not")
    void weekend(String date) {
        assertThat(holidays.isWorkingDay(LocalDate.parse(date))).isFalse();
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("any staff role reads the list; a judge and a roleless caller are refused")
    void list() {
        List<BankHoliday> listed = List.of(new BankHoliday(NEW_YEAR, "New Year's Day"));
        given(repository.list()).willReturn(listed);

        for (Role role : List.of(Role.CASEWORKER, Role.TEAM_LEADER, Role.DELIVERY_MANAGER)) {
            assertThat(holidays.list(CurrentUser.currentUser(List.of(role), "Staff", "s"))).isEqualTo(listed);
        }
        for (List<Role> roles : List.of(List.of(Role.JUDICIAL), List.<Role>of())) {
            assertThatThrownBy(() -> holidays.list(CurrentUser.currentUser(roles, "Someone", "s")))
                .isInstanceOf(ForbiddenError.class);
        }
    }

    @ParameterizedTest
    @MethodSource("notDeliveryManagers")
    @DisplayName("everyone but a Delivery Manager is refused both writes before anything is read")
    void writesRefused(List<Role> roles) {
        CurrentUser caller = CurrentUser.currentUser(roles, "Staff", "subject");

        assertThatThrownBy(() -> holidays.add(caller, Map.of("date", "2029-01-01", "name", "New Year's Day")))
            .isInstanceOf(ForbiddenError.class);
        assertThatThrownBy(() -> holidays.remove(caller, "2029-01-01")).isInstanceOf(ForbiddenError.class);
        verifyNoInteractions(repository, audit);
    }

    @Test
    @DisplayName("a Delivery Manager with no subject is refused both writes before anything is read")
    void noSubject() {
        CurrentUser caller = CurrentUser.currentUser(List.of(Role.DELIVERY_MANAGER), "A Delivery Manager", null);

        assertThatThrownBy(() -> holidays.add(caller, Map.of("date", "2029-01-01", "name", "New Year's Day")))
            .isInstanceOf(UnauthorizedError.class);
        assertThatThrownBy(() -> holidays.remove(caller, "2029-01-01")).isInstanceOf(UnauthorizedError.class);
        verifyNoInteractions(repository, audit);
    }

    @Test
    @DisplayName("adding stores the trimmed name with attribution and audits field names only")
    void adds() {
        given(repository.insert(eq(NEW_YEAR), eq("New Year's Day"), any(), eq("dm-subject"),
                                eq("A Delivery Manager"))).willReturn(true);

        BankHoliday added = holidays.add(DELIVERY_MANAGER, Map.of("date", "2029-01-01", "name", "  New Year's Day "));

        assertThat(added).isEqualTo(new BankHoliday(NEW_YEAR, "New Year's Day"));
        verify(audit).record(AuditEntry.of(AuditAction.BANK_HOLIDAY_ADDED, AuditActor.of(DELIVERY_MANAGER),
                                           AuditEntities.BANK_HOLIDAY, "2029-01-01", null)
                                 .withFields(List.of("date", "name")), List.of());
    }

    @Test
    @DisplayName("adding a date already listed is 409 on the date, with no audit")
    void duplicate() {
        given(repository.insert(any(), anyString(), any(), anyString(), anyString())).willReturn(false);

        assertThatThrownBy(() -> holidays.add(DELIVERY_MANAGER, Map.of("date", "2029-01-01", "name", "Again")))
            .isInstanceOf(ConflictError.class)
            .satisfies(error -> assertThat(((ConflictError) error).errors())
                .extracting(FieldError::field).containsExactly("date"));
        verifyNoInteractions(audit);
    }

    static Stream<Map<String, Object>> invalidBodies() {
        Map<String, Object> nullDate = new HashMap<>();
        nullDate.put("date", null);
        nullDate.put("name", "A");
        return Stream.of(Map.of("name", "A"), nullDate, Map.of("date", "2029-02-30", "name", "A"),
                         Map.of("date", "01/01/2029", "name", "A"), Map.of("date", 20290101, "name", "A"),
                         Map.of("date", "2029-01-01"), Map.of("date", "2029-01-01", "name", "   "),
                         Map.of("date", "2029-01-01", "name", "x".repeat(201)),
                         Map.of("date", "2029-01-01", "name", "A", "forged", true));
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    @DisplayName("a missing, malformed or impossible date, a blank or long name, or an unknown key is 400")
    void invalid(Map<String, Object> body) {
        assertThatThrownBy(() -> holidays.add(DELIVERY_MANAGER, body)).isInstanceOf(InvalidInputError.class);
        verify(repository, never()).insert(any(), any(), any(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("a name of exactly 200 characters is accepted")
    void longestName() {
        given(repository.insert(any(), anyString(), any(), anyString(), anyString())).willReturn(true);

        assertThat(holidays.add(DELIVERY_MANAGER, Map.of("date", "2029-01-01", "name", "x".repeat(200))).name())
            .hasSize(200);
    }

    @Test
    @DisplayName("removing a listed date audits field names only")
    void removes() {
        given(repository.delete(NEW_YEAR)).willReturn(true);

        holidays.remove(DELIVERY_MANAGER, "2029-01-01");

        verify(audit).record(AuditEntry.of(AuditAction.BANK_HOLIDAY_REMOVED, AuditActor.of(DELIVERY_MANAGER),
                                           AuditEntities.BANK_HOLIDAY, "2029-01-01", null)
                                 .withFields(List.of("date", "name")), List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2029-01-01", "2029-02-30", "not-a-date"})
    @DisplayName("removing a date that is not listed, or is no date, is 404 with no audit")
    void removeAbsent(String date) {
        assertThatThrownBy(() -> holidays.remove(DELIVERY_MANAGER, date)).isInstanceOf(NotFoundError.class);
        verifyNoInteractions(audit);
    }
}
