package uk.gov.hmcts.reform.deedpoll.ccd;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkRule.Facts;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkRule.Outcome;
import uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.AwaitingConfirmation;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.Claimed;

/**
 * Plan 02's decision 10 with no database: when the {@code linkDeedPoll} submission binds, when it is a no-op, and
 * every way it is refused. The container-free companion of {@code CcdPersistenceSubmissionTest}, which Docker-free
 * CI does not run.
 */
class CcdLinkRuleTest {

    private static final String RECORD = "7d0b7a8e-0000-4000-8000-000000000001";
    private static final String OTHER_RECORD = "7d0b7a8e-0000-4000-8000-000000000002";
    private static final String REFERENCE = "1234567890123456";
    private static final String OTHER_REFERENCE = "6543210987654321";

    /** An unbound record whose attempt is claimed, and a reference nobody holds: the one case that binds. */
    private static Facts bindable() {
        return new Facts(Optional.of(REFERENCE), true, Optional.empty(), Optional.of(Claimed), Optional.empty());
    }

    @ParameterizedTest
    @EnumSource(value = CcdCreationAttemptStatus.class, names = {"Claimed", "AwaitingConfirmation"})
    @DisplayName("binds an unbound record whose attempt is claimed or awaiting confirmation")
    void binds(CcdCreationAttemptStatus status) {
        Facts facts = new Facts(Optional.of(REFERENCE), true, Optional.empty(), Optional.of(status), Optional.empty());

        assertThat(CcdLinkRule.decide(RECORD, facts)).isEqualTo(Outcome.BIND);
    }

    @Test
    @DisplayName("is a no-op for a record already bound to this reference")
    void alreadyBoundIsANoOp() {
        Facts facts = new Facts(Optional.of(REFERENCE), true, Optional.of(REFERENCE), Optional.of(AwaitingConfirmation),
            Optional.of(RECORD));

        assertThat(CcdLinkRule.decide(RECORD, facts)).isEqualTo(Outcome.ALREADY_BOUND);
        assertThat(Outcome.ALREADY_BOUND.refused()).isFalse();
    }

    @Test
    @DisplayName("refuses a reference that is not sixteen digits before looking at anything else")
    void refusesAMalformedReference() {
        Facts facts = new Facts(Optional.empty(), false, Optional.empty(), Optional.empty(), Optional.empty());

        assertThat(CcdLinkRule.decide(RECORD, facts)).isEqualTo(Outcome.MALFORMED_REFERENCE);
    }

    @Test
    @DisplayName("refuses an id no record has -- a forged UUID binds nothing")
    void refusesAnUnknownRecord() {
        Facts facts = new Facts(Optional.of(REFERENCE), false, Optional.empty(), Optional.empty(), Optional.empty());

        assertThat(CcdLinkRule.decide(RECORD, facts)).isEqualTo(Outcome.NO_SUCH_RECORD);
    }

    @ParameterizedTest
    @EnumSource(value = CcdCreationAttemptStatus.class, names = {"Pending", "Linked", "NeedsRepair"})
    @DisplayName("refuses a real record whose attempt is not expecting a submission -- a UUID is not permission")
    void refusesAnAttemptNotInProgress(CcdCreationAttemptStatus status) {
        Facts facts = new Facts(Optional.of(REFERENCE), true, Optional.empty(), Optional.of(status), Optional.empty());

        assertThat(CcdLinkRule.decide(RECORD, facts)).isEqualTo(Outcome.NO_ATTEMPT_IN_PROGRESS);
    }

    @Test
    @DisplayName("refuses a real record with no attempt at all")
    void refusesARecordWithNoAttempt() {
        Facts facts = new Facts(Optional.of(REFERENCE), true, Optional.empty(), Optional.empty(), Optional.empty());

        assertThat(CcdLinkRule.decide(RECORD, facts)).isEqualTo(Outcome.NO_ATTEMPT_IN_PROGRESS);
    }

    @Test
    @DisplayName("checks the attempt before the binding: a linked record with no attempt in progress is not a no-op")
    void theAttemptComesBeforeTheBinding() {
        Facts facts = new Facts(Optional.of(REFERENCE), true, Optional.of(REFERENCE), Optional.empty(),
            Optional.of(RECORD));

        assertThat(CcdLinkRule.decide(RECORD, facts)).isEqualTo(Outcome.NO_ATTEMPT_IN_PROGRESS);
    }

    @Test
    @DisplayName("refuses a record already bound to another reference -- never an overwrite")
    void refusesARecordLinkedElsewhere() {
        Facts facts = new Facts(Optional.of(REFERENCE), true, Optional.of(OTHER_REFERENCE), Optional.of(Claimed),
            Optional.empty());

        assertThat(CcdLinkRule.decide(RECORD, facts)).isEqualTo(Outcome.RECORD_LINKED_ELSEWHERE);
    }

    @Test
    @DisplayName("refuses a reference another record already holds")
    void refusesATakenReference() {
        Facts facts = new Facts(Optional.of(REFERENCE), true, Optional.empty(), Optional.of(Claimed),
            Optional.of(OTHER_RECORD));

        assertThat(CcdLinkRule.decide(RECORD, facts)).isEqualTo(Outcome.REFERENCE_TAKEN);
    }

    @Test
    @DisplayName("the one case that binds is the fixture every refusal above departs from")
    void theFixtureBinds() {
        assertThat(CcdLinkRule.decide(RECORD, bindable())).isEqualTo(Outcome.BIND);
    }

    @Test
    @DisplayName("every refusal is fixed prose, and the two outcomes that proceed carry none")
    void refusalsAreFixedProse() {
        assertThat(Arrays.stream(Outcome.values()).filter(Outcome::refused))
            .allSatisfy(outcome -> assertThat(outcome.refusal())
                .isNotBlank()
                .doesNotContain(RECORD)
                .doesNotContain(REFERENCE));
        assertThat(Outcome.BIND.refusal()).isNull();
        assertThat(Outcome.ALREADY_BOUND.refusal()).isNull();
    }
}
