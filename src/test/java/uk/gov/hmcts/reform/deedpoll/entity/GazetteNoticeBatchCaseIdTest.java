package uk.gov.hmcts.reform.deedpoll.entity;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GazetteNoticeBatchCaseIdTest {

    @Test
    void identityIncludesBothBatchAndCase() {
        UUID batch = UUID.randomUUID();
        String deedPoll = UUID.randomUUID().toString();
        GazetteNoticeBatchCaseId id = new GazetteNoticeBatchCaseId(batch, deedPoll);
        GazetteNoticeBatchCaseId same = new GazetteNoticeBatchCaseId(batch, deedPoll);
        GazetteNoticeBatchCaseId otherBatch = new GazetteNoticeBatchCaseId(UUID.randomUUID(), deedPoll);
        GazetteNoticeBatchCaseId otherCase = new GazetteNoticeBatchCaseId(batch, UUID.randomUUID().toString());

        assertThat(id).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(id).isNotEqualTo(otherBatch).isNotEqualTo(otherCase).isNotEqualTo(null);
        // The two equals() branches the lines above cannot reach, written so Sonar does not read them as mistakes:
        // asserting a value equals itself (S5863) or equals a String (S5845) is what it flagged here.
        GazetteNoticeBatchCaseId sameReference = id;
        assertThat(id).isEqualTo(sameReference);
        assertThat(id.equals(new Object())).isFalse();
        assertThat(new HashSet<>(List.of(id, same, otherBatch, otherCase))).hasSize(3);
    }

    @Test
    void emptyJpaKeysCompareSafely() {
        GazetteNoticeBatchCaseId empty = new GazetteNoticeBatchCaseId();
        GazetteNoticeBatchCaseId alsoEmpty = new GazetteNoticeBatchCaseId();
        assertThat(empty).isEqualTo(alsoEmpty).hasSameHashCodeAs(alsoEmpty);
        alsoEmpty.setBatchId(UUID.randomUUID());
        assertThat(empty).isNotEqualTo(alsoEmpty);
        alsoEmpty.setBatchId(null);
        alsoEmpty.setDeedPollId(UUID.randomUUID().toString());
        assertThat(empty).isNotEqualTo(alsoEmpty);
    }
}
