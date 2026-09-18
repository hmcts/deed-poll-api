package uk.gov.hmcts.reform.deedpoll.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule that decides whether an applicant eventually hears from the court.
 *
 * <p>Container-free on purpose. CI has no Docker daemon and excludes every container-backed class, so a rule
 * stated only in one of those is a rule CI does not check -- and this one is worth checking on every commit:
 * a status misfiled as permanent is an applicant who is never told, and one misfiled as temporary is a retry
 * loop against a request Notify refuses every time.
 *
 * <p>These assertions are the mapping restated, which is the point: the mapping is inherited from {@code pcs-api}
 * rather than read from Notify's documentation (the plan's flag 2), so it is written down twice deliberately --
 * changing it should mean changing a test that says what the old behaviour was.
 */
class NotificationFailureTest {

    @ParameterizedTest
    @ValueSource(ints = {400, 403})
    @DisplayName("400 and 403 are permanent, because resending sends the same refused request")
    void badRequestAndForbiddenArePermanent(int status) {
        assertThat(NotificationFailure.classify(status)).isEqualTo(NotificationFailure.PERMANENT);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500})
    @DisplayName("429 and 500 are temporary, because the same request would have worked at another moment")
    void rateLimitedAndServerErrorAreTemporary(int status) {
        assertThat(NotificationFailure.classify(status)).isEqualTo(NotificationFailure.TEMPORARY);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 200, 401, 404, 409, 418, 502, 503})
    @DisplayName("anything else is technical, because nobody has read what it means")
    void everythingElseIsTechnical(int status) {
        // 0 is the client's own value for a call that never reached Notify -- a DNS failure, a reset, a read
        // timeout. It falls here rather than being mapped, because the request may have been delivered and
        // there is no delivery query in this plan to ask with. A failed row for an email that did go out is
        // recoverable; a second rejection letter to an applicant is not.
        // 401 and 404 are here rather than beside 403: Notify's client documents 403 for authentication, so
        // anything else in that family is a status whose meaning has been assumed rather than read.
        assertThat(NotificationFailure.classify(status)).isEqualTo(NotificationFailure.TECHNICAL);
    }

    @Test
    @DisplayName("only the temporary verdict is retryable, so the outbox retries nothing else")
    void onlyTemporaryIsRetryable() {
        assertThat(NotificationFailure.TEMPORARY.retryable()).isTrue();
        assertThat(NotificationFailure.PERMANENT.retryable()).isFalse();
        assertThat(NotificationFailure.TECHNICAL.retryable()).isFalse();
    }

    @Test
    @DisplayName("every verdict is either retried or terminal -- there is no fourth outcome to forget")
    void everyVerdictIsAccountedFor() {
        // A member added to the enum without a decision about retrying it would arrive in the outbox as
        // "not retryable" by default. This does not stop that, but it fails if the set changes at all, which
        // is enough to make the decision deliberate.
        assertThat(NotificationFailure.values())
            .containsExactly(NotificationFailure.PERMANENT, NotificationFailure.TEMPORARY,
                NotificationFailure.TECHNICAL);
    }
}
