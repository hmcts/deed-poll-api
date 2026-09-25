package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The bound data part: {@code customXml/item1.xml}, written from scratch for one case.
 *
 * <p><strong>From scratch, and never by patching the template's own.</strong> Both templates ship a data part
 * -- 130,472 bytes in the adult one -- holding all 111 elements of the Dataverse entity rather than the bound
 * subset, with each element's value being its own name. Worse, 17 of the adult template's elements hold a ~7 KB
 * Word document escaped into the element text, which is an artifact of how Dynamics exported the file. A
 * generator that patched that part would carry the junk into every element it did not overwrite, on a legal
 * document. Building it fresh writes only the fields the template actually binds, and dropped the adult output
 * from 57,630 to 49,599 bytes as a side effect.
 *
 * <p><strong>Nothing else in the package is touched.</strong> The part, {@code customXml/itemProps1.xml} and
 * {@code customXml/_rels/item1.xml.rels} are all present in both templates and the relationship from
 * {@code word/document.xml.rels} resolves, so there is nothing to add and no relationship to invent. Leaving
 * {@code itemProps1.xml} alone is also how the per-template {@code ds:itemID} looks after itself: it is
 * {@code {C9ADC254-…}} on the adult deed and {@code {97E651D3-…}} on the minor's, so a hard-coded GUID would
 * leave one template's 13 bindings resolving against nothing.
 */
final class DeedDataPart {

    /**
     * The {@code ds:itemID} on the {@code ds:datastoreItem} root, which every binding's {@code w:storeItemID}
     * must equal. Matched with a regex rather than parsed, because the part is 360 bytes of one element and the
     * only question asked of it is this attribute's value.
     */
    private static final Pattern ITEM_ID = Pattern.compile("ds:itemID=\"([^\"]*)\"");

    private DeedDataPart() {
    }

    /**
     * The GUID this template's bindings must name, read from its own {@code customXml/itemProps1.xml}.
     */
    static String itemId(byte[] itemProps) {
        String xml = new String(itemProps, StandardCharsets.UTF_8);
        Matcher matcher = ITEM_ID.matcher(xml);
        if (!matcher.find()) {
            throw new IllegalStateException("customXml/itemProps1.xml declares no ds:itemID");
        }
        return matcher.group(1);
    }

    /**
     * One case's values as the data part the bindings resolve against.
     *
     * <p>Indented two spaces and one element per line, matching the supplied part, because this is an artifact
     * somebody will one day unzip and read. The whitespace is between elements only -- never inside one -- so
     * no value gains a space it did not have.
     */
    static byte[] build(DeedSchema schema, Map<DeedField, String> values) {
        StringBuilder xml = new StringBuilder(1024);
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        xml.append('<').append(schema.rootElement())
            .append(" xmlns=\"").append(schema.namespaceUri()).append("\">\n");
        // xmlns="" resets the namespace, because the bindings name this element and its children with no
        // prefix. Without it every XPath resolves to nothing and the deed prints blank throughout.
        xml.append("  <").append(schema.entityElement()).append(" xmlns=\"\">\n");
        values.forEach((field, value) -> xml.append("    <").append(field.element()).append('>')
            .append(DeedXml.escape(value))
            .append("</").append(field.element()).append(">\n"));
        xml.append("  </").append(schema.entityElement()).append(">\n");
        xml.append("</").append(schema.rootElement()).append('>');
        return xml.toString().getBytes(StandardCharsets.UTF_8);
    }
}
