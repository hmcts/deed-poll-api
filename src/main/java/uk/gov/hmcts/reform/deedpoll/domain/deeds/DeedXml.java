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
     * A case value as XML character data.
     *
     * <p>{@code &}, {@code <} and {@code >} because a parser must not read them as markup; {@code "} and
     * {@code '} are left alone, because nothing here writes an attribute value and escaping them would put
     * {@code &quot;} into the text a reader shows. A carriage return is escaped as a character reference,
     * because an XML parser normalises a literal one to a line feed and the value would come back changed.
     */
    static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '\r' -> out.append("&#13;");
                default -> out.append(character);
            }
        }
        return out.toString();
    }
}
