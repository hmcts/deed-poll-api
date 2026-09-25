package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MaritalStatus;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Nationality;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NationalitySection;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deed generator, checked by reading its output rather than by trusting the code that wrote it.
 *
 * <p><strong>Container-free, so CI runs it.</strong> Nothing here needs a database: the input is an unsaved
 * {@link DeedPoll} and two templates on the classpath, which is deliberate -- the generator is the one piece of
 * this plan whose failures are all silent, and a check that only ran on a Docker-capable machine would not be a
 * check.
 *
 * <p>Every assertion goes through {@link DeedPackages}, which resolves each binding's own {@code w:xpath} with a
 * real XPath engine instead of re-deriving what the generator was asked to write. That is the check docx4j
 * performed in the spike: a data part in the wrong namespace, or bound to the wrong data store, resolves
 * silently to empty, and only an engine that follows the template's own instructions will say so.
 */
class DeedGeneratorTest {

    private static final String DATA_PART = "customXml/item1.xml";
    private static final String ITEM_PROPS = "customXml/itemProps1.xml";
    private static final String DOCUMENT_PART = "word/document.xml";

    /**
     * The Dataverse entity type code the bindings name. {@code 10740} is {@code deedpoll_deedpoll}.
     */
    private static final String CRM_NAMESPACE = "urn:microsoft-crm/document-template/deedpoll_deedpoll/10740/";

    private final DeedGenerator generator = new DeedGenerator();

    @Nested
    @DisplayName("the values a deed prints")
    class Values {

        @ParameterizedTest
        @EnumSource(DeedTemplate.class)
        @DisplayName("every bound field resolves to the case's value through the template's own XPath")
        void everyBoundFieldResolvesToTheCaseValue(DeedTemplate template) {
            Map<String, byte[]> entries = DeedPackages.entries(generator.generate(completeCase(), template).content());
            Document document = DeedPackages.parse(DeedPackages.entry(entries, DOCUMENT_PART));
            Document dataPart = DeedPackages.parse(DeedPackages.entry(entries, DATA_PART));
            Map<String, String> expected = expectedFor(template);

            List<Element> bindings = DeedPackages.bindings(document);
            assertThat(bindings).isNotEmpty();
            for (Element binding : bindings) {
                String element = DeedPackages.element(binding);
                assertThat(expected).containsKey(element);
                assertThat(DeedPackages.resolveBinding(binding, dataPart))
                    .as("%s resolved through %s", element, template.formCode())
                    .isEqualTo(expected.get(element));
            }

            assertThat(bindings.stream().map(DeedPackages::element).collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(expected.keySet());
        }

        @ParameterizedTest
        @EnumSource(DeedTemplate.class)
        @DisplayName("the cached text a non-Word reader displays is the case's value too, in every control")
        void theCachedTextIsTheCaseValueInEveryControl(DeedTemplate template) {
            Map<String, byte[]> entries = DeedPackages.entries(generator.generate(completeCase(), template).content());
            Document document = DeedPackages.parse(DeedPackages.entry(entries, DOCUMENT_PART));

            // The whole reason word/document.xml is modified at all: a reader that does not resolve bindings
            // shows this text, and the applicant opens the file in whatever they have.
            assertThat(DeedPackages.cachedText(document)).isEqualTo(expectedFor(template));
        }

        @ParameterizedTest
        @EnumSource(DeedTemplate.class)
        @DisplayName("no field name survives anywhere a reader can see it")
        void noFieldNameSurvivesAnywhereReadable(DeedTemplate template) {
            Map<String, byte[]> entries = DeedPackages.entries(generator.generate(completeCase(), template).content());
            Document document = DeedPackages.parse(DeedPackages.entry(entries, DOCUMENT_PART));

            assertThat(DeedPackages.textRuns(document))
                .as("the rendered deed still shows a placeholder")
                .noneMatch(run -> run.contains("deedpoll_"));
        }

        @Test
        @DisplayName("the adult deed shows each value beside its own printed label")
        void theAdultDeedShowsEachValueBesideItsLabel() {
            List<String> runs = renderedText(DeedTemplate.ADULT);

            // What a human opening the document in Word would have been checking, which this devcontainer
            // cannot do: each value adjacent to the label the court printed beside it.
            assertThat(runs).containsSubsequence("Date you completed the form", "9 March 2026");
            assertThat(runs).containsSubsequence("Forenames(s)", "Robin", "Middle name(s)", "Ash",
                "Surname(s)", "Okonkwo-Hart");
            assertThat(runs).containsSubsequence("Your email address", "robin@example.com");
            assertThat(runs).containsSubsequence("Status", "Married / Civil Partnership");
            assertThat(runs).containsSubsequence("Nationality", "British Citizen");
        }

        @Test
        @DisplayName("the minor's deed shows the child's age and the parents, and no marital status")
        void theMinorDeedShowsTheAgeAndTheParents() {
            List<String> runs = renderedText(DeedTemplate.MINOR);

            assertThat(runs).containsSubsequence("Date the deed is made", "9 March 2026");
            assertThat(runs).containsSubsequence("The minor is single and aged", "9");
            assertThat(runs).contains("Robert Smith and Ada Smith");
            // Marital status is the applicant's and the minor's deed does not ask for it, so the label the
            // adult deed prints it under is absent here rather than empty.
            assertThat(runs).doesNotContain("Status");
            assertThat(runs).doesNotContain("Married / Civil Partnership");
        }
    }

