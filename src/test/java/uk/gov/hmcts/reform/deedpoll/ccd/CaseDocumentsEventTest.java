package uk.gov.hmcts.reform.deedpoll.ccd;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.ccd.client.model.CaseDataContent;
import uk.gov.hmcts.reform.ccd.client.model.StartEventResponse;
import uk.gov.hmcts.reform.deedpoll.storage.DocumentStoreProperties;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingRuleTest.answered;

class CaseDocumentsEventTest {

    private static final String HASH = "a-hash-token-that-must-stay-secret";
    private static final UUID OLD = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID NEW = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final List<SupportingDocumentReference> DOCUMENTS = List.of(
        new SupportingDocumentReference("doc-old", OLD, "old.pdf", Optional.empty()),
        new SupportingDocumentReference("doc-new", NEW, "new.pdf", Optional.of(HASH)));

    private final CoreCaseDataApi ccd = mock(CoreCaseDataApi.class);
    private final SystemUserTokens systemUser = mock(SystemUserTokens.class);
    private final AuthTokenGenerator s2s = mock(AuthTokenGenerator.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AuthTokenGenerator> serviceTokens = mock(ObjectProvider.class);
    private CaseDocumentsEvent event;

    @BeforeEach
    void setUp() {
        when(serviceTokens.getObject()).thenReturn(s2s);
        when(s2s.generate()).thenReturn("Bearer s2s");
        when(systemUser.accessToken()).thenReturn("Bearer system");
        event = new CaseDocumentsEvent(ccd, systemUser, serviceTokens,
            new DocumentStoreProperties("cdam", "http://dm-store:8080/"));
    }

    @Test
    void missingStartResponseOrTokenNeverSubmits() {
        for (StartEventResponse response : new StartEventResponse[] {
            null, StartEventResponse.builder().build(), StartEventResponse.builder().token(" ").build()
        }) {
            when(ccd.startEvent(anyString(), anyString(), anyString(), anyString())).thenReturn(response);
            assertThatThrownBy(() -> event.submit("123", DOCUMENTS))
                .isInstanceOf(CaseDocumentsEvent.NotSubmitted.class);
        }
        verify(ccd, never()).createEvent(anyString(), anyString(), anyString(), any());
    }

    @Test
    void localPayloadFailureNeverSubmits() {
        when(ccd.startEvent(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(StartEventResponse.builder().token("event-token").build());
        assertThatThrownBy(() -> event.submit("123", null)).isInstanceOf(CaseDocumentsEvent.NotSubmitted.class);
        verify(ccd, never()).createEvent(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("the event sets the whole collection, and only the document being attached carries its hash")
    @SuppressWarnings("unchecked")
    void content() {
        CaseDataContent content = event.content(DOCUMENTS, "event-token");

        assertThat(content.getEvent().getId()).isEqualTo(CaseDocumentsEvent.EVENT_ID);
        assertThat(content.getEvent().getSummary()).isEqualTo(CaseDocumentsEvent.EVENT_SUMMARY).doesNotContain(HASH);
        assertThat(content.getEventToken()).isEqualTo("event-token");
        List<Map<String, Object>> items =
            (List<Map<String, Object>>) ((Map<String, Object>) content.getData()).get(CaseDocumentsEvent.FIELD);
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("id", "doc-old");
        assertThat((Map<String, Object>) items.get(0).get("value"))
            .containsEntry("document_url", "http://dm-store:8080/documents/" + OLD)
            .containsEntry("document_binary_url", "http://dm-store:8080/documents/" + OLD + "/binary")
            .containsEntry("document_filename", "old.pdf")
            .doesNotContainKey("document_hash");
        assertThat((Map<String, Object>) items.get(1).get("value")).containsEntry("document_hash", HASH);
    }

    @Test
    @DisplayName("the event is started and submitted as the system user")
    void submits() {
        when(ccd.startEvent("Bearer system", "Bearer s2s", "123", CaseDocumentsEvent.EVENT_ID))
            .thenReturn(StartEventResponse.builder().token("event-token").build());

        event.submit("123", DOCUMENTS);

        ArgumentCaptor<CaseDataContent> sent = ArgumentCaptor.forClass(CaseDataContent.class);
        verify(ccd).createEvent(eq("Bearer system"), eq("Bearer s2s"), eq("123"), sent.capture());
        assertThat(sent.getValue().getEventToken()).isEqualTo("event-token");
    }

    @Test
    @DisplayName("a start that fails is NotSubmitted, in fixed prose, and nothing is submitted")
    void startFailureIsNotSubmitted() {
        when(ccd.startEvent(anyString(), anyString(), anyString(), anyString())).thenThrow(answered(404));

        assertThatThrownBy(() -> event.submit("123", DOCUMENTS))
            .isInstanceOf(CaseDocumentsEvent.NotSubmitted.class)
            .hasMessage("Not submitted: CCD answered HTTP 404")
            .hasNoCause();
        verify(ccd, never()).createEvent(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a budget spent by the token grants starts nothing; one spent by the start submits nothing")
    void budgetAskedBeforeEachCall() {
        when(ccd.startEvent(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(StartEventResponse.builder().token("event-token").build());

        assertThat(event.submit("123", DOCUMENTS, () -> Duration.ofMillis(-1))).isFalse();
        verify(ccd, never()).startEvent(anyString(), anyString(), anyString(), anyString());

        // Time for the first grant, none by the second: S2S is never waited for and nothing starts.
        Iterator<Duration> grants = List.of(Duration.ofSeconds(5), Duration.ofMillis(-1)).iterator();
        assertThat(event.submit("123", DOCUMENTS, grants::next)).isFalse();
        verify(s2s, never()).generate();
        verify(ccd, never()).startEvent(anyString(), anyString(), anyString(), anyString());

        // Time for both grants and the start, none for the submission.
        Iterator<Duration> answers = List.of(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5),
                                             Duration.ofMillis(-1)).iterator();
        assertThat(event.submit("123", DOCUMENTS, answers::next)).isFalse();
        verify(ccd).startEvent(anyString(), anyString(), anyString(), anyString());
        verify(ccd, never()).createEvent(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a token grant that hangs is waited for only as long as the budget allows, and starts nothing")
    void hungGrantIsBounded() {
        CountDownLatch release = new CountDownLatch(1);
        when(systemUser.accessToken()).thenAnswer(invocation -> {
            release.await(10, TimeUnit.SECONDS);
            return "Bearer system";
        });
        long deadline = System.nanoTime() + Duration.ofMillis(200).toNanos();
        long started = System.nanoTime();
        try {
            assertThat(event.submit("123", DOCUMENTS, () -> Duration.ofNanos(deadline - System.nanoTime())))
                .isFalse();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        } finally {
            release.countDown();
        }
        verify(ccd, never()).startEvent(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a token grant that fails within the budget is NotSubmitted, in fixed prose")
    void failedGrantWithinBudget() {
        when(s2s.generate()).thenThrow(new IllegalStateException("s2s down"));

        assertThatThrownBy(() -> event.submit("123", DOCUMENTS, () -> Duration.ofSeconds(5)))
            .isInstanceOf(CaseDocumentsEvent.NotSubmitted.class)
            .hasNoCause();
        verify(ccd, never()).startEvent(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a submission failure escapes as it was, for the caller to classify")
    void submissionFailureEscapes() {
        when(ccd.startEvent(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(StartEventResponse.builder().token("event-token").build());
        when(ccd.createEvent(anyString(), anyString(), anyString(), any())).thenThrow(answered(422));

        assertThatThrownBy(() -> event.submit("123", DOCUMENTS)).isInstanceOf(feign.FeignException.class);
    }

    @Test
    @DisplayName("a reference never prints its hash token")
    void referenceRedactsTheToken() {
        assertThat(DOCUMENTS.get(1).toString()).doesNotContain(HASH).contains("hashToken=<redacted>");
        assertThat(DOCUMENTS.get(0).toString()).contains("hashToken=<none>");
    }
}
