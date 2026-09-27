package uk.gov.hmcts.reform.deedpoll.ccd;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.reform.deedpoll.entity.CcdCreationAttempt;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus;
import uk.gov.hmcts.reform.deedpoll.repository.CcdCaseProjection;
import uk.gov.hmcts.reform.deedpoll.repository.CcdCreationAttemptRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The handler's wiring of {@link CcdLinkRule} with the repositories mocked: that it locks before it reads, binds
 * only on {@code BIND}, and refuses as a submit error. {@code CcdPersistenceSubmissionTest} runs the same handler
 * through the SDK against real Postgres; this is its Docker-free companion.
 */
class LinkDeedPollHandlerTest {

    private static final String RECORD = "7d0b7a8e-0000-4000-8000-000000000001";
    private static final long REFERENCE = 1_234_567_890_123_456L;

    private DeedPollRepository deedPolls;
    private CcdCreationAttemptRepository attempts;
    private LinkDeedPollHandler handler;

    @BeforeEach
    void setUp() {
        deedPolls = mock(DeedPollRepository.class);
        attempts = mock(CcdCreationAttemptRepository.class);
        handler = new LinkDeedPollHandler(deedPolls, attempts);
        when(deedPolls.findCcdCaseProjection(anyString())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("locks the record, then binds the reference to it")
    void binds() {
        record(null, CcdCreationAttemptStatus.Claimed);
        when(deedPolls.bindCcdCaseReference(RECORD, "1234567890123456")).thenReturn(1);

        SubmitResponse<DeedPollCaseState> response = handler.submit(payload(REFERENCE, RECORD));

        assertThat(response.getErrors()).isNullOrEmpty();
        verify(deedPolls).findByIdForUpdate(RECORD);
        verify(deedPolls).bindCcdCaseReference(RECORD, "1234567890123456");
    }

    @Test
    @DisplayName("binds nothing again for a record already bound to this reference")
    void alreadyBoundBindsNothing() {
        record("1234567890123456", CcdCreationAttemptStatus.AwaitingConfirmation);

        assertThat(handler.submit(payload(REFERENCE, RECORD)).getErrors()).isNullOrEmpty();
        verify(deedPolls, never()).bindCcdCaseReference(anyString(), anyString());
    }

    @Test
    @DisplayName("refuses an unknown id as a submit error, binding nothing")
    void refusesAnUnknownRecord() {
        when(deedPolls.findByIdForUpdate(RECORD)).thenReturn(Optional.empty());

        assertThat(handler.submit(payload(REFERENCE, RECORD)).getErrors())
            .containsExactly(CcdLinkRule.Outcome.NO_SUCH_RECORD.refusal());
        verify(deedPolls, never()).bindCcdCaseReference(anyString(), anyString());
    }

    @Test
    @DisplayName("refuses a record whose attempt is only Pending: nothing claimed it for this operation")
    void refusesAPendingAttempt() {
        record(null, CcdCreationAttemptStatus.Pending);

        assertThat(handler.submit(payload(REFERENCE, RECORD)).getErrors())
            .containsExactly(CcdLinkRule.Outcome.NO_ATTEMPT_IN_PROGRESS.refusal());
        verify(deedPolls, never()).bindCcdCaseReference(anyString(), anyString());
    }

    @Test
    @DisplayName("refuses a reference another record holds")
    void refusesATakenReference() {
        record(null, CcdCreationAttemptStatus.Claimed);
        when(deedPolls.findCcdCaseProjection("1234567890123456"))
            .thenReturn(Optional.of(new CcdCaseProjection("someone-else", "KBDP2026-000002")));

        assertThat(handler.submit(payload(REFERENCE, RECORD)).getErrors())
            .containsExactly(CcdLinkRule.Outcome.REFERENCE_TAKEN.refusal());
        verify(deedPolls, never()).bindCcdCaseReference(anyString(), anyString());
    }

    @Test
    @DisplayName("takes no lock for a malformed reference or a payload with no id")
    void locksNothingItCannotBind() {
        assertThat(handler.submit(payload(234_567_890_123_456L, RECORD)).getErrors())
            .containsExactly(CcdLinkRule.Outcome.MALFORMED_REFERENCE.refusal());
        assertThat(handler.submit(payload(REFERENCE, " ")).getErrors())
            .containsExactly(CcdLinkRule.Outcome.NO_SUCH_RECORD.refusal());
        assertThat(handler.submit(new EventPayload<>(REFERENCE, null, new LinkedMultiValueMap<>())).getErrors())
            .containsExactly(CcdLinkRule.Outcome.NO_SUCH_RECORD.refusal());
        verify(deedPolls, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("throws rather than answering success if the conditional bind changes no row")
    void bindThatChangesNothingThrows() {
        record(null, CcdCreationAttemptStatus.Claimed);
        when(deedPolls.bindCcdCaseReference(RECORD, "1234567890123456")).thenReturn(0);

        assertThatThrownBy(() -> handler.submit(payload(REFERENCE, RECORD)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("joins the SDK's transaction and refuses to run without one")
    void requiresTheCallersTransaction() throws NoSuchMethodException {
        Transactional transactional = LinkDeedPollHandler.class.getMethod("submit", EventPayload.class)
            .getAnnotation(Transactional.class);

        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    private void record(String boundReference, CcdCreationAttemptStatus status) {
        DeedPoll deedPoll = mock(DeedPoll.class);
        when(deedPoll.getId()).thenReturn(RECORD);
        when(deedPoll.getCcdCaseReference()).thenReturn(boundReference);
        when(deedPolls.findByIdForUpdate(RECORD)).thenReturn(Optional.of(deedPoll));
        if (boundReference != null) {
            when(deedPolls.findCcdCaseProjection(boundReference))
                .thenReturn(Optional.of(new CcdCaseProjection(RECORD, "KBDP2026-000001")));
        }
        CcdCreationAttempt attempt = mock(CcdCreationAttempt.class);
        when(attempt.getStatus()).thenReturn(status);
        when(attempts.findLive(RECORD)).thenReturn(Optional.of(attempt));
    }

    private static EventPayload<DeedPollCaseData, DeedPollCaseState> payload(long reference, String deedPollId) {
        DeedPollCaseData data = new DeedPollCaseData();
        data.setDeedPollId(deedPollId);
        return new EventPayload<>(reference, data, new LinkedMultiValueMap<>());
    }
}
