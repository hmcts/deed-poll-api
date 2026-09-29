package uk.gov.hmcts.reform.deedpoll.domain.documents;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers {@link DocumentOwner}, the record a document store is told a document belongs to.
 */
class DocumentOwnerTest {

    private static final String ID = "6f1c5f2e-0000-4000-8000-000000000001";

    @Test
    @DisplayName("carries a linked record's CCD case reference")
    void carriesALinkedRecordsCaseReference() {
        DeedPoll record = new DeedPoll();
        record.setId(ID);
        record.setCcdCaseReference("1234567890123456");

        assertThat(DocumentOwner.of(record))
            .isEqualTo(new DocumentOwner(ID, Optional.of("1234567890123456")));
    }

    @Test
    @DisplayName("carries no case reference for a record not yet linked")
    void carriesNoCaseReferenceForAnUnlinkedRecord() {
        DeedPoll record = new DeedPoll();
        record.setId(ID);

        assertThat(DocumentOwner.of(record).ccdCaseReference()).isEmpty();
    }

    @Test
    @DisplayName("refuses a missing id, and a null in place of an empty reference")
    void refusesNulls() {
        assertThatThrownBy(() -> new DocumentOwner(null, Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DocumentOwner(ID, null)).isInstanceOf(NullPointerException.class);
    }
}
