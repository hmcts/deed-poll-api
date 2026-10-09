package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** A comparison token, not a credential. Includes expiry, all mapped values and readiness in a versioned digest. */
public final class GazetteConfirmation {
    private GazetteConfirmation() {
    }

    public static String issue(List<GazetteInput> inputs, LocalDateTime expiresAt) {
        String prefix = "v1." + expiresAt.toEpochSecond(ZoneOffset.UTC) + ".";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, prefix);
            for (GazetteInput input : inputs.stream().sorted(Comparator.comparing(GazetteInput::caseId)).toList()) {
                add(digest, input.caseId());
                add(digest, input.kind());
                add(digest, input.state());
                add(digest, Boolean.toString(input.publish()));
                add(digest, input.noticeSent());
                for (Object value : input.values()) {
                    add(digest, value);
                }
            }
            return prefix + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static void verify(String token, List<GazetteInput> inputs) {
        LocalDateTime expires = expiry(token);
        if (!issue(inputs, expires).equals(token)) {
            throw new GazetteRefusal(GazetteRefusal.Code.SELECTION_CHANGED);
        }
    }

    public static LocalDateTime expiry(String token) {
        if (token == null || !token.matches("v1\\.[0-9]{1,12}\\.[0-9a-f]{64}")) {
            throw new InvalidInputError("Invalid Gazette confirmation token.");
        }
        long seconds = Long.parseLong(token.split("\\.")[1]);
        LocalDateTime expires = LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC);
        LocalDateTime now = AuditClock.now();
        if (!expires.isAfter(now)) {
            throw new GazetteRefusal(GazetteRefusal.Code.CONFIRMATION_EXPIRED);
        }
        if (expires.isAfter(now.plusMinutes(30))) {
            throw new InvalidInputError("Invalid Gazette confirmation lifetime.");
        }
        return expires;
    }

    private static void add(MessageDigest digest, Object value) {
        String text = value == null ? "null" : value.getClass().getSimpleName() + ":" + value;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