    @Nested
    @DisplayName("a case with columns unset")
    class Nulls {

        @ParameterizedTest
        @EnumSource(DeedTemplate.class)
        @DisplayName("an unset column resolves to an empty binding rather than breaking the document")
        void anUnsetColumnResolvesToAnEmptyBinding(DeedTemplate template) {
            Map<String, byte[]> entries = DeedPackages.entries(generator.generate(new DeedPoll(), template).content());
            Document document = DeedPackages.parse(DeedPackages.entry(entries, DOCUMENT_PART));
            Document dataPart = DeedPackages.parse(DeedPackages.entry(entries, DATA_PART));

            for (Element binding : DeedPackages.bindings(document)) {
                String element = DeedPackages.element(binding);
                // The element is present and empty, not missing: a binding with no node at all is a different
                // fault from a column with nothing in it, and only one of the two is what an empty case means.
                assertThat(DeedPackages.bindingHasNode(binding, dataPart)).as("%s has a node", element).isTrue();
                assertThat(DeedPackages.resolveBinding(binding, dataPart)).as(element).isEmpty();
            }
        }

        @ParameterizedTest
        @EnumSource(DeedTemplate.class)
        @DisplayName("nothing prints the text null, and no placeholder comes back in its place")
        void nothingPrintsTheTextNull(DeedTemplate template) {
            Map<String, byte[]> entries = DeedPackages.entries(generator.generate(new DeedPoll(), template).content());
            Document document = DeedPackages.parse(DeedPackages.entry(entries, DOCUMENT_PART));

            assertThat(DeedPackages.cachedText(document).values()).allMatch(String::isEmpty);
            assertThat(DeedPackages.textRuns(document))
                .noneMatch(run -> run.contains("null") || run.contains("deedpoll_"));
        }

        @Test
        @DisplayName("an enum column unset prints nothing, and set prints its label rather than its member name")
        void anEnumColumnPrintsItsLabel() {
            DeedPoll deedPoll = new DeedPoll();
            deedPoll.setNationality(Nationality.BritishDependantTerritoriesCitizen);
            deedPoll.setNationalitySection(NationalitySection.NoneOfTheAbove);

            Map<String, String> cached = DeedPackages.cachedText(DeedPackages.parse(DeedPackages.entry(
                DeedPackages.entries(generator.generate(deedPoll, DeedTemplate.ADULT).content()), DOCUMENT_PART)));

            assertThat(cached).containsEntry("deedpoll_nationality", "British Dependant Territories Citizen");
            assertThat(cached).containsEntry("deedpoll_section", "None of the Above");
            assertThat(cached).containsEntry("deedpoll_status", "");
        }
    }

