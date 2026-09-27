package uk.gov.hmcts.reform.deedpoll.ccd;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.CaseViewRequest;
import uk.gov.hmcts.reform.deedpoll.repository.CcdCaseProjection;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The view composes the case's two fields from the record the reference is bound to, and from nothing else.
 */
class DeedPollCaseViewTest {

    private static final long REFERENCE = 1_234_567_890_123_456L;

    private final DeedPollRepository deedPolls = mock(DeedPollRepository.class);
    private final DeedPollCaseView view =
        new DeedPollCaseView(deedPolls, new CcdProperties("A_CASE_TYPE", "A_JURISDICTION", "a-role"));

    @Test
    @DisplayName("answers for the configured case type only")
    void namesTheConfiguredCaseType() {
        assertThat(view.caseTypeIds()).containsExactly("A_CASE_TYPE");
    }

    @Test
    @DisplayName("composes the UUID and the display reference from the bound record")
    void composesFromTheRecord() {
        when(deedPolls.findCcdCaseProjection("1234567890123456"))
            .thenReturn(Optional.of(new CcdCaseProjection("7d0b7a8e-0000-4000-8000-000000000001", "KBDP2026-000001")));

        DeedPollCaseData data = view.getCase(new CaseViewRequest<>(REFERENCE, DeedPollCaseState.Registered));

        assertThat(data.getDeedPollId()).isEqualTo("7d0b7a8e-0000-4000-8000-000000000001");
        assertThat(data.getDeedPollReference()).isEqualTo("KBDP2026-000001");
    }

    @Test
    @DisplayName("refuses a case no record is bound to, rather than answering with empty data")
    void refusesAnUnboundCase() {
        when(deedPolls.findCcdCaseProjection("1234567890123456")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> view.getCase(new CaseViewRequest<>(REFERENCE, DeedPollCaseState.Registered)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining("1234567890123456");
    }
}
