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
import java.time.LocalTime;
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
    private static final ProcessSettings SEEDED = seeded(Duration.ofDays(7), 0, null, null);

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
        assertThatThrownBy(() -> service.update(caller, valid(2880, 0))).isInstanceOf(ForbiddenError.class);
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

        assertThatThrownBy(() -> service.update(anonymous, valid(2880, 0))).isInstanceOf(UnauthorizedError.class);
        verifyNoInteractions(repository, audit);
    }

    static Stream<Arguments> refusedBodies() {
        return Stream.of(
            Arguments.of(body("referralClaimExpiryMinutes", 1439, "revision", 0), "referralClaimExpiryMinutes"),
            Arguments.of(body("referralClaimExpiryMinutes", 43201, "revision", 0), "referralClaimExpiryMinutes"),
            Arguments.of(body("referralClaimExpiryMinutes", 0, "revision", 0), "referralClaimExpiryMinutes"),
            Arguments.of(body("referralClaimExpiryMinutes", 120, "revision", 0), "referralClaimExpiryMinutes"),
            Arguments.of(withMinutes("reminderIntervalHours", 0), "reminderIntervalHours"),
            Arguments.of(withMinutes("reminderIntervalHours", 169), "reminderIntervalHours"),
            Arguments.of(withMinutes("reminderIntervalHours", null), "reminderIntervalHours"),
            Arguments.of(withMinutes("reminderIntervalHours", 48L), "reminderIntervalHours"),
            Arguments.of(withMinutes("masterWindowCap", 0), "masterWindowCap"),
            Arguments.of(withMinutes("masterWindowCap", 101), "masterWindowCap"),
            Arguments.of(withMinutes("masterWindowCap", "10"), "masterWindowCap"),
            Arguments.of(withMinutes("morningWindowStart", "07:05"), "morningWindowStart"),
            Arguments.of(withMinutes("morningWindowStart", "7:00"), "morningWindowStart"),
            Arguments.of(withMinutes("morningWindowEnd", "10:30:00"), "morningWindowEnd"),
            Arguments.of(withMinutes("afternoonWindowStart", null), "afternoonWindowStart"),
            Arguments.of(withMinutes("afternoonWindowEnd", "24:00"), "afternoonWindowEnd"),
            Arguments.of(body("referralClaimExpiryMinutes", -5, "revision", 0), "referralClaimExpiryMinutes"),
            Arguments.of(body("referralClaimExpiryMinutes", 90.5, "revision", 0), "referralClaimExpiryMinutes"),
            Arguments.of(body("referralClaimExpiryMinutes", 90.0, "revision", 0), "referralClaimExpiryMinutes"),
            Arguments.of(body("referralClaimExpiryMinutes", 10080L, "revision", 0), "referralClaimExpiryMinutes"),
            Arguments.of(body("referralClaimExpiryMinutes", "10080", "revision", 0), "referralClaimExpiryMinutes"),
            Arguments.of(body("referralClaimExpiryMinutes", null, "revision", 0), "referralClaimExpiryMinutes"),
            Arguments.of(body("revision", 0), "referralClaimExpiryMinutes"),
            Arguments.of(body("referralClaimExpiryMinutes", 2880), "revision"),
            Arguments.of(body("referralClaimExpiryMinutes", 2880, "revision", null), "revision"),
            Arguments.of(body("referralClaimExpiryMinutes", 2880, "revision", -1), "revision"),
            Arguments.of(body("referralClaimExpiryMinutes", 2880, "revision", "0"), "revision"),
            Arguments.of(body("referralClaimExpiryMinutes", 2880, "revision", 0, "escalationDays", 3),
                         "escalationDays"));
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
    @DisplayName("the bounds are in each whole-number refusal")
    void boundsInTheMessage() {
        assertThatThrownBy(() -> service.update(DELIVERY_MANAGER, valid(1439, 0)))
            .hasMessageContaining("1440").hasMessageContaining("43200");
        assertThatThrownBy(() -> service.update(DELIVERY_MANAGER, withMinutes("reminderIntervalHours", 169)))
            .hasMessageContaining("1 to 168");
        assertThatThrownBy(() -> service.update(DELIVERY_MANAGER, withMinutes("masterWindowCap", 101)))
            .hasMessageContaining("1 to 100");
    }

    @ParameterizedTest
    @MethodSource("boundaries")
    @DisplayName("1440 and 43200 minutes are both accepted")
    void boundariesAccepted(int minutes) {
        given(repository.read(true)).willReturn(Optional.of(SEEDED));

        ProcessSettings next = service.update(DELIVERY_MANAGER, valid(minutes, 0));

        assertThat(next.processingWindow()).isEqualTo(Duration.ofMinutes(minutes));
    }

    static Stream<Integer> boundaries() {
        return Stream.of(1440, 43200);
    }

    @Test
    @DisplayName("a stale revision is a conflict and writes nothing")
    void staleRevision() {
        given(repository.read(true)).willReturn(Optional.of(SEEDED));

        assertThatThrownBy(() -> service.update(DELIVERY_MANAGER, valid(2880, 1))).isInstanceOf(ConflictError.class);
        verify(repository, never()).change(any(), any(), any(), anyList());
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("a stale revision beats the no-op: an old form holding today's value is still a conflict")
    void staleBeatsNoOp() {
        ProcessSettings current = seeded(Duration.ofMinutes(2880), 3, LocalDateTime.now(), "Someone");
        given(repository.read(true)).willReturn(Optional.of(current));

        assertThatThrownBy(() -> service.update(DELIVERY_MANAGER, valid(2880, 2))).isInstanceOf(ConflictError.class);
        verify(repository, never()).change(any(), any(), any(), anyList());
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("an unchanged value returns the current settings at the same revision, with no write and no audit")
    void unchangedIsANoOp() {
        given(repository.read(true)).willReturn(Optional.of(SEEDED));

        assertThat(service.update(DELIVERY_MANAGER, valid(10080, 0))).isSameAs(SEEDED);
        assertThat(service.update(DELIVERY_MANAGER, withMinutes("morningWindowStart", "07:00"))).isSameAs(SEEDED);
        verify(repository, never()).change(any(), any(), any(), anyList());
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("a change bumps the revision, attributes it, writes the history and audits the field name only")
    void change() {
        given(repository.read(true)).willReturn(Optional.of(SEEDED));

        ProcessSettings next = service.update(DELIVERY_MANAGER, valid(1445, 0));

        assertThat(next.referralClaimExpiryMinutes()).isEqualTo(1445);
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

        assertThat(service.processingWindow()).isEqualTo(Duration.ofDays(7));
        assertThat(service.reminderInterval()).isEqualTo(Duration.ofHours(48));
        assertThat(service.masterWindowCap()).isEqualTo(10);
        assertThat(service.referralWindows()).isEqualTo(new ReferralWindows(
            LocalTime.of(7, 0), LocalTime.of(10, 30), LocalTime.of(14, 0), LocalTime.of(14, 30)));
        verify(repository, never()).read(true);
    }

    @Test
    @DisplayName("the sweep's read never falls back to a default when the row is missing")
    void sweepReadNoDefault() {
        given(repository.read(false)).willReturn(Optional.empty());

        assertThatThrownBy(service::processingWindow).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(service::reminderInterval).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(service::masterWindowCap).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(service::referralWindows).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("only the two old keys leave every new setting as stored, and history names only the window")
    void oldShapeBody() {
        given(repository.read(true)).willReturn(Optional.of(SEEDED));

        ProcessSettings next = service.update(DELIVERY_MANAGER, valid(2880, 0));

        assertThat(next.reminderInterval()).isEqualTo(SEEDED.reminderInterval());
        assertThat(next.masterWindowCap()).isEqualTo(10);
        assertThat(next.referralWindows()).isEqualTo(SEEDED.referralWindows());
        verify(repository).change(SEEDED, next, "dm-subject", List.of("referralClaimExpiryMinutes"));
    }

    @Test
    @DisplayName("each changed setting is named once, in a fixed order, and an unchanged one is not")
    void namesEveryChangedField() {
        given(repository.read(true)).willReturn(Optional.of(SEEDED));
        Map<String, Object> fields = valid(10080, 0);
        fields.put("afternoonWindowEnd", "15:00");
        fields.put("masterWindowCap", 12);
        fields.put("reminderIntervalHours", 48);

        ProcessSettings next = service.update(DELIVERY_MANAGER, fields);

        assertThat(next.afternoonWindowEnd()).isEqualTo(LocalTime.of(15, 0));
        verify(repository).change(SEEDED, next, "dm-subject", List.of("masterWindowCap", "afternoonWindowEnd"));
        verify(audit).record(eq(AuditEntry.of(AuditAction.PROCESS_SETTINGS_CHANGED, AuditActor.of(DELIVERY_MANAGER),
                                              AuditEntities.PROCESS_SETTINGS, "1", null)
                                      .withFields(List.of("masterWindowCap", "afternoonWindowEnd"))),
                             eq(List.of()));
    }

    static Stream<Arguments> misordered() {
        return Stream.of(
            Arguments.of("morningWindowEnd", "07:00", "morningWindowEnd"),
            Arguments.of("morningWindowStart", "11:00", "morningWindowEnd"),
            Arguments.of("morningWindowEnd", "14:00", "afternoonWindowStart"),
            Arguments.of("afternoonWindowStart", "10:15", "afternoonWindowStart"),
            Arguments.of("afternoonWindowEnd", "14:00", "afternoonWindowEnd"),
            Arguments.of("afternoonWindowStart", "15:00", "afternoonWindowEnd"));
    }

    @ParameterizedTest
    @MethodSource("misordered")
    @DisplayName("misordered windows, judged with the stored values for absent keys, name a field and write nothing")
    void misorderedWindows(String field, String time, String named) {
        given(repository.read(true)).willReturn(Optional.of(SEEDED));

        assertThatThrownBy(() -> service.update(DELIVERY_MANAGER, withMinutes(field, time)))
            .isInstanceOfSatisfying(InvalidInputError.class, error -> assertThat(error.errors())
                .extracting(FieldError::field).containsExactly(named));
        verify(repository, never()).change(any(), any(), any(), anyList());
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("the morning window may end a quarter hour before the afternoon one starts")
    void adjacentWindows() {
        given(repository.read(true)).willReturn(Optional.of(SEEDED));

        assertThat(service.update(DELIVERY_MANAGER, withMinutes("morningWindowEnd", "13:45")).morningWindowEnd())
            .isEqualTo(LocalTime.of(13, 45));
    }

    private static ProcessSettings seeded(Duration window, int revision, LocalDateTime at, String by) {
        return new ProcessSettings(window, Duration.ofHours(48), 10, LocalTime.of(7, 0), LocalTime.of(10, 30),
                                   LocalTime.of(14, 0), LocalTime.of(14, 30), revision, at, by);
    }

    /** The seeded window at revision 0, beside one other key. */
    private static Map<String, Object> withMinutes(String field, Object value) {
        Map<String, Object> fields = valid(10080, 0);
        fields.put(field, value);
        return fields;
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