    @Nested
    @DisplayName("the two field sets")
    class FieldSets {

        @Test
        @DisplayName("the adult deed binds 30 controls across 18 distinct fields")
        void theAdultDeedBindsThirtyControlsAcrossEighteenFields() {
            Document document = documentPartOf(DeedTemplate.ADULT);

            assertThat(DeedPackages.bindings(document)).hasSize(30);
            assertThat(DeedPackages.cachedText(document)).hasSize(18);
        }

        @Test
        @DisplayName("the minor's deed binds 13 controls across 13 distinct fields")
        void theMinorDeedBindsThirteenControlsAcrossThirteenFields() {
            Document document = documentPartOf(DeedTemplate.MINOR);

            assertThat(DeedPackages.bindings(document)).hasSize(13);
            assertThat(DeedPackages.cachedText(document)).hasSize(13);
        }

        @Test
        @DisplayName("the two sets differ exactly as the templates do: 11 shared, 7 adult-only, 2 minor-only")
        void theTwoSetsDifferExactlyAsTheTemplatesDo() {
            Set<String> adult = DeedPackages.cachedText(documentPartOf(DeedTemplate.ADULT)).keySet();
            Set<String> minor = DeedPackages.cachedText(documentPartOf(DeedTemplate.MINOR)).keySet();

            assertThat(adult).containsAll(shared());
            assertThat(minor).containsAll(shared());
            assertThat(adult).containsExactlyInAnyOrderElementsOf(union(shared(), adultOnly()));
            assertThat(minor).containsExactlyInAnyOrderElementsOf(union(shared(), minorOnly()));
        }
    }

    @Nested
    @DisplayName("the package the generator produces")
    class Output {

        @ParameterizedTest
        @EnumSource(DeedTemplate.class)
        @DisplayName("every entry but the data part and the body is copied from the template byte for byte")
        void everyOtherEntryIsCopiedByteForByte(DeedTemplate template) {
            Map<String, byte[]> generated =
                DeedPackages.entries(generator.generate(completeCase(), template).content());
            Map<String, byte[]> original = DeedPackages.entries(DeedPackages.template(template));

            assertThat(generated.keySet()).containsExactlyElementsOf(original.keySet());
            for (String name : original.keySet()) {
                if (!name.equals(DATA_PART) && !name.equals(DOCUMENT_PART)) {
                    assertThat(generated.get(name)).as(name).isEqualTo(original.get(name));
                }
            }
        }

        @ParameterizedTest
        @EnumSource(DeedTemplate.class)
        @DisplayName("itemProps1.xml is untouched, so each template keeps its own ds:itemID")
        void itemPropsIsUntouchedSoEachTemplateKeepsItsOwnItemId(DeedTemplate template) {
            Map<String, byte[]> generated =
                DeedPackages.entries(generator.generate(completeCase(), template).content());

            assertThat(DeedPackages.entry(generated, ITEM_PROPS))
                .isEqualTo(DeedPackages.entry(DeedPackages.entries(DeedPackages.template(template)), ITEM_PROPS));
        }

        @ParameterizedTest
        @EnumSource(DeedTemplate.class)
        @DisplayName("every binding names the data store its own package declares")
        void everyBindingNamesTheDataStoreItsOwnPackageDeclares(DeedTemplate template) {
            Map<String, byte[]> generated =
                DeedPackages.entries(generator.generate(completeCase(), template).content());
            String itemId = itemIdOf(DeedPackages.entry(generated, ITEM_PROPS));

            // Task 1's assertion, kept because its failure is silent: the adult and minor templates carry
            // different GUIDs, so one template's GUID written into the other resolves every binding to nothing.
            for (Element binding : DeedPackages.bindings(DeedPackages.parse(
                DeedPackages.entry(generated, DOCUMENT_PART)))) {
                assertThat(binding.getAttributeNS(DeedPackages.W, "storeItemID")).isEqualTo(itemId);
            }
        }

