package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The content controls in {@code word/document.xml}: what they bind, and where their cached text sits.
 *
 * <p><strong>Why the body is touched at all.</strong> A data part on its own is enough for Word, which resolves
 * each binding as it opens the file -- but the text cached inside the control is what a reader that does not
 * resolve bindings displays, and the applicant opens this document in whatever they have. Leaving the cached
 * text alone ships a deed reading "Forenames(s) deedpoll_newforename" to anyone not using Word, which is worse
 * than a blank. So both halves are written.
 *
 * <p><strong>Decision 4 -- the court owns the wording, engineering does not -- is honoured mechanically rather
 * than by care.</strong> The only bytes this class changes are the characters between {@code <w:t>} and
 * {@code </w:t>} inside a bound control. It never reserialises the document, never touches a paragraph, a table,
 * a style or a word of the deed, and it cannot: it works from the offsets it found and splices.
 *
 * <p>Three properties of the templates make that edit safe rather than delicate, and all three are
 * <strong>checked rather than assumed</strong>, because a re-authored template could break any of them and the
 * failure would otherwise be silent -- a deed that prints a placeholder, or one whose bindings resolve against
 * nothing:
 *
 * <ol>
 *   <li>no content control is nested inside another;</li>
 *   <li>every bound control holds exactly one {@code w:t};</li>
 *   <li>every binding names the same data store and the same XPath shape, so one {@link DeedSchema} and one
 *       {@code ds:itemID} are the answer for the whole document.</li>
 * </ol>
 */
final class DeedControls {

    /** The whole XPath both templates use, in four parts: prefix, root, entity, field. */
    private static final Pattern BINDING_XPATH = Pattern.compile(
        "^/([A-Za-z_][\\w.-]*):([\\w.-]+)\\[1\\]/([\\w.-]+)\\[1\\]/([\\w.-]+)\\[1\\]$");

    private static final String SDT_OPEN = "<w:sdt>";
    private static final String SDT_CLOSE = "</w:sdt>";
    private static final String SDT_CONTENT_OPEN = "<w:sdtContent>";
    private static final String TEXT_CLOSE = "</w:t>";

    private DeedControls() {
    }

    /**
     * One bound control: which field it shows, and the half-open range of the cached text it shows it in.
     *
     * @param field the field the binding's XPath names
     * @param textStart index of the first character inside the control's {@code w:t}
     * @param textEnd index one past the last, so {@code [textStart, textEnd)} is what gets replaced
     */
    record Control(DeedField field, int textStart, int textEnd) {
    }

    /**
     * What one template's {@code word/document.xml} says about itself.
     *
     * @param controls every bound control, in document order
     * @param schema the data-part shape all of them agree on
     * @param storeItemId the {@code ds:itemID} all of them name, which the template's own
     *     {@code customXml/itemProps1.xml} must declare
     */
    record Parsed(List<Control> controls, DeedSchema schema, String storeItemId) {

