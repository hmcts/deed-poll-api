package uk.gov.hmcts.reform.deedpoll.domain.documents;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers {@link StorageReceipt}, what a store answers a save with.
 */
class StorageReceiptTest {

    @Test
    @DisplayName("carries the storage key the row records")
    void carriesTheStorageKey() {
        assertThat(new StorageReceipt("a-key").storageKey()).isEqualTo("a-key");
    }

    @Test
    @DisplayName("refuses to answer a save with no key")
    void refusesNoKey() {
        assertThatThrownBy(() -> new StorageReceipt(null)).isInstanceOf(NullPointerException.class);
    }
}