        @Test
        @DisplayName("the two templates carry different data stores, which is why the GUID is never hard-coded")
        void theTwoTemplatesCarryDifferentDataStores() {
            String adult = itemIdOf(DeedPackages.entry(
                DeedPackages.entries(DeedPackages.template(DeedTemplate.ADULT)), ITEM_PROPS));
            String minor = itemIdOf(DeedPackages.entry(
                DeedPackages.entries(DeedPackages.template(DeedTemplate.MINOR)), ITEM_PROPS));

            assertThat(adult).isEqualTo("{C9ADC254-2098-471F-A6B0-379E83CFF8E7}");
            assertThat(minor).isEqualTo("{97E651D3-4026-41A5-9FD2-30805E1EDFD3}");
        }

        @ParameterizedTest
        @EnumSource(DeedTemplate.class)
        @DisplayName("no content control is nested and each holds exactly one text run")
        void noControlIsNestedAndEachHoldsOneTextRun(DeedTemplate template) {
            // The two properties that make replacing the cached text safe rather than delicate. Asserted on the
            // output rather than on the template, because it is the output an applicant opens.
            Document document = DeedPackages.parse(DeedPackages.entry(
                DeedPackages.entries(generator.generate(completeCase(), template).content()), DOCUMENT_PART));

            NodeList controls = document.getElementsByTagNameNS(DeedPackages.W, "sdt");
            assertThat(controls.getLength()).isPositive();
            for (int i = 0; i < controls.getLength(); i++) {
                Element control = (Element) controls.item(i);
                assertThat(control.getElementsByTagNameNS(DeedPackages.W, "sdt").getLength())
                    .as("a nested content control").isZero();
                assertThat(control.getElementsByTagNameNS(DeedPackages.W, "t").getLength())
                    .as("text runs in one control").isEqualTo(1);
            }
        }

        @ParameterizedTest
        @EnumSource(DeedTemplate.class)
        @DisplayName("the court's wording is untouched: emptying every text run gives the template back")
        void theCourtsWordingIsUntouched(DeedTemplate template) {
            byte[] generated = DeedPackages.entry(
                DeedPackages.entries(generator.generate(completeCase(), template).content()), DOCUMENT_PART);
            byte[] original = DeedPackages.entry(DeedPackages.entries(DeedPackages.template(template)),
                DOCUMENT_PART);

            // Decision 4 as a check rather than as an intention. If anything outside a w:t had moved -- a
            // paragraph, a style, a table cell, a namespace declaration -- these two would differ.
            assertThat(DeedPackages.withoutTextRuns(generated))
                .isEqualTo(DeedPackages.withoutTextRuns(original));
        }

        @ParameterizedTest
        @EnumSource(DeedTemplate.class)
        @DisplayName("only the bound controls' text differs from the template, and every one of them does")
        void onlyTheBoundControlsTextDiffers(DeedTemplate template) {
            List<String> generated = DeedPackages.textRuns(DeedPackages.parse(DeedPackages.entry(
                DeedPackages.entries(generator.generate(completeCase(), template).content()), DOCUMENT_PART)));
            List<String> original = DeedPackages.textRuns(DeedPackages.parse(DeedPackages.entry(
                DeedPackages.entries(DeedPackages.template(template)), DOCUMENT_PART)));

            assertThat(generated).hasSameSizeAs(original);
            List<Integer> changed = new ArrayList<>();
            for (int i = 0; i < original.size(); i++) {
                if (!original.get(i).equals(generated.get(i))) {
                    changed.add(i);
                }
            }

            // Every changed run was a field name, and there are as many of them as the template has bindings --
            // so nothing else was touched and nothing was missed.
            assertThat(changed).hasSize(template == DeedTemplate.ADULT ? 30 : 13);
            assertThat(changed).allSatisfy(index ->
                assertThat(original.get(index).trim()).startsWith("deedpoll_"));
        }

