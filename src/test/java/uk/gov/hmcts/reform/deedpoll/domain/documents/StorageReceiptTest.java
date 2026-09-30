package uk.gov.hmcts.reform.deedpoll.domain.documents;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

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

    @Test
    @DisplayName("carries no hash token unless the store issued one")
    void carriesNoTokenByDefault() {
        assertThat(new StorageReceipt("a-key").hashToken()).isEmpty();
    }

    @Test
    @DisplayName("never prints the hash token, which is transient and must not reach a log line")
    void neverPrintsTheToken() {
        String token = "a".repeat(64);

        String printed = new StorageReceipt("a-key", Optional.of(token)).toString();

        assertThat(printed).doesNotContain(token).contains("a-key").contains("<redacted>");
        assertThat(new StorageReceipt("a-key")).hasToString("StorageReceipt[storageKey=a-key, hashToken=<none>]");
    }
}
