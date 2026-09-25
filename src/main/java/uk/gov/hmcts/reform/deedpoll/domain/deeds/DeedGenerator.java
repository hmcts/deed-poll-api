package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import org.springframework.stereotype.Service;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Fills one of the court's deed templates with a case's data.
 *
 * <p><strong>No library, and that is a decision rather than an omission.</strong> {@code java.util.zip} and
 * plain text surgery do the whole job because the input is two fixed templates vendored in this repo, not
 * arbitrary user documents. Both alternatives were resolved and measured on 2026-09-25 and both were rejected on
 * weight rather than licence: {@code docx4j-core} 11.5.0 plus its JAXB reference implementation pulls 31 jars
 * and 16.3 MB, including {@code xalan-interpretive}, and {@code poi-ooxml} 5.4.1 pulls 13 jars and 18.5 MB. The
 * measurements are recorded in {@code docs/plans/notify-4b-generate-the-deed.md} so that a later PDF step does
 * not have to take them again. docx4j was still worth the spike: it read back a document this code's own
 * approach wrote, resolved all 30 bindings, and is the only reason to believe the output rather than the code.
 *
 * <p><strong>Two entries of the package are replaced and nothing else is touched.</strong>
 * {@code customXml/item1.xml} is rewritten from scratch by {@link DeedDataPart}, and the cached text inside the
 * bound content controls of {@code word/document.xml} by {@link DeedControls}. Every other entry -- the styles,
 * the images, the glossary, {@code customXml/itemProps1.xml} with its per-template {@code ds:itemID}, and every
 * relationship -- is copied byte for byte.
 *
 * <p><strong>It does not decide which deed a case takes.</strong> The template is an argument; see
 * {@link DeedTemplate} for why that rule lives with the email rather than here.
 *
 * <p>Stateless and cheap: the template is read from the classpath per call rather than cached, because a deed is
 * generated at most once per case (decision 6) and 355 KB read from a jar is not the cost worth optimising.
 */
@Service
public class DeedGenerator {

    /** The bound data part, replaced wholesale. */
    private static final String DATA_PART = "customXml/item1.xml";

    /**
     * Where the {@code ds:itemID} every binding must name is declared. Read, never written.
     */
    private static final String ITEM_PROPS = "customXml/itemProps1.xml";

    /** The body: the court's wording, plus the content controls whose cached text is replaced. */
    private static final String DOCUMENT_PART = "word/document.xml";

    /**
     * The timestamp every entry of the output carries.
     *
     * <p>Fixed, and it is the one both templates already carry -- the earliest a zip can express. A generated
     * deed must be the same bytes whenever it is generated, or "the stored bytes are what the generator
     * produced" stops being a question anybody can answer twice. {@code setTimeLocal} rather than
     * {@code setTime}, because the latter takes an instant and would make the output depend on the pod's zone.
     */
    private static final LocalDateTime ENTRY_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);

    /**
     * Generates the deed for one case.
     *
     * @param deedPoll the case whose values the deed prints
     * @param template which of the court's deeds to produce
     * @return the file, with the format it is in
     * @throws IllegalStateException if the template no longer has the shape this generator writes -- an unknown
     *     bound field, a nested or multi-run content control, a binding naming a data store the package does not
     *     hold, or a field the template was expected to bind and does not
     */
    public GeneratedDeed generate(DeedPoll deedPoll, DeedTemplate template) {
        return generateFrom(deedPoll, template, read(template));
    }

    /**
     * The generate, given a package rather than reading the vendored one.
     *
     * <p>Package-private, and it exists so the checks below can be shown to fire. Every one of them guards a
     * failure that is otherwise silent -- a field the template stopped binding, a control that gained a second
     * text run, a binding pointed at a data store the package does not hold -- and the only way to prove a guard
     * fires is to hand the generator a package that breaks it. The vendored templates cannot be that package,
     * because they are correct.
     */
    GeneratedDeed generateFrom(DeedPoll deedPoll, DeedTemplate template, Map<String, byte[]> templateEntries) {
        Map<String, byte[]> entries = new LinkedHashMap<>(templateEntries);

        String documentXml = new String(part(entries, template, DOCUMENT_PART), StandardCharsets.UTF_8);
        DeedControls.Parsed parsed = DeedControls.read(documentXml);

        String itemId = DeedDataPart.itemId(part(entries, template, ITEM_PROPS));
        if (!itemId.equals(parsed.storeItemId())) {
            // Silent if unchecked: Word would find no data store for the bindings and every control would
            // resolve to empty, so the deed would print the cached text and nothing the applicant told us.
            throw new IllegalStateException(template.formCode() + " binds the data store "
                + parsed.storeItemId() + " but its customXml/itemProps1.xml declares " + itemId);
        }

        checkFields(template, parsed.fields());

        Map<DeedField, String> values = DeedField.resolve(template, deedPoll);
        entries.put(DATA_PART, DeedDataPart.build(parsed.schema(), values));
        entries.put(DOCUMENT_PART, DeedControls.fill(documentXml, parsed, values).getBytes(StandardCharsets.UTF_8));

        return new GeneratedDeed(template, DeedOutputFormat.DOCX, write(entries));
    }

    /**
     * That the template binds exactly the fields {@link DeedField} says it does, in both directions.
     *
     * <p>{@link DeedControls#read} has already refused an element no {@link DeedField} knows. This is the other
     * direction: a template re-authored with a control removed would otherwise generate happily and print one
     * less fact than the deed is meant to state, and a field declared as bound by a template that does not bind
     * it is a mapping nobody is applying.
     */
    private void checkFields(DeedTemplate template, Set<DeedField> bound) {
        List<DeedField> declared = DeedField.fieldsFor(template);
        for (DeedField field : declared) {
            if (!bound.contains(field)) {
                throw new IllegalStateException(template.formCode() + " does not bind " + field.element()
                    + ", which DeedField says it does");
            }
        }
        for (DeedField field : bound) {
            if (!field.boundBy(template)) {
                throw new IllegalStateException(template.formCode() + " binds " + field.element()
                    + ", which DeedField says belongs to the other deed");
            }
        }
    }

    /** Every entry of the template package, in the order the template holds them. */
    private Map<String, byte[]> read(DeedTemplate template) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (InputStream resource = getClass().getClassLoader().getResourceAsStream(template.resourcePath())) {
            if (resource == null) {
                throw new IllegalStateException("The template " + template.resourcePath() + " is not on the classpath");
            }
            try (ZipInputStream zip = new ZipInputStream(resource)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.isDirectory()) {
                        entries.put(entry.getName(), zip.readAllBytes());
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the template " + template.resourcePath(), e);
        }
        return entries;
    }

    /**
     * The package written back out.
     *
     * <p>Entry order is the template's, which keeps {@code [Content_Types].xml} first as the OPC specification
     * requires. No timestamp is set, so the output does not depend on when it was generated: two runs over the
     * same case produce the same bytes, which is what makes "the stored bytes are what the generator produced"
     * a check somebody can repeat.
     */
    private byte[] write(Map<String, byte[]> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(entries.size() * 4096);
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry out = new ZipEntry(entry.getKey());
                out.setTimeLocal(ENTRY_TIME);
                zip.putNextEntry(out);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the generated deed", e);
        }
        return bytes.toByteArray();
    }

    private byte[] part(Map<String, byte[]> entries, DeedTemplate template, String name) {
        byte[] content = entries.get(name);
        if (content == null) {
            throw new IllegalStateException(template.formCode() + " has no " + name);
        }
        return content;
    }
}
