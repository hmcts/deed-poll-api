package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading a template's content controls, and refusing one whose shape the generator cannot honour.
 *
 * <p><strong>Every test here is about a failure that would otherwise be silent.</strong> The vendored templates
 * are correct, so the only way to show these guards fire is to hand the reader a document that breaks one --
 * which is what the small synthetic parts below are for. A re-authored template that nested a control, split a
 * value across two runs or pointed a binding somewhere else would produce a deed printing placeholders or
 * blanks, with nothing anywhere saying so.
 */
class DeedControlsTest {

    private static final String CRM = DeedDocuments.CRM;
    private static final String STORE = DeedDocuments.STORE;

    @Test
    @DisplayName("a control's field, data store and data-part shape are read from the binding itself")
    void controlsFieldAndSchemaAreReadFromTheBinding() {
        DeedControls.Parsed parsed = DeedControls.read(
            DeedDocuments.document(DeedDocuments.control("deedpoll_newforename", STORE, "cached")));

        assertThat(parsed.controls()).hasSize(1);
        assertThat(parsed.fields()).containsExactly(DeedField.NEW_FORENAME);
        assertThat(parsed.storeItemId()).isEqualTo(STORE);
        assertThat(parsed.schema()).isEqualTo(new DeedSchema(CRM, "DocumentTemplate", "deedpoll_deedpoll"));
    }

    @Test
    @DisplayName("filling replaces the cached text and nothing else, escaping the value as it goes")
    void fillingReplacesTheCachedTextAndNothingElse() {
        String xml = DeedDocuments.document(
            DeedDocuments.control("deedpoll_newforename", STORE, "deedpoll_newforename"));
        DeedControls.Parsed parsed = DeedControls.read(xml);

        String filled = DeedControls.fill(xml, parsed, Map.of(DeedField.NEW_FORENAME, "Robin & Ash"));

        assertThat(filled).isEqualTo(xml.replace(">deedpoll_newforename<", ">Robin &amp; Ash<"));
    }

    @Test
    @DisplayName("a field bound by several controls gets the same value in every one of them")
    void fieldBoundBySeveralControlsGetsTheSameValue() {
        String xml = DeedDocuments.document(
            DeedDocuments.control("deedpoll_newforename", STORE, "one"),
            DeedDocuments.control("deedpoll_newsurname", STORE, "two"),
            DeedDocuments.control("deedpoll_newforename", STORE, "three"));
        DeedControls.Parsed parsed = DeedControls.read(xml);

        String filled = DeedControls.fill(xml, parsed,
            Map.of(DeedField.NEW_FORENAME, "Robin", DeedField.NEW_SURNAME, "Okonkwo-Hart"));

        assertThat(filled).contains(">Robin<", ">Okonkwo-Hart<");
        assertThat(filled).doesNotContain(">one<", ">two<", ">three<");
        assertThat(filled.split(">Robin<", -1)).hasSize(3);
    }

    @Test
    @DisplayName("a control with no binding is left alone rather than refused")
    void controlWithNoBindingIsLeftAlone() {
        // Nothing to fill, so nothing to complain about: a plain content control is not a mapping this
        // generator has an opinion about.
        String xml = DeedDocuments.document(
            "<w:sdt><w:sdtPr><w:id w:val=\"1\"/></w:sdtPr><w:sdtContent><w:p><w:r><w:t>plain</w:t>"
                + "</w:r></w:p></w:sdtContent></w:sdt>",
            DeedDocuments.control("deedpoll_newforename", STORE, "cached"));

        DeedControls.Parsed parsed = DeedControls.read(xml);

        assertThat(parsed.controls()).hasSize(1);
        assertThat(DeedControls.fill(xml, parsed, Map.of(DeedField.NEW_FORENAME, "Robin"))).contains(">plain<");
    }

    @Test
    @DisplayName("a nested control is refused, because the splice would land in the wrong one")
    void nestedControlIsRefused() {
        String inner = DeedDocuments.control("deedpoll_newforename", STORE, "inner");
        String xml = DeedDocuments.document("<w:sdt><w:sdtPr><w:id w:val=\"1\"/></w:sdtPr><w:sdtContent>" + inner
            + "</w:sdtContent></w:sdt>");

        assertThatThrownBy(() -> DeedControls.read(xml))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("contains another w:sdt");
    }

    @Test
    @DisplayName("a control holding two text runs is refused rather than half filled")
    void controlHoldingTwoTextRunsIsRefused() {
        String xml = DeedDocuments.document(
            DeedDocuments.control("deedpoll_newforename", STORE, "one</w:t></w:r><w:r><w:t>two"));

        assertThatThrownBy(() -> DeedControls.read(xml))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("holds 2 w:t elements");
    }

    @Test
    @DisplayName("two controls naming different data stores are refused")
    void twoControlsNamingDifferentDataStoresAreRefused() {
        String xml = DeedDocuments.document(
            DeedDocuments.control("deedpoll_newforename", STORE, "one"),
            DeedDocuments.control("deedpoll_newsurname", "{97E651D3-4026-41A5-9FD2-30805E1EDFD3}", "two"));

        assertThatThrownBy(() -> DeedControls.read(xml))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("name different data stores");
    }

    @Test
    @DisplayName("two controls disagreeing about the data part's namespace are refused")
    void twoControlsDisagreeingAboutTheNamespaceAreRefused() {
        String xml = DeedDocuments.document(
            DeedDocuments.control("deedpoll_newforename", STORE, "one"),
            DeedDocuments.control("deedpoll_newsurname", STORE, "two")
                .replace(CRM, "urn:microsoft-crm/document-template/deedpoll_deedpoll/99999/"));

        assertThatThrownBy(() -> DeedControls.read(xml))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("disagree about the data part");
    }

    @Test
    @DisplayName("a binding naming a field no mapping knows is refused rather than left blank")
    void bindingNamingAnUnknownFieldIsRefused() {
        String xml = DeedDocuments.document(DeedDocuments.control("deedpoll_somethingnew", STORE, "cached"));

        assertThatThrownBy(() -> DeedControls.read(xml))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("which DeedField does not know");
    }

    @Test
    @DisplayName("an XPath of a shape this generator does not write is refused")
    void anXpathOfAnUnexpectedShapeIsRefused() {
        String xml = DeedDocuments.document(DeedDocuments.control("deedpoll_newforename", STORE, "cached")
            .replace("/ns0:DocumentTemplate[1]/deedpoll_deedpoll[1]/deedpoll_newforename[1]",
                "/ns0:DocumentTemplate/deedpoll_newforename"));

        assertThatThrownBy(() -> DeedControls.read(xml))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("is not the shape this generator writes");
    }

    @Test
    @DisplayName("a binding whose prefix is unmapped is refused, because the namespace would be a guess")
    void bindingWithAnUnmappedPrefixIsRefused() {
        String xml = DeedDocuments.document(DeedDocuments.control("deedpoll_newforename", STORE, "cached")
            .replace("xmlns:ns0=", "xmlns:other="));

        assertThatThrownBy(() -> DeedControls.read(xml))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("is not mapped by");
    }

    @Test
    @DisplayName("a document with no bound control at all is refused")
    void documentWithNoBoundControlIsRefused() {
        assertThatThrownBy(() -> DeedControls.read(DeedDocuments.document()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no bound content controls");
    }
}
