package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPathFactoryConfigurationException;

/**
 * Reads a generated deed back the way something other than this service would.
 *
 * <p><strong>Deliberately shares no code with the generator.</strong> The generator writes both halves of the
 * document with string surgery, so a test that checked its work with the same helpers would only prove the
 * regexes agreed with themselves. Everything here goes through {@code javax.xml} and a real XPath engine
 * instead: {@link #resolveBinding} evaluates the template's own {@code w:xpath} against the data part the
 * generator wrote, which is the check docx4j performed during the spike and the only reason to believe the
 * output rather than the code.
 */
final class DeedPackages {

    /**
     * The WordprocessingML main namespace, which is where {@code w:xpath} and {@code w:t} live.
     */
    static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private static final Pattern TEXT_RUN = Pattern.compile("(?s)(<w:t(?: [^>]*)?>).*?(</w:t>)");

    private DeedPackages() {
    }

    /**
     * Every entry of a {@code .docx}, keyed by name, in the order the package holds them.
     */
    static Map<String, byte[]> entries(byte[] docx) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), zip.readAllBytes());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Not a readable zip", e);
        }
        return entries;
    }

    /** One entry, or a failure naming the entry rather than a null. */
    static byte[] entry(Map<String, byte[]> entries, String name) {
        byte[] content = entries.get(name);
        if (content == null) {
            throw new NoSuchElementException("No " + name + " in " + entries.keySet());
        }
        return content;
    }

    /** The template as vendored, for comparing a generated package against the one it came from. */
    static byte[] template(DeedTemplate template) {
        try (InputStream resource =
                 DeedPackages.class.getClassLoader().getResourceAsStream(template.resourcePath())) {
            if (resource == null) {
                throw new NoSuchElementException("No " + template.resourcePath() + " on the classpath");
            }
            return resource.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + template.resourcePath(), e);
        }
    }

    /** An XML part as a namespace-aware DOM, with external entities refused. */
    static Document parse(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalStateException("Not well-formed XML: " + new String(xml, StandardCharsets.UTF_8), e);
        }
    }

    /** Every text run in document order -- the document as a reader that ignores bindings displays it. */
    static List<String> textRuns(Document part) {
        List<String> runs = new ArrayList<>();
        NodeList nodes = part.getElementsByTagNameNS(W, "t");
        for (int i = 0; i < nodes.getLength(); i++) {
            runs.add(nodes.item(i).getTextContent());
        }
        return runs;
    }

    /**
     * The cached text of every bound content control, keyed by the Dataverse element its binding names.
     *
     * <p>A {@link Map} rather than a list because a field bound by three controls must show the same value in
     * all three: a duplicate key with a different value would be the interesting failure, so
     * {@link #cachedText} refuses one rather than letting the last writer win.
     */
    static Map<String, String> cachedText(Document part) {
        Map<String, String> cached = new LinkedHashMap<>();
        for (Element binding : bindings(part)) {
            Element control = enclosingControl(binding);
            NodeList runs = control.getElementsByTagNameNS(W, "t");
            if (runs.getLength() != 1) {
                throw new IllegalStateException(
                    "The control binding " + element(binding) + " holds " + runs.getLength() + " text runs");
            }
            String text = runs.item(0).getTextContent();
            String previous = cached.put(element(binding), text);
            if (previous != null && !previous.equals(text)) {
                throw new IllegalStateException("Two controls binding " + element(binding)
                    + " cache different text: '" + previous + "' and '" + text + "'");
            }
        }
        return cached;
    }

    /**
     * Every {@code dataBinding} element in a document part, in document order.
     */
    static List<Element> bindings(Document part) {
        List<Element> bindings = new ArrayList<>();
        NodeList nodes = part.getElementsByTagNameNS("*", "dataBinding");
        for (int i = 0; i < nodes.getLength(); i++) {
            bindings.add((Element) nodes.item(i));
        }
        return bindings;
    }

    /** The Dataverse element name a binding's XPath ends in. */
    static String element(Element binding) {
        String xpath = binding.getAttributeNS(W, "xpath");
        return xpath.substring(xpath.lastIndexOf('/') + 1).replace("[1]", "");
    }

    /**
     * What a binding actually resolves to in the data part the generator wrote.
     *
     * <p>The binding's own {@code w:xpath} and {@code w:prefixMappings}, evaluated by
     * {@code javax.xml.xpath}. Nothing is reconstructed from what the generator was asked to write, so a data
     * part in the wrong namespace -- the failure the spike warned resolves silently to empty -- shows up here as
     * an empty string where a name should be.
     */
    static String resolveBinding(Element binding, Document dataPart) {
        String xpath = binding.getAttributeNS(W, "xpath");
        Map<String, String> prefixes = prefixMappings(binding.getAttributeNS(W, "prefixMappings"));
        try {
            return (String) engine(prefixes).evaluate(xpath, dataPart, XPathConstants.STRING);
        } catch (XPathExpressionException e) {
            throw new IllegalStateException("Could not evaluate " + xpath, e);
        }
    }

    /**
     * Whether a binding resolves to a node at all, as opposed to resolving to the empty string.
     *
     * <p>The two are indistinguishable through {@link #resolveBinding}, and they are different faults: an empty
     * column should produce an element with no text, whereas a missing element means the data part does not
     * describe the field and Word would have nothing to show.
     */
    static boolean bindingHasNode(Element binding, Document dataPart) {
        String xpath = binding.getAttributeNS(W, "xpath");
        Map<String, String> prefixes = prefixMappings(binding.getAttributeNS(W, "prefixMappings"));
        try {
            return engine(prefixes).evaluate(xpath, dataPart, XPathConstants.NODE) != null;
        } catch (XPathExpressionException e) {
            throw new IllegalStateException("Could not evaluate " + xpath, e);
        }
    }

    private static XPath engine(Map<String, String> prefixes) {
        try {
            XPathFactory factory = XPathFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            XPath engine = factory.newXPath();
            engine.setNamespaceContext(context(prefixes));
            return engine;
        } catch (XPathFactoryConfigurationException e) {
            throw new IllegalStateException("Could not configure an XPath engine", e);
        }
    }

    /**
     * A document part with every text run emptied, so two parts can be compared for everything else.
     *
     * <p>This is how "the court's wording is untouched" is stated as a check rather than as an intention: the
     * generator may only change the characters inside a {@code w:t}, so the template and the generated part must
     * be byte-identical once every run is emptied.
     */
    static String withoutTextRuns(byte[] part) {
        String xml = new String(part, StandardCharsets.UTF_8);
        return TEXT_RUN.matcher(xml).replaceAll("$1$2");
    }

    private static Element enclosingControl(Element binding) {
        Node node = binding;
        while (node != null) {
            if (node instanceof Element candidate && W.equals(candidate.getNamespaceURI())
                && "sdt".equals(candidate.getLocalName())) {
                return candidate;
            }
            node = node.getParentNode();
        }
        throw new IllegalStateException("A dataBinding outside any w:sdt");
    }

    private static Map<String, String> prefixMappings(String prefixMappings) {
        Map<String, String> mappings = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile("xmlns:([\\w.-]+)='([^']*)'").matcher(prefixMappings);
        while (matcher.find()) {
            mappings.put(matcher.group(1), matcher.group(2));
        }
        return mappings;
    }

    private static NamespaceContext context(Map<String, String> prefixes) {
        return new NamespaceContext() {
            @Override
            public String getNamespaceURI(String prefix) {
                return prefixes.getOrDefault(prefix, XMLConstants.NULL_NS_URI);
            }

            @Override
            public String getPrefix(String namespaceUri) {
                return prefixes.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(namespaceUri))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
            }

            @Override
            public Iterator<String> getPrefixes(String namespaceUri) {
                return prefixes.keySet().iterator();
            }
        };
    }
}