        @ParameterizedTest
        @EnumSource(DeedTemplate.class)
        @DisplayName("the data part is built from scratch and carries only the fields the template binds")
        void theDataPartCarriesOnlyTheFieldsTheTemplateBinds(DeedTemplate template) {
            Document dataPart = DeedPackages.parse(DeedPackages.entry(
                DeedPackages.entries(generator.generate(completeCase(), template).content()), DATA_PART));

            Element entity = onlyChildElement(dataPart.getDocumentElement());
            List<String> elements = childElements(entity).stream().map(Element::getLocalName).toList();

            // The supplied part holds all 111 elements of the Dataverse entity, 17 of them stuffed with an
            // escaped Word document. Patching it rather than rebuilding it would carry that into a legal
            // document, so the check is that only the bound fields are here at all.
            assertThat(elements).containsExactlyInAnyOrderElementsOf(expectedFor(template).keySet());
            assertThat(elements).doesNotContain("createdby", "statuscode", "deedpoll_deedpollid");
        }

        @ParameterizedTest
        @EnumSource(DeedTemplate.class)
        @DisplayName("the data part is in the namespace the bindings map, with the reset on the entity element")
        void theDataPartIsInTheNamespaceTheBindingsMap(DeedTemplate template) {
            Document dataPart = DeedPackages.parse(DeedPackages.entry(
                DeedPackages.entries(generator.generate(completeCase(), template).content()), DATA_PART));

            Element root = dataPart.getDocumentElement();
            assertThat(root.getLocalName()).isEqualTo("DocumentTemplate");
            assertThat(root.getNamespaceURI()).isEqualTo(CRM_NAMESPACE);
            // The reset is load-bearing: the bindings name the entity and its fields with no prefix, so
            // anything below the root must be in no namespace at all or every XPath resolves to empty.
            Element entity = onlyChildElement(root);
            assertThat(entity.getLocalName()).isEqualTo("deedpoll_deedpoll");
            assertThat(entity.getNamespaceURI()).isNull();
            assertThat(childElements(entity)).allSatisfy(field ->
                assertThat(field.getNamespaceURI()).isNull());
        }

        @ParameterizedTest
        @EnumSource(DeedTemplate.class)
        @DisplayName("generating the same case twice produces the same bytes")
        void generatingTheSameCaseTwiceProducesTheSameBytes(DeedTemplate template) {
            DeedPoll deedPoll = completeCase();

            assertThat(generator.generate(deedPoll, template).content())
                .isEqualTo(generator.generate(deedPoll, template).content());
        }

        @ParameterizedTest
        @EnumSource(DeedTemplate.class)
        @DisplayName("the result carries its own format rather than leaving the caller to assume one")
        void theResultCarriesItsOwnFormat(DeedTemplate template) {
            GeneratedDeed deed = generator.generate(completeCase(), template);

            assertThat(deed.template()).isEqualTo(template);
            assertThat(deed.format()).isEqualTo(DeedOutputFormat.DOCX);
            assertThat(deed.mimeType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            assertThat(deed.size()).isEqualTo(deed.content().length).isPositive();
        }
    }

    @Nested
    @DisplayName("a template whose shape has changed")
    class Refusals {

        @Test
        @DisplayName("a binding pointed at a data store the package does not hold is refused")
        void bindingPointedAtAnotherDataStoreIsRefused() {
            Map<String, byte[]> entries = DeedDocuments.packageOf(
                DeedDocuments.documentBinding(DeedDocuments.STORE, elementsOf(DeedTemplate.MINOR)),
                "{97E651D3-4026-41A5-9FD2-30805E1EDFD3}");

            // The failure Task 1 warned is silent: Word finds no data store, every control resolves to empty,
            // and the applicant gets a deed with their own details missing.
            assertThatThrownBy(() -> generator.generateFrom(completeCase(), DeedTemplate.MINOR, entries))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("binds the data store");
        }

        @Test
        @DisplayName("a template that has stopped binding a field is refused rather than printing one fact less")
        void templateThatHasStoppedBindingAFieldIsRefused() {
            Map<String, byte[]> entries = DeedDocuments.packageOf(
                DeedDocuments.documentBinding(DeedDocuments.STORE, List.of("deedpoll_newforename")),
                DeedDocuments.STORE);

            assertThatThrownBy(() -> generator.generateFrom(completeCase(), DeedTemplate.ADULT, entries))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not bind");
        }

