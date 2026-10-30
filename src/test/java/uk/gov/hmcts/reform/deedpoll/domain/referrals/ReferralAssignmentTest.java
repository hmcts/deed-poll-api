package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntry;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditTrail;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditValue;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.notes.CreateNoteInput;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettingsService;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ReferralWindowTimes;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;
import uk.gov.hmcts.reform.deedpoll.entity.enums.WindowSlot;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.MasterRepository;
import uk.gov.hmcts.reform.deedpoll.repository.MasterRepository.Assignee;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ReferralService#assign}, the reassignment (plan F1, decision F-10), and the create, which since plan F1 takes
 * no Master, with no database: the gate, the rules and their order, the columns it keeps and restarts, the note and the
 * audit event. A reassignment checks no window and no cap, so there is no concurrency test of its own; the HTTP answers
 * are {@code CaseworkerReferralAssignmentTest}'s.
 */
class ReferralAssignmentTest {

    private static final String CASE_ID = "case-1";
    private static final UUID REFERRAL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SULLIVAN = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OKAFOR = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");

    private static final CurrentUser CASEWORKER = CurrentUser.currentUser(List.of(), "Ada Caseworker", "caseworker-1");
    private static final CurrentUser LEADER =
        CurrentUser.currentUser(List.of(Role.TEAM_LEADER), "Tia Leader", "leader-1");

    /** When Master Sullivan was sent it: Monday's morning window. */
    private static final LocalDateTime SENT = london(2026, 10, 26, 9, 0);
    private static final LocalDate SENT_DAY = LocalDate.of(2026, 10, 26);

    private static final ReferralWindowTimes WINDOWS = new ReferralWindowTimes(
        LocalTime.of(7, 0), LocalTime.of(10, 30), LocalTime.of(14, 0), LocalTime.of(14, 30));

    private DeedPollRepository deedPolls;
    private ReferralRepository referrals;
    private NoteService notes;
    private ProcessSettingsService settings;
    private AuditTrail audit;
    private MasterRepository masters;
    private ReferralService service;
    private Referral referral;

    @BeforeEach
    void setUp() {
        deedPolls = mock(DeedPollRepository.class);
        referrals = mock(ReferralRepository.class);
        notes = mock(NoteService.class);
        settings = mock(ProcessSettingsService.class);
        audit = mock(AuditTrail.class);
        masters = mock(MasterRepository.class);
        when(settings.windowCap()).thenReturn(10);
        when(settings.referralWindows()).thenReturn(WINDOWS);
        when(settings.processingWindow()).thenReturn(Duration.ofDays(7));
        service = new ReferralService(deedPolls, referrals, notes, settings, mock(EntityManager.class), audit,
                                      masters);

        when(referrals.save(any())).thenAnswer(call -> {
            Referral written = call.getArgument(0);
            if (written.getId() == null) {
                written.setId(REFERRAL_ID);
            }
            // As @PrePersist stamps it on a real save, which the assignment's note names.
            if (written.getCreatedOn() == null) {
                written.setCreatedOn(LocalDateTime.of(2026, 10, 28, 9, 0));
            }
            return written;
        });
        when(referrals.findStatesByDeedPollId(anyString())).thenAnswer(call -> List.of(referral.getState()));
        when(masters.findAssigneeForUpdate(SULLIVAN))
            .thenReturn(Optional.of(new Assignee("Master Sullivan", true, "sullivan-subject")));
        when(masters.findAssigneeForUpdate(OKAFOR))
            .thenReturn(Optional.of(new Assignee("Master Okafor", true, null)));

        referral = new Referral();
        referral.setId(REFERRAL_ID);
        DeedPoll parent = new DeedPoll();
        parent.setId(CASE_ID);
        referral.setDeedPoll(parent);
        referral.setReasons(List.of(ReferralReason.DomesticAbuse.name()));
        referral.setCreatedOn(LocalDateTime.of(2026, 10, 23, 9, 0));
        // Sent to Master Sullivan in Monday's morning window, and reminded once since.
        referral.setState(ReferralState.Claimed);
        referral.setClaimedBy("Master Sullivan");
        referral.setClaimedById("sullivan-subject");
        referral.setClaimedAt(SENT);
        referral.setAssignedAt(SENT);
        referral.setLastTouchedAt(SENT);
        referral.setAssignedMasterId(SULLIVAN);
        referral.setWindowDate(SENT_DAY);
        referral.setWindowSlot(WindowSlot.AM);
        referral.setRemindersSent(1);
        when(referrals.findByIdAndDeedPollIdForUpdate(REFERRAL_ID, CASE_ID)).thenReturn(Optional.of(referral));
    }

