package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntry;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditTrail;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditValue;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.notes.CreateNoteInput;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;
import uk.gov.hmcts.reform.deedpoll.domain.settings.BankHolidays;
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
import uk.gov.hmcts.reform.deedpoll.repository.MasterWindowCount;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
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
 * {@link ReferralService#assign} (plan D2) and the create, which since plan F1 takes no Master, with no database: the
 * rules, their
 * order, the window an assignment counts towards, the cap, the note and the audit event. The cap under concurrency
 * needs two committed transactions, so it is {@code ReferralAssignmentConcurrencyTest}'s, and the HTTP answers are
 * {@code CaseworkerReferralAssignmentTest}'s.
 */
class ReferralAssignmentTest {

    private static final String CASE_ID = "case-1";
    private static final UUID REFERRAL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SULLIVAN = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OKAFOR = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");

    private static final CurrentUser CASEWORKER = CurrentUser.currentUser(List.of(), "Ada Caseworker", "caseworker-1");

    private static final ReferralWindowTimes WINDOWS = new ReferralWindowTimes(
        LocalTime.of(7, 0), LocalTime.of(10, 30), LocalTime.of(14, 0), LocalTime.of(14, 30));

    /** The August bank holiday, 2026: a Monday. */
    private static final LocalDate BANK_HOLIDAY = LocalDate.of(2026, 8, 31);

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
        BankHolidays bankHolidays = mock(BankHolidays.class);
        when(bankHolidays.isWorkingDay(any())).thenAnswer(call -> {
            LocalDate day = call.getArgument(0);
            return day.getDayOfWeek().getValue() <= DayOfWeek.FRIDAY.getValue() && !day.equals(BANK_HOLIDAY);
        });
        when(settings.windowCap()).thenReturn(10);
        when(settings.referralWindows()).thenReturn(WINDOWS);
        when(settings.processingWindow()).thenReturn(Duration.ofDays(7));
        service = new ReferralService(deedPolls, referrals, notes, settings, mock(EntityManager.class), audit,
                                      masters, bankHolidays);

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
        referral.setState(ReferralState.Open);
        referral.setReasons(List.of(ReferralReason.DomesticAbuse.name()));
        referral.setCreatedOn(LocalDateTime.of(2026, 10, 27, 9, 0));
        when(referrals.findByIdAndDeedPollIdForUpdate(REFERRAL_ID, CASE_ID)).thenReturn(Optional.of(referral));
    }

    @Nested
    @DisplayName("assigning an Open referral")
    class FromOpen {

        @Test
        @DisplayName("makes it Claimed by the Master, with the window, the clocks and the linked subject")
        void assigns() {
            LocalDateTime now = london(2026, 10, 28, 9, 15);

            Referral saved = service.assign(CASE_ID, REFERRAL_ID.toString(), SULLIVAN, CASEWORKER, now);

            assertThat(saved.getState()).isEqualTo(ReferralState.Claimed);
            assertThat(saved.getClaimedBy()).isEqualTo("Master Sullivan");
            assertThat(saved.getClaimedById()).isEqualTo("sullivan-subject");
            assertThat(saved.getAssignedMasterId()).isEqualTo(SULLIVAN);
            assertThat(saved.getClaimedAt()).isEqualTo(now);
            assertThat(saved.getAssignedAt()).isEqualTo(now);
            assertThat(saved.getLastTouchedAt()).isEqualTo(now);
            assertThat(saved.getWindowDate()).isEqualTo(LocalDate.of(2026, 10, 28));
            assertThat(saved.getWindowSlot()).isEqualTo(WindowSlot.AM);
            assertThat(saved.getModifiedBy()).isEqualTo("Ada Caseworker");
        }

        @Test
        @DisplayName("starts the reminder count again, though the processing window left it on the row (plan D3)")
        void resetsTheReminders() {
            // unassignOverdue returns a referral to Open with one UPDATE that leaves the count; assigning clears it.
            referral.setRemindersSent(3);

            Referral saved = service.assign(CASE_ID, REFERRAL_ID.toString(), SULLIVAN, CASEWORKER,
                                            london(2026, 10, 28, 9, 15));

            assertThat(saved.getRemindersSent()).isZero();
        }

        @Test
        @DisplayName("to a Master not yet linked leaves the owner subject null")
        void unlinked() {
            Referral saved = service.assign(CASE_ID, REFERRAL_ID.toString(), OKAFOR, CASEWORKER,
                                            london(2026, 10, 28, 9, 15));

            assertThat(saved.getClaimedBy()).isEqualTo("Master Okafor");
            assertThat(saved.getClaimedById()).isNull();
        }

        @Test
        @DisplayName("writes one note naming the Master, as the caseworker")
        void note() {
            service.assign(CASE_ID, REFERRAL_ID.toString(), SULLIVAN, CASEWORKER, london(2026, 10, 28, 9, 15));

            ArgumentCaptor<CreateNoteInput> note = ArgumentCaptor.forClass(CreateNoteInput.class);
            verify(notes).createNote(eq(CASE_ID), note.capture(), eq(CASEWORKER.noteAuthor()));
            assertThat(note.getValue().note()).isEqualTo(
                "Referral raised on 27 October 2026, reasons: Domestic abuse considerations, assigned to Master "
                    + "Sullivan.");
        }

        @Test
        @DisplayName("records REFERRAL_ASSIGNED with the state, claimedBy and assignedAt, and never the subject")
        void auditEvent() {
            LocalDateTime now = london(2026, 10, 28, 9, 15);
            service.assign(CASE_ID, REFERRAL_ID.toString(), SULLIVAN, CASEWORKER, now);

            AuditEntry entry = recordedEntry();
            assertThat(entry.action()).isEqualTo(AuditAction.REFERRAL_ASSIGNED);
            assertThat(entry.fields()).containsExactly("state", "claimedBy", "assignedAt");
            assertThat(entry.detail()).containsEntry("fromState", "Open").containsEntry("toState", "Claimed");
            assertThat(recordedValues()).containsExactly(
                AuditValue.of("state", ReferralState.Open, ReferralState.Claimed),
                AuditValue.of("claimedBy", null, "Master Sullivan"),
                AuditValue.of("assignedAt", null, now));
            assertThat(recordedValues().toString()).doesNotContain("sullivan-subject");
        }

        @Test
        @DisplayName("recomputes both read-models, after locking the referral and then the Master")
        void order() {
            service.assign(CASE_ID, REFERRAL_ID.toString(), SULLIVAN, CASEWORKER, london(2026, 10, 28, 9, 15));

            InOrder order = inOrder(referrals, masters, deedPolls);
            order.verify(referrals).findByIdAndDeedPollIdForUpdate(REFERRAL_ID, CASE_ID);
            order.verify(masters).findAssigneeForUpdate(SULLIVAN);
            order.verify(referrals).countByStateAndAssignedMasterIdAndWindowDateAndWindowSlot(
                ReferralState.Claimed, SULLIVAN, LocalDate.of(2026, 10, 28), WindowSlot.AM);
            order.verify(referrals).save(referral);
            order.verify(deedPolls).writeCurrentReferralState(CASE_ID, ReferralState.Claimed);
            order.verify(deedPolls).lockForConfirmationRecompute(CASE_ID);
        }
    }

    @Nested
    @DisplayName("the window an assignment counts towards")
    class Windows {

        @Test
        @DisplayName("raised at 10:31 counts towards that afternoon")
        void lateMorning() {
            assertWindow(london(2026, 10, 28, 10, 31), LocalDate.of(2026, 10, 28), WindowSlot.PM);
        }

        @Test
        @DisplayName("raised at 14:31 counts towards the next working day's morning")
        void lateAfternoon() {
            assertWindow(london(2026, 10, 28, 14, 31), LocalDate.of(2026, 10, 29), WindowSlot.AM);
        }

        @Test
        @DisplayName("raised on a Saturday counts towards Monday's morning")
        void saturday() {
            assertWindow(london(2026, 10, 31, 11, 0), LocalDate.of(2026, 11, 2), WindowSlot.AM);
        }

        @Test
        @DisplayName("raised on a bank holiday counts towards the next working day's morning")
        void bankHoliday() {
            assertWindow(london(2026, 8, 31, 9, 0), LocalDate.of(2026, 9, 1), WindowSlot.AM);
        }

        @Test
        @DisplayName("a morning the digest already took counts towards that afternoon")
        void morningAlreadyDigested() {
            when(masters.digestTook(LocalDate.of(2026, 10, 28), WindowSlot.AM)).thenReturn(true);

            assertWindow(london(2026, 10, 28, 10, 29), LocalDate.of(2026, 10, 28), WindowSlot.PM);
        }

        @Test
        @DisplayName("an afternoon the digest already took counts towards the next working day's morning")
        void afternoonAlreadyDigested() {
            when(masters.digestTook(LocalDate.of(2026, 10, 30), WindowSlot.PM)).thenReturn(true);

            assertWindow(london(2026, 10, 30, 14, 29), LocalDate.of(2026, 11, 2), WindowSlot.AM);
        }

        @Test
        @DisplayName("takes the window lock before the Master's, which the digest's email rows wait on")
        void asksBeforeTheMastersLock() {
            service.assign(CASE_ID, REFERRAL_ID.toString(), SULLIVAN, CASEWORKER, london(2026, 10, 28, 9, 0));

            InOrder order = inOrder(masters);
            order.verify(masters).digestTook(LocalDate.of(2026, 10, 28), WindowSlot.AM);
            order.verify(masters).findAssigneeForUpdate(SULLIVAN);
        }

        private void assertWindow(LocalDateTime now, LocalDate date, WindowSlot slot) {
            Referral saved = service.assign(CASE_ID, REFERRAL_ID.toString(), SULLIVAN, CASEWORKER, now);

            assertThat(saved.getWindowDate()).isEqualTo(date);
            assertThat(saved.getWindowSlot()).isEqualTo(slot);
            verify(referrals).countByStateAndAssignedMasterIdAndWindowDateAndWindowSlot(
                ReferralState.Claimed, SULLIVAN, date, slot);
        }
    }

    @Nested
    @DisplayName("the per-Master cap")
    class Cap {

        @Test
        @DisplayName("one below the cap is admitted")
        void belowCap() {
            held(9);

            assertThat(service.assign(CASE_ID, REFERRAL_ID.toString(), SULLIVAN, CASEWORKER,
                                      london(2026, 10, 28, 9, 15)).getState()).isEqualTo(ReferralState.Claimed);
        }

        @Test
        @DisplayName("at the cap is a 409 on masterId naming the window, and nothing is written")
        void atCap() {
            held(10);

            assertThatThrownBy(() -> service.assign(CASE_ID, REFERRAL_ID.toString(), SULLIVAN, CASEWORKER,
                                                    london(2026, 10, 28, 9, 15)))
                .isInstanceOfSatisfying(ConflictError.class, refused -> {
                    String sentence = "This Master already has 10 referrals for the morning of 28 October 2026."
                        + " Choose another Master.";
                    assertThat(refused).hasMessage(sentence);
                    assertThat(refused.errors()).containsExactly(new FieldError("masterId", sentence));
                });
            assertNothingWritten();
        }

        @Test
        @DisplayName("names the afternoon, and a lowered cap's count as it stands")
        void afternoon() {
            when(settings.windowCap()).thenReturn(1);
            when(referrals.countByStateAndAssignedMasterIdAndWindowDateAndWindowSlot(any(), any(), any(), any()))
                .thenReturn(1L);

            assertThatThrownBy(() -> service.assign(CASE_ID, REFERRAL_ID.toString(), SULLIVAN, CASEWORKER,
                                                    london(2026, 10, 28, 11, 0)))
                .hasMessage("This Master already has 1 referral for the afternoon of 28 October 2026."
                    + " Choose another Master.");
        }

        private void held(long count) {
            when(referrals.countByStateAndAssignedMasterIdAndWindowDateAndWindowSlot(
                ReferralState.Claimed, SULLIVAN, LocalDate.of(2026, 10, 28), WindowSlot.AM)).thenReturn(count);
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("an unknown Master is a 400 on masterId, and nothing is written")
        void unknownMaster() {
            UUID nobody = UUID.randomUUID();
            when(masters.findAssigneeForUpdate(nobody)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.assign(CASE_ID, REFERRAL_ID.toString(), nobody, CASEWORKER))
                .isInstanceOfSatisfying(InvalidInputError.class, refused -> assertThat(refused.errors())
                    .containsExactly(new FieldError("masterId", "Select a Master who is available")));
            assertNothingWritten();
        }

        @Test
        @DisplayName("an unavailable Master is the same 400")
        void unavailableMaster() {
            when(masters.findAssigneeForUpdate(SULLIVAN))
                .thenReturn(Optional.of(new Assignee("Master Sullivan", false, null)));

            assertThatThrownBy(() -> service.assign(CASE_ID, REFERRAL_ID.toString(), SULLIVAN, CASEWORKER))
                .isInstanceOf(InvalidInputError.class)
                .hasMessage("Select a Master who is available");
            assertNothingWritten();
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Decided", "Closed", "Withdrawn"})
        @DisplayName("a referral not waiting for a direction is a 409")
        void notWaiting(ReferralState state) {
            referral.setState(state);

            assertThatThrownBy(() -> service.assign(CASE_ID, REFERRAL_ID.toString(), SULLIVAN, CASEWORKER))
                .isInstanceOf(ConflictError.class)
                .hasMessage("Referral " + REFERRAL_ID + " is " + state
                    + ": only a referral waiting for a direction can be assigned");
            assertNothingWritten();
        }
    }

    @Nested
    @DisplayName("reassigning a Claimed referral")
    class FromClaimed {

        private final LocalDateTime first = london(2026, 10, 26, 9, 0);

        @BeforeEach
        void assignedToSullivan() {
            referral.setState(ReferralState.Claimed);
            referral.setClaimedBy("Master Sullivan");
            referral.setClaimedById("sullivan-subject");
            referral.setClaimedAt(first);
            referral.setAssignedAt(first);
            referral.setLastTouchedAt(first);
            referral.setAssignedMasterId(SULLIVAN);
            referral.setWindowDate(LocalDate.of(2026, 10, 26));
            referral.setWindowSlot(WindowSlot.AM);
        }

        @Test
        @DisplayName("to another Master moves it, restarts the clocks and takes the new window")
        void reassigns() {
            LocalDateTime now = london(2026, 10, 28, 14, 10);

            Referral saved = service.assign(CASE_ID, REFERRAL_ID.toString(), OKAFOR, CASEWORKER, now);

            assertThat(saved.getAssignedMasterId()).isEqualTo(OKAFOR);
            assertThat(saved.getClaimedBy()).isEqualTo("Master Okafor");
            assertThat(saved.getClaimedById()).isNull();
            assertThat(saved.getAssignedAt()).isEqualTo(now);
            assertThat(saved.getClaimedAt()).isEqualTo(now);
            assertThat(saved.getLastTouchedAt()).isEqualTo(now);
            assertThat(saved.getWindowDate()).isEqualTo(LocalDate.of(2026, 10, 28));
            assertThat(saved.getWindowSlot()).isEqualTo(WindowSlot.PM);
            verify(referrals).countByStateAndAssignedMasterIdAndWindowDateAndWindowSlot(
                ReferralState.Claimed, OKAFOR, LocalDate.of(2026, 10, 28), WindowSlot.PM);
        }

        @Test
        @DisplayName("restarts the reminder count with the clock (plan D3)")
        void resetsTheReminders() {
            referral.setRemindersSent(2);

            Referral saved = service.assign(CASE_ID, REFERRAL_ID.toString(), OKAFOR, CASEWORKER,
                                            london(2026, 10, 28, 14, 10));

            assertThat(saved.getRemindersSent()).isZero();
        }

        @Test
        @DisplayName("to the same Master leaves the reminder count alone, as it leaves the clock")
        void sameMasterKeepsTheReminders() {
            referral.setRemindersSent(2);

            service.assign(CASE_ID, REFERRAL_ID.toString(), SULLIVAN, CASEWORKER, london(2026, 10, 28, 14, 10));

            assertThat(referral.getRemindersSent()).isEqualTo(2);
        }

        @Test
        @DisplayName("writes a note naming both Masters and audits the old and new values")
        void noteAndAudit() {
            LocalDateTime now = london(2026, 10, 28, 14, 10);
            service.assign(CASE_ID, REFERRAL_ID.toString(), OKAFOR, CASEWORKER, now);

            ArgumentCaptor<CreateNoteInput> note = ArgumentCaptor.forClass(CreateNoteInput.class);
            verify(notes).createNote(eq(CASE_ID), note.capture(), eq(CASEWORKER.noteAuthor()));
            assertThat(note.getValue().note()).isEqualTo("Referral raised on 27 October 2026, reasons: Domestic "
                + "abuse considerations, "
                + "reassigned from Master Sullivan to Master Okafor.");
            assertThat(recordedValues()).containsExactly(
                AuditValue.of("state", ReferralState.Claimed, ReferralState.Claimed),
                AuditValue.of("claimedBy", "Master Sullivan", "Master Okafor"),
                AuditValue.of("assignedAt", first, now));
        }

        @Test
        @DisplayName("checks the cap against the new Master")
        void capOfTheNewMaster() {
            when(referrals.countByStateAndAssignedMasterIdAndWindowDateAndWindowSlot(
                eq(ReferralState.Claimed), eq(OKAFOR), any(), any())).thenReturn(10L);

            assertThatThrownBy(() -> service.assign(CASE_ID, REFERRAL_ID.toString(), OKAFOR, CASEWORKER,
                                                    london(2026, 10, 28, 9, 0)))
                .isInstanceOf(ConflictError.class);
            assertThat(referral.getAssignedMasterId()).isEqualTo(SULLIVAN);
        }

        @Test
        @DisplayName("to the Master it already has is a no-op with no note, no audit and no cap check")
        void sameMaster() {
            Referral saved = service.assign(CASE_ID, REFERRAL_ID.toString(), SULLIVAN, CASEWORKER,
                                            london(2026, 10, 28, 9, 0));

            assertThat(saved.getAssignedAt()).isEqualTo(first);
            assertThat(saved.getWindowDate()).isEqualTo(LocalDate.of(2026, 10, 26));
            verify(referrals, never()).countByStateAndAssignedMasterIdAndWindowDateAndWindowSlot(
                any(), any(), any(), any());
            assertNothingWritten();
        }

        @Test
        @DisplayName("a claim made before assignment existed is reassigned, naming its claimer")
        void legacyClaim() {
            referral.setAssignedMasterId(null);
            referral.setAssignedAt(null);
            referral.setWindowDate(null);
            referral.setWindowSlot(null);
            referral.setClaimedBy("District Judge Bell");
            referral.setClaimedById("judge-3");

            Referral saved = service.assign(CASE_ID, REFERRAL_ID.toString(), SULLIVAN, CASEWORKER,
                                            london(2026, 10, 28, 9, 0));

            assertThat(saved.getAssignedMasterId()).isEqualTo(SULLIVAN);
            assertThat(saved.getClaimedById()).isEqualTo("sullivan-subject");
            ArgumentCaptor<CreateNoteInput> note = ArgumentCaptor.forClass(CreateNoteInput.class);
            verify(notes).createNote(eq(CASE_ID), note.capture(), any());
            assertThat(note.getValue().note()).endsWith("reassigned from District Judge Bell to Master Sullivan.");
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

    @Test
    @DisplayName("the current window's load is one grouped count, with the cap")
    void currentWindowLoad() {
        when(referrals.countByMasterInWindow(eq(ReferralState.Claimed), any(), any()))
            .thenReturn(List.of(new MasterWindowCount(SULLIVAN, 3)));

        ReferralWindowLoad load = service.currentWindowLoad();

        assertThat(load.cap()).isEqualTo(10);
        assertThat(load.assignedTo(SULLIVAN)).isEqualTo(3);
        assertThat(load.assignedTo(OKAFOR)).isZero();
        verify(referrals).countByMasterInWindow(ReferralState.Claimed, load.window().date(), load.window().slot());
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

    /**
     * A London wall-clock time as the UTC wall time {@code AuditClock} gives.
     */
    private static LocalDateTime london(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ReferralWindowTimes.ZONE)
            .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