        @Test
        @DisplayName("a template binding the other deed's field is refused")
        void templateBindingTheOtherDeedsFieldIsRefused() {
            List<String> elements = new ArrayList<>(elementsOf(DeedTemplate.MINOR));
            elements.add("deedpoll_status");
            Map<String, byte[]> entries = DeedDocuments.packageOf(
                DeedDocuments.documentBinding(DeedDocuments.STORE, elements), DeedDocuments.STORE);

            assertThatThrownBy(() -> generator.generateFrom(completeCase(), DeedTemplate.MINOR, entries))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("belongs to the other deed");
        }

        @Test
        @DisplayName("a package missing a part the generator reads is refused by name")
        void packageMissingAPartIsRefusedByName() {
            Map<String, byte[]> entries = new LinkedHashMap<>(DeedDocuments.packageOf(
                DeedDocuments.documentBinding(DeedDocuments.STORE, elementsOf(DeedTemplate.MINOR)),
                DeedDocuments.STORE));
            entries.remove(ITEM_PROPS);

            assertThatThrownBy(() -> generator.generateFrom(completeCase(), DeedTemplate.MINOR, entries))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOC022 has no customXml/itemProps1.xml");
        }

        private List<String> elementsOf(DeedTemplate template) {
            return DeedField.fieldsFor(template).stream().map(DeedField::element).toList();
        }
    }

    @Nested
    @DisplayName("values that are not plain text")
    class Escaping {

        @ParameterizedTest
        @EnumSource(DeedTemplate.class)
        @DisplayName("a value with XML markup in it survives both halves unchanged")
        void valueWithMarkupSurvivesBothHalves(DeedTemplate template) {
            DeedPoll deedPoll = completeCase();
            deedPoll.setNewSurname("British & <Commonwealth>");
            deedPoll.setOldForename("O'Brien \"Paddy\"");

            Map<String, byte[]> entries = DeedPackages.entries(generator.generate(deedPoll, template).content());
            Document document = DeedPackages.parse(DeedPackages.entry(entries, DOCUMENT_PART));
            Document dataPart = DeedPackages.parse(DeedPackages.entry(entries, DATA_PART));

            // Unescaped, either half would be a package Word refuses to open -- which is why one escaper is
            // shared rather than each half having its own opinion.
            assertThat(DeedPackages.cachedText(document))
                .containsEntry("deedpoll_newsurname", "British & <Commonwealth>")
                .containsEntry("deedpoll_oldforename", "O'Brien \"Paddy\"");
            for (Element binding : DeedPackages.bindings(document)) {
                if ("deedpoll_newsurname".equals(DeedPackages.element(binding))) {
                    assertThat(DeedPackages.resolveBinding(binding, dataPart))
                        .isEqualTo("British & <Commonwealth>");
                }
            }
        }
    }

    private Document documentPartOf(DeedTemplate template) {
        return DeedPackages.parse(DeedPackages.entry(
            DeedPackages.entries(generator.generate(completeCase(), template).content()), DOCUMENT_PART));
    }

    private List<String> renderedText(DeedTemplate template) {
        return DeedPackages.textRuns(documentPartOf(template)).stream().map(String::trim).toList();
    }

    private static String itemIdOf(byte[] itemProps) {
        String xml = new String(itemProps, StandardCharsets.UTF_8);
        int at = xml.indexOf("ds:itemID=\"") + "ds:itemID=\"".length();
        return xml.substring(at, xml.indexOf('"', at));
    }

