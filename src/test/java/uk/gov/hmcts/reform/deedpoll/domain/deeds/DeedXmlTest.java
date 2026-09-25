package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Escaping, which both halves of the document share so that neither can have its own opinion about a name.
 */
class DeedXmlTest {

    /**
     * U+FFFF, built rather than written: a raw one is invisible in an editor and an escape is refused by
     * {@code AvoidEscapedUnicodeCharacters}, which permits control characters and not these.
     */
    private static final String NOT_A_CHARACTER = Character.toString(Character.MAX_VALUE);

    /** Half of the emoji below -- a high surrogate with no low one after it, which XML cannot carry either. */
    private static final String LONE_SURROGATE = "😀".substring(0, 1);

    @Test
    @DisplayName("markup in a case value becomes character data rather than markup")
    void markupBecomesCharacterData() {
        assertThat(DeedXml.escape("British & <Commonwealth>"))
            .isEqualTo("British &amp; &lt;Commonwealth&gt;");
    }

    @Test
    @DisplayName("quotes are left alone, because nothing here writes an attribute value")
    void quotesAreLeftAlone() {
        // Escaping them would put &quot; into the text a reader shows.
        assertThat(DeedXml.escape("O'Brien \"Paddy\"")).isEqualTo("O'Brien \"Paddy\"");
    }

    @Test
    @DisplayName("a carriage return is escaped, because a parser would otherwise normalise it away")
    void carriageReturnIsEscaped() {
        assertThat(DeedXml.escape("Flat 4\r\n12 Gray's Inn Road"))
            .isEqualTo("Flat 4&#13;\n12 Gray's Inn Road");
    }

    @Test
    @DisplayName("ordinary text is unchanged, including the accents and hyphens a name carries")
    void ordinaryTextIsUnchanged() {
        assertThat(DeedXml.escape("Okonkwo-Hart")).isEqualTo("Okonkwo-Hart");
        assertThat(DeedXml.escape("Siân Ó Dónaill")).isEqualTo("Siân Ó Dónaill");
        assertThat(DeedXml.escape("")).isEmpty();
    }

    @Test
    @DisplayName("a character XML cannot carry becomes a space, because there is no escape for one")
    void illegalCharactersBecomeSpaces() {
        // U+000B is what Word writes for a soft line break, so this is the shape a pasted address arrives in.
        // &#11; would be as illegal as the literal, so the only answers are to drop it or replace it -- and
        // dropping would turn "Flat 4" and "12 Gray's Inn Road" into a house number that does not exist.
        assertThat(DeedXml.escape("Flat 4\u000B12 Gray's Inn Road")).isEqualTo("Flat 4 12 Gray's Inn Road");
        assertThat(DeedXml.escape("Ada\u0000Lovelace")).isEqualTo("Ada Lovelace");
        assertThat(DeedXml.escape("Ada\u001FLovelace")).isEqualTo("Ada Lovelace");
        // U+FFFF, the top of XML's second permitted range plus one -- the boundary legalInXml draws at FFFD.
        assertThat(DeedXml.escape("Ada" + NOT_A_CHARACTER + "Lovelace")).isEqualTo("Ada Lovelace");
    }

    @Test
    @DisplayName("tab and line feed survive, because XML carries both")
    void tabAndLineFeedSurvive() {
        assertThat(DeedXml.escape("Flat 4\n12 Gray's Inn Road")).isEqualTo("Flat 4\n12 Gray's Inn Road");
        assertThat(DeedXml.escape("Ada\tLovelace")).isEqualTo("Ada\tLovelace");
    }

    @Test
    @DisplayName("a supplementary character survives whole, and a half of one does not")
    void surrogatePairsSurviveAndLoneSurrogatesDoNot() {
        // Escaping by char rather than by code point would split the pair and write two lone surrogates, which
        // is the same corruption an unpaired one is.
        assertThat(DeedXml.escape("Ada 😀")).isEqualTo("Ada 😀");
        assertThat(DeedXml.escape("Ada " + LONE_SURROGATE + " Lovelace")).isEqualTo("Ada   Lovelace");
    }
}
