package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;

import java.util.Locale;
import java.util.regex.Pattern;

/** Preserves literal OOXML escape-like text and characters that XML cannot carry directly. */
final class GazetteText {

    private static final Pattern ESCAPE = Pattern.compile("_(?=x[0-9a-fA-F]{4}_)");

    private GazetteText() {
    }

    static String encode(String text) {
        // Excel decodes _x0041_ as A, even in a string cell. Escape its leading underscore first.
        String literal = ESCAPE.matcher(text).replaceAll("_x005F_");
        StringBuilder result = new StringBuilder(literal.length());
        for (int index = 0; index < literal.length(); index++) {
            char character = literal.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= literal.length() || !Character.isLowSurrogate(literal.charAt(index + 1))) {
                    throw invalidUnicode();
                }
                result.append(character).append(literal.charAt(++index));
            } else if (Character.isLowSurrogate(character)) {
                throw invalidUnicode();
            } else if ((character < 0x20 && character != '\t' && character != '\n' && character != '\r')
                || character == 0xFFFE || character == 0xFFFF) {
                result.append(String.format(Locale.ROOT, "_x%04X_", (int) character));
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }

    private static ConflictError invalidUnicode() {
        return new ConflictError("Correct invalid text characters before Gazette publication.");
    }
}
