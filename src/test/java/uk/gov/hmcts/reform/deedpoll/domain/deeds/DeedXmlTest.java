package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Escaping, which both halves of the document share so that neither can have its own opinion about a name.
 */
class DeedXmlTest {

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
}
