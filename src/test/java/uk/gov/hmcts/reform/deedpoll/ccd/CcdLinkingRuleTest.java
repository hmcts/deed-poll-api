package uk.gov.hmcts.reform.deedpoll.ccd;

import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingRule.CaseSeen;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingRule.Confirmation;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingRule.Next;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingRule.SubmitFailure;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 02, Task 7's recovery rule with no database and no network: which failures may have created a case, when a
 * case is confirmed, and what reconciliation does with each answer. {@code CcdLinkingScenariosTest} runs it against
 * a fake data-store and real Postgres; this is the part Docker-free CI runs.
 */
class CcdLinkingRuleTest {

    private static final String RECORD = "7d0b7a8e-0000-4000-8000-000000000001";
    private static final String CASE_TYPE = "DEED_POLL_PLACEHOLDER";
    private static final String REFERENCE = "1234567890123456";

    static FeignException answered(int status) {
        Request request = Request.create(Request.HttpMethod.POST, "http://ccd/case-types/X/cases", Map.of(), null,
            StandardCharsets.UTF_8, null);
        return FeignException.errorStatus("submitCaseCreation", feign.Response.builder()
            .status(status).reason("r").request(request).headers(Map.of())
            .body("{\"token\": \"a secret the detail must never quote\"}", StandardCharsets.UTF_8).build());
    }

    static RetryableException noResponse() {
        Request request = Request.create(Request.HttpMethod.POST, "http://ccd/case-types/X/cases", Map.of(), null,
            StandardCharsets.UTF_8, null);
        return new RetryableException(-1, "Read timed out", Request.HttpMethod.POST, (Long) null, request);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 409, 422})
    @DisplayName("a 4xx CCD sent is a refusal: nothing was created")
    void refusals(int status) {
        assertThat(CcdLinkingRule.classify(answered(status))).isEqualTo(SubmitFailure.REFUSED);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 408, 500, 502, 503, 504})
    @DisplayName("a 404, a 408 or a 5xx after submit may have followed a commit: the outcome is unknown")
    void unknownStatuses(int status) {
        assertThat(CcdLinkingRule.classify(answered(status))).isEqualTo(SubmitFailure.UNKNOWN);
    }

    @Test
    @DisplayName("no response at all, or a failure that is not an answer, is unknown")
    void noAnswerIsUnknown() {
        assertThat(CcdLinkingRule.classify(noResponse())).isEqualTo(SubmitFailure.UNKNOWN);
        assertThat(CcdLinkingRule.classify(new IllegalStateException("decode failed")))
            .isEqualTo(SubmitFailure.UNKNOWN);
    }

    @Test
    @DisplayName("confirms only when reference, case type and record id all match the binding")
    void confirms() {
        Optional<String> binding = Optional.of(REFERENCE);
        assertThat(CcdLinkingRule.confirm(RECORD, CASE_TYPE, binding,
            new CaseSeen(Optional.of(REFERENCE), CASE_TYPE, RECORD))).isEqualTo(Confirmation.CONFIRMED);

        assertThat(CcdLinkingRule.confirm(RECORD, CASE_TYPE, binding,
            new CaseSeen(Optional.of("6543210987654321"), CASE_TYPE, RECORD))).isEqualTo(Confirmation.CONTRADICTED);
        assertThat(CcdLinkingRule.confirm(RECORD, CASE_TYPE, binding,
            new CaseSeen(Optional.of(REFERENCE), "OTHER", RECORD))).isEqualTo(Confirmation.CONTRADICTED);
        assertThat(CcdLinkingRule.confirm(RECORD, CASE_TYPE, binding,
            new CaseSeen(Optional.of(REFERENCE), CASE_TYPE, "another-record"))).isEqualTo(Confirmation.CONTRADICTED);
        assertThat(CcdLinkingRule.confirm(RECORD, CASE_TYPE, binding,
            new CaseSeen(Optional.empty(), CASE_TYPE, RECORD))).isEqualTo(Confirmation.CONTRADICTED);
    }

    @Test
    @DisplayName("a record with no binding has no case of ours, whatever CCD showed")
    void unboundIsNotBound() {
        assertThat(CcdLinkingRule.confirm(RECORD, CASE_TYPE, Optional.empty(),
            new CaseSeen(Optional.of(REFERENCE), CASE_TYPE, RECORD))).isEqualTo(Confirmation.NOT_BOUND);
    }

    @Test
    @DisplayName("nothing created goes round again until the attempts are used, then is a repair item")
    void afterNothingCreated() {
        assertThat(CcdLinkingRule.mayTryAgain(1, 3)).isTrue();
        assertThat(CcdLinkingRule.mayTryAgain(2, 3)).isTrue();
        assertThat(CcdLinkingRule.mayTryAgain(3, 3)).isFalse();
        assertThat(CcdLinkingRule.mayTryAgain(1, 1)).isFalse();
    }

    @Test
    @DisplayName("reconciliation links on confirmation, repairs a contradiction at once, and otherwise waits")
    void reconciles() {
        for (boolean settled : new boolean[] {false, true}) {
            assertThat(CcdLinkingRule.reconcile(Optional.of(Confirmation.CONFIRMED), settled)).isEqualTo(Next.LINK);
            assertThat(CcdLinkingRule.reconcile(Optional.of(Confirmation.CONTRADICTED), settled))
                .isEqualTo(Next.REPAIR);
        }
        assertThat(CcdLinkingRule.reconcile(Optional.of(Confirmation.NOT_BOUND), false)).isEqualTo(Next.WAIT);
        assertThat(CcdLinkingRule.reconcile(Optional.empty(), false)).isEqualTo(Next.WAIT);
        assertThat(CcdLinkingRule.reconcile(Optional.of(Confirmation.NOT_BOUND), true)).isEqualTo(Next.REPAIR);
        assertThat(CcdLinkingRule.reconcile(Optional.empty(), true)).isEqualTo(Next.REPAIR);
    }
}