        /** The distinct fields bound, which is 18 on the adult deed across 30 controls and 13 on the minor's. */
        Set<DeedField> fields() {
            return controls.stream().map(Control::field).collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    /** Reads every content control in a template's main document part, checking the three properties above. */
    static Parsed read(String documentXml) {
        List<Control> controls = new ArrayList<>();
        DeedSchema schema = null;
        String storeItemId = null;

        int from = 0;
        while (true) {
            int open = documentXml.indexOf(SDT_OPEN, from);
            if (open < 0) {
                break;
            }
            int close = documentXml.indexOf(SDT_CLOSE, open);
            if (close < 0) {
                throw new IllegalStateException("A w:sdt at " + open + " is never closed");
            }
            int bodyStart = open + SDT_OPEN.length();
            int nextOpen = documentXml.indexOf(SDT_OPEN, bodyStart);
            if (nextOpen >= 0 && nextOpen < close) {
                // A nested control would make the offsets below belong to the wrong control, so the splice
                // would put one field's value where another's belongs.
                throw new IllegalStateException("The w:sdt at " + open + " contains another w:sdt");
            }

            Optional<String> binding = bindingTag(documentXml, bodyStart, close);
            if (binding.isPresent()) {
                String tag = binding.get();
                Binding parsed = binding(tag);

                if (schema == null) {
                    schema = parsed.schema();
                    storeItemId = parsed.storeItemId();
                } else {
                    require(schema.equals(parsed.schema()),
                        "Two bindings disagree about the data part: " + schema + " and " + parsed.schema());
                    require(storeItemId.equals(parsed.storeItemId()),
                        "Two bindings name different data stores: " + storeItemId + " and " + parsed.storeItemId());
                }

                DeedField field = DeedField.forElement(parsed.element()).orElseThrow(() ->
                    new IllegalStateException("The template binds " + parsed.element()
                        + ", which DeedField does not know -- so the deed would print a placeholder"));
                controls.add(text(documentXml, bodyStart, close, field));
            }

            from = close + SDT_CLOSE.length();
        }

        require(!controls.isEmpty(), "The template has no bound content controls at all");
        return new Parsed(List.copyOf(controls), schema, storeItemId);
    }

    /**
     * Replaces every bound control's cached text with the case's value, and nothing else.
     *
     * <p>Written back to front, so each splice leaves the offsets of the controls before it untouched.
     */
    static String fill(String documentXml, Parsed parsed, Map<DeedField, String> values) {
        StringBuilder out = new StringBuilder(documentXml);
        List<Control> controls = parsed.controls();
        for (int i = controls.size() - 1; i >= 0; i--) {
            Control control = controls.get(i);
            String value = values.get(control.field());
            require(value != null, "No value resolved for " + control.field().element());
            out.replace(control.textStart(), control.textEnd(), DeedXml.escape(value));
        }
        return out.toString();
    }

    /**
     * The {@code dataBinding} element of a control, or empty when it has none to bind.
     */
    private static Optional<String> bindingTag(String documentXml, int bodyStart, int close) {
        int at = documentXml.indexOf("dataBinding", bodyStart);
        if (at < 0 || at > close) {
            return Optional.empty();
        }
        int tagStart = documentXml.lastIndexOf('<', at);
        int tagEnd = documentXml.indexOf('>', at);
        require(tagStart >= bodyStart && tagEnd > tagStart && tagEnd < close,
            "A dataBinding at " + at + " is not a complete element inside its own w:sdt");
        return Optional.of(documentXml.substring(tagStart, tagEnd + 1));
    }

    /**
     * The one {@code w:t} inside a control, as the range of its text.
     */
    private static Control text(String documentXml, int bodyStart, int close, DeedField field) {
        int contentAt = documentXml.indexOf(SDT_CONTENT_OPEN, bodyStart);
        require(contentAt >= 0 && contentAt < close, "The control binding " + field.element() + " has no w:sdtContent");

        List<Integer> runs = new ArrayList<>();
        int at = contentAt;
        while (true) {
            at = documentXml.indexOf("<w:t", at + 1);
            if (at < 0 || at > close) {
                break;
            }
            // "<w:tc", "<w:tcPr", "<w:tblPr" and friends all start "<w:t" too, so the character after the
            // name is what distinguishes the text run.
            char next = documentXml.charAt(at + 4);
            if (next == ' ' || next == '>' || next == '/') {
                runs.add(at);
            }
        }
        require(runs.size() == 1,
            "The control binding " + field.element() + " holds " + runs.size()
                + " w:t elements, and this generator replaces exactly one");

        int runAt = runs.get(0);
        require(documentXml.charAt(runAt + 4) != '/',
            "The control binding " + field.element() + " holds a self-closing w:t, which has no text to replace");
        int textStart = documentXml.indexOf('>', runAt) + 1;
        int textEnd = documentXml.indexOf(TEXT_CLOSE, textStart);
        require(textStart > 0 && textEnd > 0 && textEnd < close,
            "The w:t in the control binding " + field.element() + " is never closed inside its own w:sdt");
        return new Control(field, textStart, textEnd);
    }

    /** A binding's XPath and data store, as the four things they say. */
    private static Binding binding(String tag) {
        String xpath = attribute(tag, "w:xpath")
            .orElseThrow(() -> new IllegalStateException("A dataBinding declares no w:xpath: " + tag));
        String storeItemId = attribute(tag, "w:storeItemID")
            .orElseThrow(() -> new IllegalStateException("A dataBinding declares no w:storeItemID: " + tag));
        String prefixMappings = attribute(tag, "w:prefixMappings")
            .orElseThrow(() -> new IllegalStateException("A dataBinding declares no w:prefixMappings: " + tag));

        Matcher matcher = BINDING_XPATH.matcher(xpath);
        require(matcher.matches(), "A dataBinding's w:xpath is not the shape this generator writes: " + xpath);
        String prefix = matcher.group(1);
        String namespaceUri = prefixMapping(prefixMappings, prefix).orElseThrow(() ->
            new IllegalStateException("The prefix " + prefix + " in " + xpath + " is not mapped by "
                + prefixMappings));
        return new Binding(new DeedSchema(namespaceUri, matcher.group(2), matcher.group(3)), matcher.group(4),
            storeItemId);
    }

    /**
     * One attribute of an element, matched textually.
     *
     * <p>The attribute values here are GUIDs, XPaths and namespace URIs, none of which contains a double quote,
     * and every one is written by Word with double quotes. A parser would be the right tool for arbitrary XML;
     * for two fixed templates vendored in this repo it would mean reserialising the part, which is the one thing
     * this class must not do.
     */
    private static Optional<String> attribute(String tag, String name) {
        int at = tag.indexOf(name + "=\"");
        if (at < 0) {
            return Optional.empty();
        }
        int valueStart = at + name.length() + 2;
        int valueEnd = tag.indexOf('"', valueStart);
        return valueEnd < 0 ? Optional.empty() : Optional.of(tag.substring(valueStart, valueEnd));
    }

    /**
     * What {@code w:prefixMappings} maps a prefix to -- {@code xmlns:ns0='urn:…'}, single-quoted by Word.
     */
    private static Optional<String> prefixMapping(String prefixMappings, String prefix) {
        Matcher matcher = Pattern.compile("xmlns:" + Pattern.quote(prefix) + "=(?:'([^']*)'|&apos;([^&]*)&apos;)")
            .matcher(prefixMappings);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /** A binding's three answers: the data-part shape, the field it names, and the store it reads. */
    private record Binding(DeedSchema schema, String element, String storeItemId) {
    }
}
