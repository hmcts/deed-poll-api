package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The published address pattern and the check the service runs are one rule stated twice. This class holds them
 * together.
 *
 * <p>The pattern stays published because admin compiles it with JavaScript. The service checks without it because
 * its repeated groups recurse in Java's engine. A change to either that the other does not follow fails here, on
 * hand-picked boundaries and on a seeded random corpus built to land near the rule's edges.
 */
class GazetteSettingsServiceEmailTest {

    private static final Pattern PUBLISHED = Pattern.compile(GazetteSettingsService.EMAIL_PATTERN);

    private static final String LOCAL_CHARS = "aZ9!#$%&'*+/=?^_`{|}~-";
    private static final String LABEL_CHARS = "aZ9-";
    private static final String NOISE = "aZ9.-@ _+é\n";

    @ParameterizedTest
    @ValueSource(strings = {
        "gazette@example.com", "a@b.co", "first.last@sub.example.co.uk", "o'neil+tag@example.com",
        "x@a-b.example", "!#$%&'*+/=?^_`{|}~-@example.com", "a@1.2", "A@B.C"})
    @DisplayName("accepts the addresses the published pattern accepts")
    void acceptsValidAddresses(String address) {
        assertThat(PUBLISHED.matcher(address).matches()).isTrue();
        assertThat(GazetteSettingsService.isWellFormed(address)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "@", "a@", "@example.com", "a@example", "a..b@example.com", ".a@example.com", "a.@example.com",
        "a@-example.com", "a@example-.com", "a@example..com", "a@.example.com", "a@example.com.", "a@b@example.com",
        "a b@example.com", "a@exa mple.com", "Name <a@example.com>", "a@example.com,b@example.com", "é@example.com",
        "a@exämple.com", "a@example.com\n", "a@example_.com"})
    @DisplayName("refuses the addresses the published pattern refuses")
    void refusesInvalidAddresses(String address) {
        assertThat(PUBLISHED.matcher(address).matches()).isFalse();
        assertThat(GazetteSettingsService.isWellFormed(address)).isFalse();
    }

    @Test
    @DisplayName("agrees on the 63-character label limit")
    void agreesOnTheLabelLimit() {
        String longest = "a@" + "b".repeat(63) + ".com";
        String tooLong = "a@" + "b".repeat(64) + ".com";

        assertThat(GazetteSettingsService.isWellFormed(longest)).isEqualTo(PUBLISHED.matcher(longest).matches())
            .isTrue();
        assertThat(GazetteSettingsService.isWellFormed(tooLong)).isEqualTo(PUBLISHED.matcher(tooLong).matches())
            .isFalse();
    }

    @Test
    @DisplayName("agrees with the published pattern on 200,000 seeded near-miss addresses")
    void agreesOnARandomCorpus() {
        Random random = new Random(20_261_009L);
        List<String> disagreements = new ArrayList<>();
        int accepted = 0;
        for (int i = 0; i < 200_000; i++) {
            String address = nearMiss(random);
            boolean expected = PUBLISHED.matcher(address).matches();
            if (expected) {
                accepted++;
            }
            if (GazetteSettingsService.isWellFormed(address) != expected && disagreements.size() < 10) {
                disagreements.add(address);
            }
        }

        assertThat(disagreements).as("addresses the two rules decide differently").isEmpty();
        assertThat(accepted).as("the corpus must exercise acceptance as well as refusal").isBetween(5_000, 195_000);
    }

    /** A plausible address, then sometimes damaged by one random edit, so most cases sit on a rule's edge. */
    private static String nearMiss(Random random) {
        StringBuilder address = new StringBuilder(atoms(random, LOCAL_CHARS, 1 + random.nextInt(3)))
            .append('@').append(atoms(random, LABEL_CHARS, 1 + random.nextInt(3)));
        if (random.nextInt(3) > 0) {
            int at = random.nextInt(address.length() + 1);
            char noise = NOISE.charAt(random.nextInt(NOISE.length()));
            switch (random.nextInt(3)) {
                case 0 -> address.insert(at, noise);
                case 1 -> {
                    if (at < address.length()) {
                        address.deleteCharAt(at);
                    }
                }
                default -> {
                    if (at < address.length()) {
                        address.setCharAt(at, noise);
                    }
                }
            }
        }
        return address.toString();
    }

    private static String atoms(Random random, String alphabet, int count) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                joined.append('.');
            }
            int length = 1 + random.nextInt(4);
            for (int j = 0; j < length; j++) {
                joined.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
        }
        return joined.toString();
    }
}