    @Nested
    @DisplayName("the gate")
    class Gate {

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"CASEWORKER", "JUDICIAL"})
        @DisplayName("without ALLOCATE_REFERRALS is a 403 before the body is read or anything is locked")
        void refused(Role role) {
            CurrentUser caller = CurrentUser.currentUser(List.of(role), "Ada", "subject-1");

            // A body that would be a 400: the 403 comes first, whatever was sent.
            assertThatThrownBy(() -> service.assign(CASE_ID, REFERRAL_ID.toString(), Map.of(), caller))
                .isInstanceOf(ForbiddenError.class);
            verifyNoInteractions(referrals, masters, notes, audit, deedPolls);
        }

        @Test
        @DisplayName("a caller with no deed poll role is the same 403")
        void noRole() {
            assertThatThrownBy(() -> service.assign(CASE_ID, REFERRAL_ID.toString(), body(OKAFOR), CASEWORKER))
                .isInstanceOf(ForbiddenError.class);
            verifyNoInteractions(referrals, masters);
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"TEAM_LEADER", "DELIVERY_MANAGER", "DEEDPOLL_ADMIN"})
        @DisplayName("a Team leader, a Delivery Manager and the legacy admin reassign")
        void admitted(Role role) {
            CurrentUser caller = CurrentUser.currentUser(List.of(role), "Tia", "subject-2");

            assertThat(service.assign(CASE_ID, REFERRAL_ID.toString(), body(OKAFOR), caller).getAssignedMasterId())
                .isEqualTo(OKAFOR);
        }

        @Test
        @DisplayName("with the capability, a missing masterId is the 400 on masterId")
        void shape() {
            assertThatThrownBy(() -> service.assign(CASE_ID, REFERRAL_ID.toString(), Map.of(), LEADER))
                .isInstanceOfSatisfying(InvalidInputError.class, refused -> assertThat(refused.errors())
                    .extracting(FieldError::field).containsExactly("masterId"));
            verifyNoInteractions(referrals, masters);
        }
    }

    @Nested
    @DisplayName("reassigning a Claimed referral")
    class Reassigning {

        @Test
        @DisplayName("moves it to the new Master, restarts the clocks, and keeps the window it was sent in")
        void reassigns() {
            LocalDateTime now = london(2026, 10, 28, 14, 10);

            Referral saved = service.assign(CASE_ID, REFERRAL_ID.toString(), OKAFOR, LEADER, now);

            assertThat(saved.getState()).isEqualTo(ReferralState.Claimed);
            assertThat(saved.getAssignedMasterId()).isEqualTo(OKAFOR);
            assertThat(saved.getClaimedBy()).isEqualTo("Master Okafor");
            assertThat(saved.getClaimedById()).isNull();
            assertThat(saved.getAssignedAt()).isEqualTo(now);
            assertThat(saved.getClaimedAt()).isEqualTo(now);
            assertThat(saved.getLastTouchedAt()).isEqualTo(now);
            assertThat(saved.getModifiedBy()).isEqualTo("Tia Leader");
            assertThat(saved.getWindowDate()).isEqualTo(SENT_DAY);
            assertThat(saved.getWindowSlot()).isEqualTo(WindowSlot.AM);
        }

        @Test
        @DisplayName("restarts the reminder count with the clock")
        void resetsTheReminders() {
            Referral saved = service.assign(CASE_ID, REFERRAL_ID.toString(), OKAFOR, LEADER,
                                            london(2026, 10, 28, 14, 10));

            assertThat(saved.getRemindersSent()).isZero();
        }

        @ParameterizedTest
        @ValueSource(strings = {"2026-10-28T06:00", "2026-10-28T12:00", "2026-10-28T19:00", "2026-10-31T11:00",
            "2026-08-31T09:00"})
        @DisplayName("works at any time, inside a window or not, a weekend or a bank holiday, and checks no cap")
        void anyTime(String londonTime) {
            when(settings.windowCap()).thenReturn(1);
            LocalDateTime now = LocalDateTime.parse(londonTime).atZone(ReferralWindowTimes.ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();

            Referral saved = service.assign(CASE_ID, REFERRAL_ID.toString(), OKAFOR, LEADER, now);

            assertThat(saved.getAssignedMasterId()).isEqualTo(OKAFOR);
            assertThat(saved.getWindowDate()).isEqualTo(SENT_DAY);
            // Neither the windows nor the cap is asked: a reassignment takes no place in any window.
            verify(settings, never()).windowCap();
            verify(settings, never()).referralWindows();
            verify(masters, never()).digestTook(any(), any());
        }

        @Test
        @DisplayName("to a linked Master takes their subject")
        void linked() {
            referral.setAssignedMasterId(OKAFOR);
            referral.setClaimedBy("Master Okafor");
            referral.setClaimedById(null);

            Referral saved = service.assign(CASE_ID, REFERRAL_ID.toString(), SULLIVAN, LEADER,
                                            london(2026, 10, 28, 9, 0));

            assertThat(saved.getClaimedById()).isEqualTo("sullivan-subject");
        }

        @Test
        @DisplayName("writes a note naming both Masters and audits REFERRAL_ASSIGNED, never the subject")
        void noteAndAudit() {
            LocalDateTime now = london(2026, 10, 28, 14, 10);
            service.assign(CASE_ID, REFERRAL_ID.toString(), OKAFOR, LEADER, now);

            ArgumentCaptor<CreateNoteInput> note = ArgumentCaptor.forClass(CreateNoteInput.class);
            verify(notes).createNote(eq(CASE_ID), note.capture(), eq(LEADER.noteAuthor()));
            assertThat(note.getValue().note()).isEqualTo("Referral raised on 23 October 2026, reasons: Domestic "
                + "abuse considerations, reassigned from Master Sullivan to Master Okafor.");
            AuditEntry entry = recordedEntry();
            assertThat(entry.action()).isEqualTo(AuditAction.REFERRAL_ASSIGNED);
            assertThat(entry.fields()).containsExactly("state", "claimedBy", "assignedAt");
            assertThat(recordedValues()).containsExactly(
                AuditValue.of("state", ReferralState.Claimed, ReferralState.Claimed),
                AuditValue.of("claimedBy", "Master Sullivan", "Master Okafor"),
                AuditValue.of("assignedAt", SENT, now));
            assertThat(recordedValues().toString()).doesNotContain("sullivan-subject");
        }

        @Test
        @DisplayName("locks the referral and then the Master, then recomputes both read-models")
        void order() {
            service.assign(CASE_ID, REFERRAL_ID.toString(), OKAFOR, LEADER, london(2026, 10, 28, 9, 15));

            InOrder order = inOrder(referrals, masters, deedPolls);
            order.verify(referrals).findByIdAndDeedPollIdForUpdate(REFERRAL_ID, CASE_ID);
            order.verify(masters).findAssigneeForUpdate(OKAFOR);
            order.verify(referrals).save(referral);
            order.verify(deedPolls).writeCurrentReferralState(CASE_ID, ReferralState.Claimed);
            order.verify(deedPolls).lockForConfirmationRecompute(CASE_ID);
        }

        @Test
        @DisplayName("to the Master it already has is a no-op with no note, no audit and nothing restarted")
        void sameMaster() {
            Referral saved = service.assign(CASE_ID, REFERRAL_ID.toString(), SULLIVAN, LEADER,
                                            london(2026, 10, 28, 9, 0));

            assertThat(saved.getAssignedAt()).isEqualTo(SENT);
            assertThat(saved.getRemindersSent()).isEqualTo(1);
            assertNothingWritten();
        }

        @Test
        @DisplayName("a claim made before assignment existed is reassigned, naming its claimer, and gains no window")
        void legacyClaim() {
            referral.setAssignedMasterId(null);
            referral.setAssignedAt(null);
            referral.setWindowDate(null);
            referral.setWindowSlot(null);
            referral.setClaimedBy("District Judge Bell");
            referral.setClaimedById("judge-3");

            Referral saved = service.assign(CASE_ID, REFERRAL_ID.toString(), SULLIVAN, LEADER,
                                            london(2026, 10, 28, 9, 0));

            assertThat(saved.getAssignedMasterId()).isEqualTo(SULLIVAN);
            assertThat(saved.getClaimedById()).isEqualTo("sullivan-subject");
            assertThat(saved.getWindowDate()).isNull();
            assertThat(saved.getWindowSlot()).isNull();
            ArgumentCaptor<CreateNoteInput> note = ArgumentCaptor.forClass(CreateNoteInput.class);
            verify(notes).createNote(eq(CASE_ID), note.capture(), any());
            assertThat(note.getValue().note()).endsWith("reassigned from District Judge Bell to Master Sullivan.");
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("an Open referral is a 409: it is sent from the queue, and nothing is written")
        void open() {
            referral.setState(ReferralState.Open);
            referral.setClaimedBy(null);
            referral.setClaimedById(null);
            referral.setAssignedMasterId(null);

            assertThatThrownBy(() -> service.assign(CASE_ID, REFERRAL_ID.toString(), OKAFOR, LEADER,
                                                    london(2026, 10, 28, 9, 0)))
                .isInstanceOfSatisfying(ConflictError.class, refused -> {
                    assertThat(refused).hasMessage(
                        "Send this referral from the queue: it has not been sent to a Master yet.");
                    assertThat(refused.errors()).isEmpty();
                });
            assertNothingWritten();
        }

        @Test
        @DisplayName("an unknown Master is a 400 on masterId, and nothing is written")
        void unknownMaster() {
            UUID nobody = UUID.randomUUID();
            when(masters.findAssigneeForUpdate(nobody)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.assign(CASE_ID, REFERRAL_ID.toString(), body(nobody), LEADER))
                .isInstanceOfSatisfying(InvalidInputError.class, refused -> assertThat(refused.errors())
                    .containsExactly(new FieldError("masterId", "Select a Master who is available")));
            assertNothingWritten();
        }

        @Test
        @DisplayName("an unavailable Master is the same 400")
        void unavailableMaster() {
            when(masters.findAssigneeForUpdate(OKAFOR))
                .thenReturn(Optional.of(new Assignee("Master Okafor", false, null)));

            assertThatThrownBy(() -> service.assign(CASE_ID, REFERRAL_ID.toString(), body(OKAFOR), LEADER))
                .isInstanceOf(InvalidInputError.class)
                .hasMessage("Select a Master who is available");
            assertNothingWritten();
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Decided", "Closed", "Withdrawn"})
        @DisplayName("a referral not waiting for a direction keeps its 409")
        void notWaiting(ReferralState state) {
            referral.setState(state);

            assertThatThrownBy(() -> service.assign(CASE_ID, REFERRAL_ID.toString(), body(OKAFOR), LEADER))
                .isInstanceOf(ConflictError.class)
                .hasMessage("Referral " + REFERRAL_ID + " is " + state
                    + ": only a referral waiting for a direction can be assigned");
            assertNothingWritten();
        }
    }

    @Nested
    @DisplayName("creating a referral")
    class Create {

        @BeforeEach
        void caseExists() {
            DeedPoll parent = new DeedPoll();
            parent.setId(CASE_ID);
            when(deedPolls.findById(CASE_ID)).thenReturn(Optional.of(parent));
            when(referrals.findStatesByDeedPollId(anyString())).thenReturn(List.of(ReferralState.Claimed));
        }

        @Test
        @DisplayName("creates it Open, waiting to be sent, with one note and one event, and touches no Master (F1)")
        void alwaysOpen() {
            when(referrals.findStatesByDeedPollId(anyString())).thenReturn(List.of(ReferralState.Open));

            Referral saved = service.createReferral(CASE_ID, new CreateReferralInput(
                List.of(ReferralReason.DomesticAbuse), Optional.empty(), Urgency.Normal, Optional.empty()), CASEWORKER);

            assertThat(saved.getState()).isEqualTo(ReferralState.Open);
            assertThat(saved.getAssignedMasterId()).isNull();
            assertThat(saved.getAssignedAt()).isNull();
            assertThat(saved.getWindowDate()).isNull();
            ArgumentCaptor<CreateNoteInput> note = ArgumentCaptor.forClass(CreateNoteInput.class);
            verify(notes).createNote(eq(CASE_ID), note.capture(), any());
            assertThat(note.getValue().note()).startsWith("Referral raised, waiting to be sent to a Master.");
            ArgumentCaptor<AuditEntry> entries = ArgumentCaptor.forClass(AuditEntry.class);
            verify(audit).record(entries.capture(), any());
            assertThat(entries.getValue().action()).isEqualTo(AuditAction.REFERRAL_CREATED);
            verify(deedPolls).writeCurrentReferralState(CASE_ID, ReferralState.Open);
            verifyNoInteractions(masters);
        }
    }

    private void assertNothingWritten() {
        verify(referrals, never()).save(any());
        verify(notes, never()).createNote(anyString(), any(), any());
        verifyNoInteractions(audit);
        verify(deedPolls, never()).writeCurrentReferralState(any(), any());
    }

    private AuditEntry recordedEntry() {
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(entry.capture(), any());
        return entry.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<AuditValue> recordedValues() {
        ArgumentCaptor<Collection<AuditValue>> values = ArgumentCaptor.forClass(Collection.class);
        verify(audit).record(any(), values.capture());
        return List.copyOf(values.getValue());
    }

    private static Map<String, Object> body(UUID masterId) {
        return Map.of("masterId", masterId.toString());
    }

    /**
     * A London wall-clock time as the UTC wall time {@code AuditClock} gives.
     */
    private static LocalDateTime london(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ReferralWindowTimes.ZONE)
            .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
