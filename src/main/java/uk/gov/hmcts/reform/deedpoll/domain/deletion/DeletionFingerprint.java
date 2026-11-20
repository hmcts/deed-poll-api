package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;

/**
 * What a deletion request's body was, as one value a replay can be compared with: the same key with the same
 * fingerprint is the same request, and the same key with another is a 409.
 *
 * <p>SHA-256 over a versioned, line-separated encoding of the reason, the confirmed {@code lastChangedOn} (to the
 * millisecond, as {@code timestamp(3)} keeps it) and the note last, so no field can run into the next: neither of the
 * first two can contain a line break. Lower-case hex, as {@code V48} checks it.
 */
public final class DeletionFingerprint {

    private static final String VERSION = "deletion-request-v1";

    private DeletionFingerprint() {
    }

    /** The fingerprint of this body. */
    public static String of(DeletionReason reason, DeletionNote note, LocalDateTime expectedLastChangedOn) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(note, "note");
        Objects.requireNonNull(expectedLastChangedOn, "expectedLastChangedOn");
        String encoded = VERSION + '\n' + reason.name() + '\n' + truncate(expectedLastChangedOn) + '\n' + note.value();
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(encoded.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", impossible);
        }
    }

    /**
     * The precision the request stores the confirmed {@code lastChangedOn} at, and so compares it at.
     */
    public static LocalDateTime truncate(LocalDateTime timestamp) {
        return timestamp.truncatedTo(ChronoUnit.MILLIS);
    }
}
