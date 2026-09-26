package uk.gov.hmcts.reform.deedpoll.domain.deeds;

/**
 * Escaping, which is the generator's job on both halves of the document.
 *
 * <p>A case value reaches the output twice -- once in the bound data part and once as the cached run text
 * inside the content control -- and a value like {@code British & <Commonwealth>} would produce a package Word
 * refuses to open if either half wrote it raw. One helper rather than two, so the two halves cannot escape
 * differently and disagree about what the applicant is called.
 */
final class DeedXml {

    private DeedXml() {
    }

    /**
     * What an illegal character becomes, and why it is not simply dropped.
     *
     * <p>A space, because dropping would join two words the applicant wrote apart: Word writes {@code U+000B}
     * for a soft line break, so {@code "Flat 4\u000B12 Gray's Inn Road"} is the shape this arrives in, and
     * {@code "Flat 412 Gray's Inn Road"} is a wrong address that reads like a real one. A space is visible,
     * harmless in a name, and cannot merge two values.
     */
    private static final char REPLACEMENT = ' ';

    /**
     * A case value as XML character data.
     *
     * <p>{@code &}, {@code <} and {@code >} because a parser must not read them as markup; {@code "} and
     * {@code '} are left alone, because nothing here writes an attribute value and escaping them would put
     * {@code &quot;} into the text a reader shows. A carriage return is escaped as a character reference,
     * because an XML parser normalises a literal one to a line feed and the value would come back changed.
     *
     * <p><strong>A character XML 1.0 cannot carry at all is replaced rather than escaped</strong>, because
     * there is no escape for it: {@code &#11;} is as illegal as the literal, so a C0 control other than tab,
     * line feed and carriage return -- or an unpaired surrogate -- makes both written parts non-well-formed
     * and Word reports the {@code .docx} as corrupt. Nothing upstream filters them: {@code DeedPollSchemas}
     * validates these columns by length alone, so a paste out of Word or a Dataverse migration can put one in
     * a name, and this generator never parses its own output back -- so the deed would be stored, its id
     * frozen by {@code DeedDocumentService}, and emailed, with no check anywhere able to see it. The
     * silent-failure argument the package is built on says the character cannot be allowed through.
     */
    static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            // By code point, so a valid surrogate pair survives and a lone surrogate -- which codePointAt
            // answers as itself, one char wide -- is caught by legalInXml below.
            int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);
            switch (codePoint) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '\r' -> out.append("&#13;");
                default -> out.appendCodePoint(legalInXml(codePoint) ? codePoint : REPLACEMENT);
            }
        }
        return out.toString();
    }

    /**
     * Whether XML 1.0 permits this code point at all -- its {@code Char} production, minus the carriage return
     * the caller has already handled.
     */
    private static boolean legalInXml(int codePoint) {
        return codePoint == '\t'
            || codePoint == '\n'
            || (codePoint >= 0x20 && codePoint <= 0xD7FF)
            || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
            || codePoint >= 0x10000;
    }
}
