package uk.gov.hmcts.reform.deedpoll.domain.documents;

import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/** Interim content checks shared by upload and staging; not antivirus or a complete PDF parser. */
public final class UploadContentPolicy {
    public static final String MISMATCH = "The file's contents don't match a PDF, image or Word document";
    public static final String ENCRYPTED = "Upload a file that is not encrypted or password-protected";
    public static final String WORD_FORMAT = "Upload an unencrypted Word document saved in .docx format";
    public static final String ACTIVE_PDF = "Upload a PDF without scripts, embedded files or active content";
    public static final String UNSAFE_WORD = "Upload a DOCX without macros, embedded objects or external templates";
    private static final Logger LOG = LoggerFactory.getLogger(UploadContentPolicy.class);
    private static final Set<String> ACTIVE_NAMES = Set.of("JavaScript", "JS", "Launch", "EmbeddedFile",
        "RichMedia", "XFA");

    private UploadContentPolicy() {
    }

    public static void verify(UploadedFile file) {
        byte[] bytes = file.content();
        if (bytes == null || bytes.length == 0 || bytes.length > UploadPolicy.MAX_UPLOAD_BYTES
            || !UploadPolicy.isAllowedUploadType(file.mimeType())) {
            refuse("unknown", MISMATCH);
        }
        switch (file.mimeType()) {
            case "application/pdf" -> {
                requirePrefix(bytes, "pdf", 37, 80, 68, 70, 45);
                verifyPdf(bytes);
            }
            case "image/jpeg" -> requirePrefix(bytes, "jpeg", 255, 216, 255);
            case "image/png" -> requirePrefix(bytes, "png", 137, 80, 78, 71, 13, 10, 26, 10);
            case "image/tiff" -> {
                if (!startsWith(bytes, 73, 73, 42, 0) && !startsWith(bytes, 77, 77, 0, 42)) {
                    refuse("tiff", MISMATCH);
                }
            }
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> verifyWord(bytes);
            default -> refuse("unknown", MISMATCH);
        }
    }

    private static void verifyWord(byte[] bytes) {
        if (startsWith(bytes, 208, 207, 17, 224, 161, 177, 26, 225)) {
            // OLE also identifies legacy .doc: the signature alone cannot establish encryption.
            refuse("ole", WORD_FORMAT);
        }
        requirePrefix(bytes, "docx", 80, 75, 3, 4);
        try {
            DocxContentPolicy.verify(bytes);
        } catch (DocxContentPolicy.UnsafeContent rejected) {
            refuse("docx", UNSAFE_WORD);
        } catch (IOException | InvalidFormatException | RuntimeException rejected) {
            // Parser diagnostics can contain attacker-controlled package names. Do not log the exception.
            refuse("docx", MISMATCH);
        }
    }

    private static void verifyPdf(byte[] bytes) {
        // A tripwire only: compressed object streams can hide names. Decode PDF name escapes, but do not
        // decompress or execute anything. Bounded token storage avoids copying the whole upload into a String.
        byte[] name = new byte[32];
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] != '/') {
                continue;
            }
            int length = 0;
            while (++index < bytes.length && !pdfDelimiter(bytes[index] & 255)) {
                int value = bytes[index] & 255;
                if (value == '#' && index + 2 < bytes.length) {
                    int high = Character.digit((char) bytes[index + 1], 16);
                    int low = Character.digit((char) bytes[index + 2], 16);
                    if (high >= 0 && low >= 0) {
                        value = high * 16 + low;
                        index += 2;
                    }
                }
                if (length < name.length) {
                    name[length] = (byte) value;
                }
                length++;
            }
            index--;
            if (length <= name.length) {
                String token = new String(name, 0, length, StandardCharsets.US_ASCII);
                if ("Encrypt".equals(token)) {
                    refuse("pdf", ENCRYPTED);
                }
                if (ACTIVE_NAMES.contains(token)) {
                    refuse("pdf", ACTIVE_PDF);
                }
            }
        }
    }

    private static boolean pdfDelimiter(int value) {
        return value == 0 || value == 9 || value == 10 || value == 12 || value == 13 || value == 32
            || "()<>[]{}/%".indexOf(value) >= 0;
    }

    private static void requirePrefix(byte[] bytes, String kind, int... prefix) {
        if (!startsWith(bytes, prefix)) {
            refuse(kind, MISMATCH);
        }
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if ((bytes[index] & 255) != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static void refuse(String kind, String message) {
        LOG.warn("Document content refused kind={}", kind);
        throw new InvalidInputError(message, List.of(new FieldError("file", message)));
    }
}
