package uk.gov.hmcts.reform.deedpoll.domain.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditActor;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntities;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntry;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditTrail;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.repository.ProcessSettingsRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ProcessSettingsServiceTest {

    private static final CurrentUser DELIVERY_MANAGER =
        CurrentUser.currentUser(List.of(Role.DELIVERY_MANAGER), "A Delivery Manager", "dm-subject");
    private static final ProcessSettings SEEDED = new ProcessSettings(Duration.ofHours(2), 0, null, null);

    private final ProcessSettingsRepository repository = mock(ProcessSettingsRepository.class);
    private final AuditTrail audit = mock(AuditTrail.class);
    private final ProcessSettingsService service = new ProcessSettingsService(repository, audit);

    static Stream<List<Role>> refusedRoles() {
        return Stream.of(List.of(Role.TEAM_LEADER), List.of(Role.DEEDPOLL_ADMIN), List.of(Role.CASEWORKER),
                         List.of(Role.JUDICIAL), List.of(Role.TEAM_LEADER, Role.JUDICIAL), List.of());
    }

    @ParameterizedTest
    @MethodSource("refusedRoles")
    @DisplayName("everyone but a Delivery Manager is refused both operations before anything is read")
    void refusedBeforeAnyRead(List<Role> roles) {
        CurrentUser caller = CurrentUser.currentUser(roles, "Staff", "subject");

        assertThatThrownBy(() -> service.get(caller)).isInstanceOf(ForbiddenError.class);
        assertThatThrownBy(() -> service.update(caller, valid(60, 0))).isInstanceOf(ForbiddenError.class);
        verifyNoInteractions(repository, audit);
    }

    @Test
    @DisplayName("a Delivery Manager reads the stored settings unlocked")
    void deliveryManagerReads() {
        given(repository.read(false)).willReturn(Optional.of(SEEDED));

        assertThat(service.get(DELIVERY_MANAGER)).isEqualTo(SEEDED);
    }

    @Test
    @DisplayName("a Delivery Manager with no subject cannot own a change")
    void noSubject() {
        CurrentUser anonymous = CurrentUser.currentUser(List.of(Role.DELIVERY_MANAGER), "A Delivery Manager", null);

        assertThatThrownBy(() -> service.update(anonymous, valid(60, 0))).isInstanceOf(UnauthorizedError.class);
        verifyNoInteractions(repository, audit);
    }

    static Stream<Arguments> refusedBodies() {
        return Stream.of(
            Arguments.of(body("referralClaimExpiryMinutes", 29, "revision", 0), "referralClaimExpiryMinutes"),
            Arguments.of(body("referralClaimExpiryMinutes", 1441, "revision", 0), "referralClaimExpiryMinutes"),
            Arguments.of(body("referralClaimExpiryMinutes", 0, "revision", 0), "referralClaimExpiryMinutes"),
            Arguments.of(body("referralClaimExpiryMinutes", -5, "revision", 0), "referralClaimExpiryMinutes"),
            Arguments.of(body("referralClaimExpiryMinutes", 90.5, "revision", 0), "referralClaimExpiryMinutes"),
            Arguments.of(body("referralClaimExpiryMinutes", 90.0, "revision", 0), "referralClaimExpiryMinutes"),
            Arguments.of(body("referralClaimExpiryMinutes", 120L, "revision", 0), "referralClaimExpiryMinutes"),
            Arguments.of(body("referralClaimExpiryMinutes", "120", "revision", 0), "referralClaimExpiryMinutes"),
            Arguments.of(body("referralClaimExpiryMinutes", null, "revision", 0), "referralClaimExpiryMinutes"),
            Arguments.of(body("revision", 0), "referralClaimExpiryMinutes"),
            Arguments.of(body("referralClaimExpiryMinutes", 60), "revision"),
            Arguments.of(body("referralClaimExpiryMinutes", 60, "revision", null), "revision"),
            Arguments.of(body("referralClaimExpiryMinutes", 60, "revision", -1), "revision"),
            Arguments.of(body("referralClaimExpiryMinutes", 60, "revision", "0"), "revision"),
            Arguments.of(body("referralClaimExpiryMinutes", 60, "revision", 0, "escalationDays", 3), "escalationDays"));
    }

    @ParameterizedTest
    @MethodSource("refusedBodies")
    @DisplayName("a body outside the closed key set, the bounds or whole minutes names its field and reads nothing")
    void refusedBody(Map<String, Object> fields, String field) {
        assertThatThrownBy(() -> service.update(DELIVERY_MANAGER, fields))
            .isInstanceOfSatisfying(InvalidInputError.class, error -> assertThat(error.errors())
                .extracting(FieldError::field).containsExactly(field));
        verifyNoInteractions(repository, audit);
    }

    @Test
    @DisplayName("the bounds are in the claim window's refusal")
    void boundsInTheMessage() {
        assertThatThrownBy(() -> service.update(DELIVERY_MANAGER, valid(29, 0)))
            .hasMessageContaining("30").hasMessageContaining("1440");
    }

    @ParameterizedTest
    @MethodSource("boundaries")
    @DisplayName("30 and 1440 minutes are both accepted")
    void boundariesAccepted(int minutes) {
        given(repository.read(true)).willReturn(Optional.of(SEEDED));

        ProcessSettings next = service.update(DELIVERY_MANAGER, valid(minutes, 0));

        assertThat(next.referralClaimExpiry()).isEqualTo(Duration.ofMinutes(minutes));
    }

    static Stream<Integer> boundaries() {
        return Stream.of(30, 1440);
    }

    @Test
    @DisplayName("a stale revision is a conflict and writes nothing")
    void staleRevision() {
        given(repository.read(true)).willReturn(Optional.of(SEEDED));

        assertThatThrownBy(() -> service.update(DELIVERY_MANAGER, valid(60, 1))).isInstanceOf(ConflictError.class);
        verify(repository, never()).change(any(), any(), any(), anyList());
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("a stale revision beats the no-op: an old form holding today's value is still a conflict")
    void staleBeatsNoOp() {
        ProcessSettings current = new ProcessSettings(Duration.ofMinutes(60), 3, LocalDateTime.now(), "Someone");
        given(repository.read(true)).willReturn(Optional.of(current));

        assertThatThrownBy(() -> service.update(DELIVERY_MANAGER, valid(60, 2))).isInstanceOf(ConflictError.class);
        verify(repository, never()).change(any(), any(), any(), anyList());
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("an unchanged value returns the current settings at the same revision, with no write and no audit")
    void unchangedIsANoOp() {
        given(repository.read(true)).willReturn(Optional.of(SEEDED));

        assertThat(service.update(DELIVERY_MANAGER, valid(120, 0))).isSameAs(SEEDED);
        verify(repository, never()).change(any(), any(), any(), anyList());
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("a change bumps the revision, attributes it, writes the history and audits the field name only")
    void change() {
        given(repository.read(true)).willReturn(Optional.of(SEEDED));

        ProcessSettings next = service.update(DELIVERY_MANAGER, valid(45, 0));

        assertThat(next.referralClaimExpiryMinutes()).isEqualTo(45);
        assertThat(next.revision()).isOne();
        assertThat(next.updatedBy()).isEqualTo("A Delivery Manager");
        assertThat(next.updatedAt()).isNotNull();
        verify(repository).change(SEEDED, next, "dm-subject", List.of("referralClaimExpiryMinutes"));
        verify(audit).record(eq(AuditEntry.of(AuditAction.PROCESS_SETTINGS_CHANGED, AuditActor.of(DELIVERY_MANAGER),
                                              AuditEntities.PROCESS_SETTINGS, "1", null)
                                      .withFields(List.of("referralClaimExpiryMinutes"))),
                             eq(List.of()));
    }

    @Test
    @DisplayName("the sweep's read needs no capability and returns the stored window")
    void sweepRead() {
        given(repository.read(anyBoolean())).willReturn(Optional.of(SEEDED));

        assertThat(service.referralClaimExpiry()).isEqualTo(Duration.ofHours(2));
        verify(repository).read(false);
    }

    @Test
    @DisplayName("the sweep's read never falls back to a default when the row is missing")
    void sweepReadNoDefault() {
        given(repository.read(false)).willReturn(Optional.empty());

        assertThatThrownBy(service::referralClaimExpiry).isInstanceOf(IllegalStateException.class);
    }

    private static Map<String, Object> valid(int minutes, int revision) {
        return body("referralClaimExpiryMinutes", minutes, "revision", revision);
    }

    private static Map<String, Object> body(Object... pairs) {
        Map<String, Object> body = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            body.put((String) pairs[i], pairs[i + 1]);
        }
        return body;
    }
}
