package uk.gov.hmcts.reform.deedpoll.domain.masters;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
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
import uk.gov.hmcts.reform.deedpoll.repository.MasterRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class MasterServiceTest {

    private static final CurrentUser DELIVERY_MANAGER =
        CurrentUser.currentUser(List.of(Role.DELIVERY_MANAGER), "A Delivery Manager", "dm-subject");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final String ADDRESS = "master.sullivan@judiciary.example";
    private static final Master SULLIVAN = new Master(ID.toString(), "Master Sullivan", ADDRESS, true, false, 3);

    private final MasterRepository repository = mock(MasterRepository.class);
    private final ReferralRepository referrals = mock(ReferralRepository.class);
    private final AuditTrail audit = mock(AuditTrail.class);
    private final MasterService service = new MasterService(repository, referrals, audit);

    static Stream<List<Role>> notDeliveryManagers() {
        return Stream.of(List.of(Role.CASEWORKER), List.of(Role.TEAM_LEADER), List.of(Role.DEEDPOLL_ADMIN),
                         List.of(Role.JUDICIAL), List.of());
    }

    @ParameterizedTest
    @MethodSource("notDeliveryManagers")
    @DisplayName("everyone but a Delivery Manager is refused both writes before anything is read")
    void writesRefused(List<Role> roles) {
        CurrentUser caller = CurrentUser.currentUser(roles, "Staff", "subject");

        assertThatThrownBy(() -> service.create(caller, Map.of("name", "A", "email", ADDRESS)))
            .isInstanceOf(ForbiddenError.class);
        assertThatThrownBy(() -> service.update(caller, ID.toString(), Map.of("name", "A", "revision", 0)))
            .isInstanceOf(ForbiddenError.class);
        verifyNoInteractions(repository, audit);
    }

    @Test
    @DisplayName("a Delivery Manager with no subject is refused both writes before anything is read")
    void noSubject() {
        CurrentUser caller = CurrentUser.currentUser(List.of(Role.DELIVERY_MANAGER), "A Delivery Manager", null);

        assertThatThrownBy(() -> service.create(caller, Map.of("name", "A", "email", ADDRESS)))
            .isInstanceOf(UnauthorizedError.class);
        assertThatThrownBy(() -> service.update(caller, ID.toString(), Map.of("name", "A", "revision", 3)))
            .isInstanceOf(UnauthorizedError.class);
        verifyNoInteractions(repository, audit);
    }

    @Test
    @DisplayName("every staff role reads the list; a judge and a roleless caller are refused before the read")
    void listIsForStaff() {
        given(repository.list()).willReturn(List.of(SULLIVAN));
        for (Role role : List.of(Role.CASEWORKER, Role.TEAM_LEADER, Role.DELIVERY_MANAGER, Role.DEEDPOLL_ADMIN)) {
            assertThat(service.list(CurrentUser.currentUser(List.of(role), "Staff", "s"))).containsExactly(SULLIVAN);
        }
        for (List<Role> roles : List.of(List.of(Role.JUDICIAL), List.<Role>of())) {
            MasterRepository untouched = mock(MasterRepository.class);
            assertThatThrownBy(() -> new MasterService(untouched, referrals, audit)
                .list(CurrentUser.currentUser(roles, "Not staff", "s"))).isInstanceOf(ForbiddenError.class);
            verifyNoInteractions(untouched);
        }
    }

    @Nested
    class Create {

        @Test
        @DisplayName("trims the name, normalises the email, defaults available and audits names only")
        void creates() {
            Master created = service.create(DELIVERY_MANAGER,
                Map.of("name", "  Master Sullivan ", "email", "  Master.Sullivan@Judiciary.EXAMPLE "));

            assertThat(created.name()).isEqualTo("Master Sullivan");
            assertThat(created.email()).isEqualTo(ADDRESS);
            assertThat(created.available()).isTrue();
            assertThat(created.linked()).isFalse();
            assertThat(created.revision()).isZero();
            verify(repository).insert(eq(UUID.fromString(created.id())), eq("Master Sullivan"), eq(ADDRESS),
                eq(true), any(), eq("dm-subject"), eq("A Delivery Manager"));
            verify(audit).record(AuditEntry.of(AuditAction.MASTER_ADDED, AuditActor.of(DELIVERY_MANAGER),
                    AuditEntities.MASTER, created.id(), null).withFields(List.of("name", "email", "available")),
                List.of());
        }

        @Test
        @DisplayName("an unavailable Master may be added")
        void unavailable() {
            assertThat(service.create(DELIVERY_MANAGER, Map.of("name", "M", "email", ADDRESS, "available", false))
                .available()).isFalse();
        }

        @Test
        @DisplayName("a duplicate email is 409 on the email field, without the address")
        void duplicate() {
            given(repository.emailTaken(ADDRESS, null)).willReturn(true);

            assertThatThrownBy(() -> service.create(DELIVERY_MANAGER, Map.of("name", "M", "email", ADDRESS)))
                .isInstanceOfSatisfying(ConflictError.class, error -> {
                    assertThat(error.getMessage()).isEqualTo("Another Master already has this email address.")
                        .doesNotContain(ADDRESS);
                    assertThat(error.errors()).extracting(FieldError::field).containsExactly("email");
                });
            verify(repository, never()).insert(any(), any(), any(), anyBoolean(), any(), any(), any());
            verifyNoInteractions(audit);
        }

        @Test
        @DisplayName("a duplicate that races past the check is still 409, and records nothing")
        void racedDuplicate() {
            willThrow(new DuplicateKeyException("Key (lower(email))=(" + ADDRESS + ") already exists"))
                .given(repository).insert(any(), any(), any(), anyBoolean(), any(), any(), any());

            assertThatThrownBy(() -> service.create(DELIVERY_MANAGER, Map.of("name", "M", "email", ADDRESS)))
                .isInstanceOf(ConflictError.class).hasMessageNotContaining(ADDRESS).hasNoCause();
            verifyNoInteractions(audit);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "  ", "no-at-sign", "@example.com", "name@", "two@@example.com",
            "a@b@example.com", "with space@example.com", "tab\t@example.com"})
        @DisplayName("an email without exactly one @ with text either side, or with whitespace, is refused")
        void badEmail(String email) {
            assertThatThrownBy(() -> service.create(DELIVERY_MANAGER, Map.of("name", "M", "email", email)))
                .isInstanceOfSatisfying(InvalidInputError.class, error ->
                    assertThat(error.errors()).extracting(FieldError::field).containsExactly("email"));
        }

        @Test
        @DisplayName("an email of 320 characters is accepted and 321 refused")
        void emailLength() {
            String domain = "@example.com";
            String longest = "a".repeat(320 - domain.length()) + domain;
            assertThat(service.create(DELIVERY_MANAGER, Map.of("name", "M", "email", longest)).email())
                .hasSize(320);
            assertThatThrownBy(() -> service.create(DELIVERY_MANAGER, Map.of("name", "M", "email", "a" + longest)))
                .isInstanceOf(InvalidInputError.class);
        }

        @Test
        @DisplayName("a name of 1 to 200 trimmed characters is accepted; blank, 201 and a non-string are refused")
        void nameBounds() {
            assertThat(service.create(DELIVERY_MANAGER, Map.of("name", " M ", "email", ADDRESS)).name())
                .isEqualTo("M");
            assertThat(service.create(DELIVERY_MANAGER, Map.of("name", "n".repeat(200), "email", ADDRESS)).name())
                .hasSize(200);
            for (Object name : List.of("   ", "n".repeat(201), 7)) {
                assertThatThrownBy(() -> service.create(DELIVERY_MANAGER, Map.of("name", name, "email", ADDRESS)))
                    .isInstanceOfSatisfying(InvalidInputError.class, error ->
                        assertThat(error.errors()).extracting(FieldError::field).containsExactly("name"));
            }
        }

        @Test
        @DisplayName("a missing field, an unknown key and a non-boolean available are refused")
        void malformed() {
            assertThatThrownBy(() -> service.create(DELIVERY_MANAGER, Map.of("email", ADDRESS)))
                .isInstanceOf(InvalidInputError.class);
            assertThatThrownBy(() -> service.create(DELIVERY_MANAGER, Map.of("name", "M")))
                .isInstanceOf(InvalidInputError.class);
            assertThatThrownBy(() -> service.create(DELIVERY_MANAGER,
                Map.of("name", "M", "email", ADDRESS, "idamSubject", "someone")))
                .isInstanceOf(InvalidInputError.class);
            assertThatThrownBy(() -> service.create(DELIVERY_MANAGER,
                Map.of("name", "M", "email", ADDRESS, "available", "yes")))
                .isInstanceOf(InvalidInputError.class);
            verifyNoInteractions(repository, audit, referrals);
        }
    }

    @Nested
    class Update {

        @BeforeEach
        void stored() {
            given(repository.findForUpdate(ID)).willReturn(Optional.of(SULLIVAN));
        }

        @Test
        @DisplayName("a stale revision is 409, even when nothing would change")
        void stale() {
            assertThatThrownBy(() -> service.update(DELIVERY_MANAGER, ID.toString(),
                Map.of("name", "Master Sullivan", "revision", 2))).isInstanceOf(ConflictError.class);
            verify(repository, never()).update(any(), any(), any(), anyBoolean(), anyBoolean(), anyInt(), any(),
                any(), any());
            verifyNoInteractions(audit);
        }

        @Test
        @DisplayName("saving the current values is a no-op with no audit")
        void noOp() {
            assertThat(service.update(DELIVERY_MANAGER, ID.toString(),
                Map.of("email", " MASTER.SULLIVAN@judiciary.example", "available", true, "revision", 3)))
                .isEqualTo(SULLIVAN);
            verify(repository, never()).update(any(), any(), any(), anyBoolean(), anyBoolean(), anyInt(), any(),
                any(), any());
            verifyNoInteractions(audit);
        }

        @Test
        @DisplayName("marking a Master unavailable changes only that and audits its name")
        void unavailable() {
            Master changed = service.update(DELIVERY_MANAGER, ID.toString(), Map.of("available", false,
                "revision", 3));

            assertThat(changed).isEqualTo(new Master(ID.toString(), "Master Sullivan", ADDRESS, false, false, 4));
            verify(repository).update(eq(ID), eq("Master Sullivan"), eq(ADDRESS), eq(false), eq(false), eq(4), any(),
                eq("dm-subject"), eq("A Delivery Manager"));
            verify(audit).record(AuditEntry.of(AuditAction.MASTER_CHANGED, AuditActor.of(DELIVERY_MANAGER),
                AuditEntities.MASTER, ID.toString(), null).withFields(List.of("available")), List.of());
        }

        @Test
        @DisplayName("changing a linked Master's email unlinks it, and the audit says so")
        void emailChangeUnlinks() {
            given(repository.findForUpdate(ID)).willReturn(Optional.of(
                new Master(ID.toString(), "Master Sullivan", ADDRESS, true, true, 3)));

            Master changed = service.update(DELIVERY_MANAGER, ID.toString(),
                Map.of("email", "New@Judiciary.example", "revision", 3));

            assertThat(changed.email()).isEqualTo("new@judiciary.example");
            assertThat(changed.linked()).isFalse();
            verify(repository).update(eq(ID), eq("Master Sullivan"), eq("new@judiciary.example"), eq(true),
                eq(true), eq(4), any(), any(), any());
            verify(audit).record(AuditEntry.of(AuditAction.MASTER_CHANGED, AuditActor.of(DELIVERY_MANAGER),
                AuditEntities.MASTER, ID.toString(), null).withFields(List.of("email", "linked")), List.of());
        }

        @Test
        @DisplayName("unlinking takes the old account off the Master's referrals, locked before the Master's row")
        void emailChangeDetachesTheReferrals() {
            given(repository.findForUpdate(ID)).willReturn(Optional.of(
                new Master(ID.toString(), "Master Sullivan", ADDRESS, true, true, 3)));

            service.update(DELIVERY_MANAGER, ID.toString(), Map.of("email", "new@judiciary.example", "revision", 3));

            InOrder order = inOrder(referrals, repository);
            order.verify(referrals).lockAssignedTo(ID);
            order.verify(repository).findForUpdate(ID);
            order.verify(referrals).detachFromUnlinkedMaster(ID);
        }

        @Test
        @DisplayName("a name change leaves a linked Master linked")
        void nameChangeKeepsTheLink() {
            given(repository.findForUpdate(ID)).willReturn(Optional.of(
                new Master(ID.toString(), "Master Sullivan", ADDRESS, true, true, 3)));

            assertThat(service.update(DELIVERY_MANAGER, ID.toString(), Map.of("name", "Master S", "revision", 3))
                .linked()).isTrue();
            verify(repository).update(eq(ID), eq("Master S"), eq(ADDRESS), eq(true), eq(false), eq(4), any(), any(),
                any());
            verifyNoInteractions(referrals);
        }

        @Test
        @DisplayName("an email another Master holds is 409 without the address")
        void duplicate() {
            given(repository.emailTaken("other@judiciary.example", ID)).willReturn(true);

            assertThatThrownBy(() -> service.update(DELIVERY_MANAGER, ID.toString(),
                Map.of("email", "Other@judiciary.example", "revision", 3)))
                .isInstanceOf(ConflictError.class).hasMessageNotContaining("other@");
            verifyNoInteractions(audit);
        }

        @Test
        @DisplayName("a duplicate that races past the check is still 409, and records nothing")
        void racedDuplicate() {
            willThrow(new DuplicateKeyException("Key (lower(email))=(other@judiciary.example) already exists"))
                .given(repository).update(any(), any(), any(), anyBoolean(), anyBoolean(), anyInt(), any(), any(),
                    any());

            assertThatThrownBy(() -> service.update(DELIVERY_MANAGER, ID.toString(),
                Map.of("email", "other@judiciary.example", "revision", 3)))
                .isInstanceOfSatisfying(ConflictError.class, error -> {
                    assertThat(error.getMessage()).isEqualTo("Another Master already has this email address.");
                    assertThat(error.errors()).extracting(FieldError::field).containsExactly("email");
                })
                .hasNoCause();
            verifyNoInteractions(audit);
        }

        @Test
        @DisplayName("no field, no revision or an unknown key is refused; an unknown or malformed id is 404")
        void malformed() {
            assertThatThrownBy(() -> service.update(DELIVERY_MANAGER, ID.toString(), Map.of("revision", 3)))
                .isInstanceOf(InvalidInputError.class);
            assertThatThrownBy(() -> service.update(DELIVERY_MANAGER, ID.toString(), Map.of("name", "M")))
                .isInstanceOf(InvalidInputError.class);
            assertThatThrownBy(() -> service.update(DELIVERY_MANAGER, ID.toString(),
                Map.of("name", "M", "revision", 3, "linked", false))).isInstanceOf(InvalidInputError.class);
            assertThatThrownBy(() -> service.update(DELIVERY_MANAGER, "not-a-uuid",
                Map.of("name", "M", "revision", 3))).isInstanceOf(NotFoundError.class);
            UUID unknown = UUID.randomUUID();
            given(repository.findForUpdate(unknown)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.update(DELIVERY_MANAGER, unknown.toString(),
                Map.of("name", "M", "revision", 3))).isInstanceOf(NotFoundError.class);
        }
    }

    @Nested
    class Linking {

        private static final CurrentUser JUDGE =
            CurrentUser.currentUser(List.of(Role.JUDICIAL), "Master Sullivan", "judge-subject", ADDRESS);

        private final Logger logger = (Logger) LoggerFactory.getLogger(MasterService.class);
        private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

        @BeforeEach
        void capture() {
            logs.start();
            logger.addAppender(logs);
        }

        @AfterEach
        void release() {
            logger.detachAppender(logs);
        }

        @Test
        @DisplayName("a judge whose email matches an unlinked entry links it, audited as the service")
        void links() {
            given(repository.linkingByEmail(ADDRESS)).willReturn(Optional.of(new MasterRepository.Linking(ID, null)));
            given(repository.link(eq(ID), eq(ADDRESS), eq("judge-subject"), any())).willReturn(true);

            service.linkIfListed(JUDGE);

            verify(audit).record(AuditEntry.of(AuditAction.MASTER_LINKED, AuditActor.system(), AuditEntities.MASTER,
                ID.toString(), null).withFields(List.of("linked")), List.of());
        }

        @Test
        @DisplayName("linking gives the Master the referrals assigned while unlinked and attaches their pre-V43 claims")
        void linksTheReferralSide() {
            given(repository.linkingByEmail(ADDRESS)).willReturn(Optional.of(new MasterRepository.Linking(ID, null)));
            given(repository.link(eq(ID), eq(ADDRESS), eq("judge-subject"), any())).willReturn(true);

            service.linkIfListed(JUDGE);

            // Plan D2, in the linking transaction: both by this entry and this subject, never another's, and the
            // referrals locked before the Master's row, in assignment's order, so the two cannot deadlock.
            InOrder order = inOrder(referrals, repository);
            order.verify(referrals).lockForLinking(ID, "judge-subject");
            order.verify(repository).link(eq(ID), eq(ADDRESS), eq("judge-subject"), any());
            order.verify(referrals).attachToLinkedMaster(ID, "judge-subject");
            order.verify(referrals).attachLegacyClaims(ID, "judge-subject");
        }

        @Test
        @DisplayName("losing the race to link records nothing")
        void lostTheRace() {
            given(repository.linkingByEmail(ADDRESS)).willReturn(Optional.of(new MasterRepository.Linking(ID, null)));
            given(repository.link(eq(ID), eq(ADDRESS), eq("judge-subject"), any())).willReturn(false);

            service.linkIfListed(JUDGE);

            verifyNoInteractions(audit);
            verify(referrals, never()).attachToLinkedMaster(any(), any());
            verify(referrals, never()).attachLegacyClaims(any(), any());
        }

        @Test
        @DisplayName("an entry already linked to this judge is left alone")
        void alreadyLinked() {
            given(repository.linkingByEmail(ADDRESS))
                .willReturn(Optional.of(new MasterRepository.Linking(ID, "judge-subject")));

            service.linkIfListed(JUDGE);

            verify(repository, never()).link(any(), anyString(), anyString(), any());
            verifyNoInteractions(audit, referrals);
            assertThat(logs.list).isEmpty();
        }

        @Test
        @DisplayName("an entry linked to another account is left alone, with a fixed WARN naming nobody")
        void linkedToAnother() {
            given(repository.linkingByEmail(ADDRESS))
                .willReturn(Optional.of(new MasterRepository.Linking(ID, "another-subject")));

            service.linkIfListed(JUDGE);

            verify(repository, never()).link(any(), anyString(), anyString(), any());
            verifyNoInteractions(audit);
            assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("A judicial caller's email matches a Master already linked to another account");
        }

        @Test
        @DisplayName("a judge already linked to another entry is not linked to a second")
        void judgeLinkedElsewhere() {
            given(repository.linkingByEmail(ADDRESS)).willReturn(Optional.of(new MasterRepository.Linking(ID, null)));
            given(repository.subjectLinked("judge-subject")).willReturn(true);

            service.linkIfListed(JUDGE);

            verify(repository, never()).link(any(), anyString(), anyString(), any());
            verifyNoInteractions(audit);
            assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("A judicial caller already linked to one Master has the email of another");
        }

        @Test
        @DisplayName("no listed email links nothing, and never creates an entry")
        void notListed() {
            given(repository.linkingByEmail(ADDRESS)).willReturn(Optional.empty());

            service.linkIfListed(JUDGE);

            verify(repository, never()).link(any(), anyString(), anyString(), any());
            verify(repository, never()).insert(any(), any(), any(), anyBoolean(), any(), any(), any());
        }

        @Test
        @DisplayName("a caseworker, a judge with no email and a judge with no subject link nothing")
        void notEligible() {
            for (CurrentUser caller : List.of(
                CurrentUser.currentUser(List.of(Role.CASEWORKER, Role.DELIVERY_MANAGER), "Staff", "s", ADDRESS),
                CurrentUser.currentUser(List.of(Role.JUDICIAL), "Judge", "s", null),
                CurrentUser.currentUser(List.of(Role.JUDICIAL), "Judge", null, ADDRESS))) {
                service.linkIfListed(caller);
            }
            verifyNoInteractions(repository, audit);
        }
    }

    @Test
    @DisplayName("the update's unlink flag reaches the repository only for a changed email on a linked Master")
    void unlinkOnlyWhenLinked() {
        given(repository.findForUpdate(ID)).willReturn(Optional.of(SULLIVAN));
        Map<String, Object> fields = new HashMap<>();
        fields.put("email", "new@judiciary.example");
        fields.put("revision", 3);

        service.update(DELIVERY_MANAGER, ID.toString(), fields);

        verify(repository).update(eq(ID), any(), eq("new@judiciary.example"), anyBoolean(), eq(false), eq(4), any(),
            any(), any());
        verify(repository).emailTaken(eq("new@judiciary.example"), eq(ID));
        verify(repository, never()).emailTaken(any(), isNull());
    }
}