    /** The expected value of every element the template binds, written out rather than derived from the code. */
    private static Map<String, String> expectedFor(DeedTemplate template) {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("deedpoll_referencenumber", "KBDP2026-00001");
        expected.put("deedpoll_date", "9 March 2026");
        expected.put("deedpoll_newforename", "Robin");
        expected.put("deedpoll_newmiddlename", "Ash");
        expected.put("deedpoll_newsurname", "Okonkwo-Hart");
        expected.put("deedpoll_oldforename", "Robert");
        expected.put("deedpoll_oldmiddlename", "John");
        expected.put("deedpoll_oldsurname", "Smith");
        expected.put("deedpoll_nationality", "British Citizen");
        expected.put("deedpoll_section", "Section 1");
        expected.put("deedpoll_sectionother", "the 1981 Act as amended");
        if (template == DeedTemplate.ADULT) {
            expected.put("deedpoll_applicantbuildingnoname", "Flat 4");
            expected.put("deedpoll_applicantaddress1", "12 Gray's Inn Road");
            expected.put("deedpoll_applicantaddress2", "Holborn");
            expected.put("deedpoll_applicanttowncity", "London");
            expected.put("deedpoll_applicantpostcode", "WC1X 8HN");
            expected.put("deedpoll_applicantemail", "robin@example.com");
            expected.put("deedpoll_status", "Married / Civil Partnership");
        } else {
            expected.put("deedpoll_ageofminor", "9");
            expected.put("deedpoll_parents", "Robert Smith and Ada Smith");
        }
        return expected;
    }

    private static Set<String> shared() {
        return Set.of("deedpoll_referencenumber", "deedpoll_date", "deedpoll_newforename",
            "deedpoll_newmiddlename", "deedpoll_newsurname", "deedpoll_oldforename", "deedpoll_oldmiddlename",
            "deedpoll_oldsurname", "deedpoll_nationality", "deedpoll_section", "deedpoll_sectionother");
    }

    private static Set<String> adultOnly() {
        return Set.of("deedpoll_applicantbuildingnoname", "deedpoll_applicantaddress1",
            "deedpoll_applicantaddress2", "deedpoll_applicanttowncity", "deedpoll_applicantpostcode",
            "deedpoll_applicantemail", "deedpoll_status");
    }

    private static Set<String> minorOnly() {
        return Set.of("deedpoll_ageofminor", "deedpoll_parents");
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return Set.copyOf(all);
    }

    /** The one child element of a part's root, so nothing depends on how a DOM treats the empty namespace. */
    private static Element onlyChildElement(Element parent) {
        List<Element> children = childElements(parent);
        assertThat(children).hasSize(1);
        return children.get(0);
    }

    private static List<Element> childElements(Element parent) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE) {
                children.add((Element) nodes.item(i));
            }
        }
        return children;
    }

    /**
     * A case with every bound column set, so a value missing from the output is the generator's fault.
     *
     * <p>Unsaved and unpersisted: the generator reads getters and writes a file, so nothing here needs a
     * database, an id or an audit column.
     */
    private static DeedPoll completeCase() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDisplayReference("KBDP2026-00001");
        // dateReceived rather than createdOn -- the business date, which survives migration, and the one the
        // reference number on the same deed takes its year from.
        deedPoll.setDateReceived(LocalDateTime.of(2026, 3, 9, 11, 30));
        deedPoll.setNewForename("Robin");
        deedPoll.setNewMiddleName("Ash");
        deedPoll.setNewSurname("Okonkwo-Hart");
        deedPoll.setOldForename("Robert");
        deedPoll.setOldMiddleName("John");
        deedPoll.setOldSurname("Smith");
        deedPoll.setApplicantBuildingNoName("Flat 4");
        deedPoll.setApplicantAddress1("12 Gray's Inn Road");
        deedPoll.setApplicantAddress2("Holborn");
        deedPoll.setApplicantTownCity("London");
        deedPoll.setApplicantPostcode("WC1X 8HN");
        deedPoll.setApplicantEmail("robin@example.com");
        deedPoll.setMaritalStatus(MaritalStatus.MarriedCivilPartnership);
        deedPoll.setNationality(Nationality.BritishCitizen);
        deedPoll.setNationalitySection(NationalitySection.Section1);
        deedPoll.setNationalitySectionOther("the 1981 Act as amended");
        deedPoll.setAgeOfMinor(9);
        deedPoll.setParents("Robert Smith and Ada Smith");
        return deedPoll;
    }
}
