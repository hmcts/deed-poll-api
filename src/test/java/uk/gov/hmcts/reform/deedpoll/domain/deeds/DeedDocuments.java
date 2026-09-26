package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small synthetic Word parts, for showing that the generator's checks fire.
 *
 * <p>The vendored templates are correct, so they cannot demonstrate a refusal. These builders produce the
 * smallest document with a given fault in it -- a nested control, a binding pointed at another data store, a
 * field the deed is not supposed to carry -- so that each guard can be shown to fail loudly rather than produce
 * a deed printing blanks.
 */
final class DeedDocuments {

    /** The namespace both templates' bindings map, and therefore the one a synthetic part must use. */
    static final String CRM = "urn:microsoft-crm/document-template/deedpoll_deedpoll/10740/";

    /** The adult template's data store, used wherever the GUID itself is not the point of the test. */
    static final String STORE = "{C9ADC254-2098-471F-A6B0-379E83CFF8E7}";

    private DeedDocuments() {
    }

    /**
     * A {@code word/document.xml} holding the given content controls and nothing else.
     */
    static String document(String... controls) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
            + " xmlns:w15=\"http://schemas.microsoft.com/office/word/2012/wordml\"><w:body>"
            + String.join("", controls)
            + "</w:body></w:document>";
    }

    /** One content control bound to a Dataverse element, with the given cached text. */
    static String control(String element, String storeItemId, String cachedText) {
        return "<w:sdt><w:sdtPr><w:id w:val=\"1\"/>"
            + "<w15:dataBinding w:prefixMappings=\"xmlns:ns0='" + CRM + "' \""
            + " w:xpath=\"/ns0:DocumentTemplate[1]/deedpoll_deedpoll[1]/" + element + "[1]\""
            + " w:storeItemID=\"" + storeItemId + "\"/></w:sdtPr>"
            + "<w:sdtContent><w:p><w:r><w:t>" + cachedText + "</w:t></w:r></w:p></w:sdtContent></w:sdt>";
    }

    /** One control per element named, all bound to the same data store. */
    static String documentBinding(String storeItemId, List<String> elements) {
        return document(elements.stream().map(element -> control(element, storeItemId, element))
            .toArray(String[]::new));
    }

    /** A package with just the two parts the generator reads, which is all any of these tests needs. */
    static Map<String, byte[]> packageOf(String documentXml, String storeItemId) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("word/document.xml", documentXml.getBytes(StandardCharsets.UTF_8));
        entries.put("customXml/itemProps1.xml", itemProps(storeItemId).getBytes(StandardCharsets.UTF_8));
        entries.put("customXml/item1.xml", "<?xml version=\"1.0\"?><DocumentTemplate/>"
            .getBytes(StandardCharsets.UTF_8));
        return entries;
    }

    private static String itemProps(String storeItemId) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>"
            + "<ds:datastoreItem ds:itemID=\"" + storeItemId + "\""
            + " xmlns:ds=\"http://schemas.openxmlformats.org/officeDocument/2006/customXml\"/>";
    }
}
