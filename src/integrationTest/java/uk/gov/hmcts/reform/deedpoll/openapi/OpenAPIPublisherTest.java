package uk.gov.hmcts.reform.deedpoll.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.hmcts.reform.deedpoll.api.KeyedRequestBody;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseSearch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseSort;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollSchemas;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.QueryOperator;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralReason;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralSchemas;
import uk.gov.hmcts.reform.deedpoll.domain.witnesses.WitnessSchemas;

import java.io.OutputStream;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Built-in feature which saves service's swagger specs in temporary directory.
 * Each CI run on master should automatically save and upload (if updated) documentation.
 *
 * <p>The path below is the contract with the pipeline, not a choice: the HMCTS Jenkins library reads
 * {@code /tmp/openapi-specs.json} after the integration stage and publishes it to {@code hmcts/cnp-api-docs}.
 * It is the template's own wiring, kept verbatim -- {@code pcs-api} keeps the same file -- so that it works when
 * the pipeline is onboarded rather than needing to be discovered then.
 *
 * <p><strong>What the spec covers is asserted, not assumed.</strong> The template's version only checked that
 * the document was served, which stays green while it describes nothing: {@code springdoc.packagesToScan} named
 * the template's own controller package for the whole of tasks 13 to 15, so a spec claiming to describe this
 * service omitted all twelve caseworker routes and said so in valid JSON. A published document that is silently
 * empty is worse than no document, because a consumer trusts it.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class OpenAPIPublisherTest extends AbstractPostgresContainerTest {

    /** Where the Jenkins library looks for it. */
    private static final Path PUBLISHED_SPEC = Paths.get("/tmp/openapi-specs.json");

    /**
     * Every route the caseworker surface has, as plan 01's tasks 13 to 15 enumerate them, plus the witness routes
     * added afterwards, the saved-query routes after those and the referral routes after those. Listed rather
     * than counted, because a count agrees with a spec that documents the right number of the wrong things.
     */
    private static final List<String> CASEWORKER_PATHS = List.of(
        "/caseworker/cases",
        "/caseworker/cases/{id}",
        "/caseworker/cases/field-rules",
        "/caseworker/document-types",
        "/caseworker/cases/{id}/notes",
        "/caseworker/cases/{id}/notes/{noteId}",
        "/caseworker/cases/{id}/witnesses",
        "/caseworker/cases/{id}/witnesses/{witnessId}",
        "/caseworker/cases/{id}/documents",
        "/caseworker/cases/{id}/documents/{documentId}/file",
        "/caseworker/saved-queries",
        "/caseworker/saved-queries/{id}",
        "/caseworker/saved-queries/{id}/selection",
        "/caseworker/cases/{id}/referrals",
        "/caseworker/cases/{id}/referrals/{referralId}",
        "/caseworker/cases/{id}/referrals/{referralId}/withdrawal"
    );

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @DisplayName("Generate swagger documentation")
    @Test
    void generateDocs() throws Exception {
        byte[] specs = mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

        try (OutputStream outputStream = Files.newOutputStream(PUBLISHED_SPEC)) {
            outputStream.write(specs);
        }

        assertThat(PUBLISHED_SPEC).exists();

        JsonNode paths = JSON.readTree(specs).get("paths");
        assertThat(paths).isNotNull();
        assertThat(paths.fieldNames()).toIterable().containsAll(CASEWORKER_PATHS);
    }

    @DisplayName("Documents the twenty-nine caseworker operations and the token they need")
    @Test
    void documentsTheCaseworkerOperations() throws Exception {
        JsonNode spec = JSON.readTree(mvc.perform(get("/v3/api-docs"))
                                          .andExpect(status().isOk())
                                          .andReturn()
                                          .getResponse()
                                          .getContentAsByteArray());

        JsonNode paths = spec.get("paths");
        // Twenty-nine operations across sixteen paths: `/caseworker/cases`, the notes and witnesses collections,
        // the saved-query collection and the referrals collection each carry a GET and a POST, the case carries a
        // GET and a PATCH, the note, the witness and the saved query each carry a GET, a PATCH and a DELETE, a
        // saved query's selection carries a PUT and a DELETE, one referral carries a GET, and its withdrawal
        // carries a POST -- the note's delete since 2026-09-08, the witness's since the Administrator gate
        // landed, the saved query's since the library did and the selection's with it, and the four referral
        // routes with the referrals feature.
        // Both the paths and the count are asserted because neither alone is enough: a count could not tell a new
        // verb on a known path from a new path, and the path list could not tell a route that was removed from one
        // that never existed.
        long operations = CASEWORKER_PATHS.stream()
            .mapToLong(path -> paths.get(path).size())
            .sum();
        assertThat(operations).isEqualTo(29);

        // Every one of them needs a forwarded IDAM token, which the document has to say or the client generated
        // from it cannot make a call. Declared once, globally, because the service authenticates by default.
        assertThat(spec.at("/components/securitySchemes/bearerAuth/scheme").asText()).isEqualTo("bearer");
        assertThat(spec.at("/security/0/bearerAuth").isArray()).isTrue();

        // The exception, and the only one: Azure's Always-On probe has no token.
        assertThat(spec.at("/paths/~1/get/security").isArray()).isTrue();
        assertThat(spec.at("/paths/~1/get/security").isEmpty()).isTrue();
    }

    /**
     * A property's declared types, however the document spells them.
     *
     * <p>OpenAPI 3.1 allows {@code "type": "string"} and {@code "type": ["string", "null"]} for the same field
     * depending on whether it accepts a null, so a caller asserting a type has to accept both forms or it is
     * really asserting nullability by accident.
     */
    private static List<String> typesOf(JsonNode property) {
        JsonNode type = property.get("type");
        if (type == null) {
            return List.of();
        }
        return type.isArray()
            ? StreamSupport.stream(type.spliterator(), false).map(JsonNode::asText).toList()
            : List.of(type.asText());
    }

    /** Whether an enum list offers a JSON null, which is what permits a patch to clear the field. */
    private static boolean offersNullMember(JsonNode enumList) {
        return StreamSupport.stream(enumList.spliterator(), false).anyMatch(JsonNode::isNull);
    }

    /**
     * The seven operations that take a body, asserted to actually describe one.
     *
     * <p>The same argument as the path assertions above, one level in. Every write body in this service binds as a
     * keyed map rather than as declared components -- see {@code KeyedRequestBody} for why it has to -- so
     * springdoc has no fields to reflect over and published {@code CreateCaseRequest: {}} for each of the JSON
     * writes, and no request body at all for the multipart upload. Valid JSON describing nothing, which a
     * consumer generating a client cannot distinguish from a body that genuinely takes no fields.
     * {@link uk.gov.hmcts.reform.deedpoll.config.OpenAPIWriteBodies} fills them in from the domain's own tables;
     * this pins that they stay filled in.
     */
    @DisplayName("Describes the body of every operation that takes one")
    @Test
    void documentsTheWriteBodies() throws Exception {
        JsonNode spec = JSON.readTree(mvc.perform(get("/v3/api-docs"))
                                          .andExpect(status().isOk())
                                          .andReturn()
                                          .getResponse()
                                          .getContentAsByteArray());

        // The six JSON bodies, each named by the operation that takes it.
        for (String body : List.of("CreateCaseRequest", "UpdateCaseRequest", "CreateNoteRequest",
                                   "UpdateNoteRequest", "CreateWitnessRequest", "UpdateWitnessRequest")) {
            JsonNode properties = spec.at("/components/schemas/" + body + "/properties");
            assertThat(properties.isObject())
                .as("%s declares its properties rather than publishing an empty schema", body)
                .isTrue();
            assertThat(properties.size()).as("%s has at least one property", body).isPositive();
        }

        // A case body describes every writable field, so the count tracks the schema rather than a number here.
        int writableFields = DeedPollSchemas.writeBodyFields().size();
        assertThat(spec.at("/components/schemas/CreateCaseRequest/properties").size())
            .isEqualTo(writableFields);
        assertThat(spec.at("/components/schemas/UpdateCaseRequest/properties").size())
            .isEqualTo(writableFields);

        // Spot-checks that the types travelled, one of each kind rather than all sixty-seven: a text field with
        // its cap, an enum with its members, a date, a boolean, a decimal and a multiselect.
        String create = "/components/schemas/CreateCaseRequest/properties/";
        assertThat(typesOf(spec.at(create + "newForename"))).contains("string");
        assertThat(spec.at(create + "newForename/maxLength").asInt()).isEqualTo(1000);
        assertThat(spec.at(create + "type/enum").isArray()).isTrue();
        assertThat(spec.at(create + "dateReceived/format").asText()).isEqualTo("date-time");
        assertThat(typesOf(spec.at(create + "migratedRecord"))).contains("boolean");
        assertThat(typesOf(spec.at(create + "refundAmount"))).contains("number");
        assertThat(typesOf(spec.at(create + "iShall"))).contains("array");

        // Which fields a patch can clear, which is the distinction the keyed body exists to carry. This document
        // is OpenAPI 3.1, where nullability is a type union and the 3.0 `nullable: true` flag is dropped on
        // serialisation -- so asserting the union is also what stops that flag being used by mistake and
        // published as nothing at all.
        assertThat(typesOf(spec.at(create + "masterName")))
            .as("a nullable field offers null in its type union")
            .contains("string", "null");
        // And the other side of it: a column that refuses a null does not offer one, or a client would be told it
        // could clear a field the service would then 400 on.
        assertThat(typesOf(spec.at(create + "migratedRecord")))
            .as("a non-nullable field offers no null")
            .doesNotContain("null");

        // A nullable ENUM has to say it twice, and this is the assertion that was missing. `enum` constrains the
        // value to its members independently of the type union, so a list of members without null describes a
        // field a validating client cannot clear even though the union next to it says it can -- a document that
        // contradicts itself, and refuses the clear the service accepts. Asserted on a nullable enum rather than
        // on masterName, because the non-enum case above cannot fail this way.
        // Asserted as a JSON null rather than as the text "null", which is a member name a client would send as
        // a string and would not clear anything.
        assertThat(spec.at(create + "type/enum").isArray()).isTrue();
        assertThat(offersNullMember(spec.at(create + "type/enum")))
            .as("a nullable enum lists null among its members, not only in its type union")
            .isTrue();
        assertThat(typesOf(spec.at(create + "type"))).contains("string", "null");
        // The mirror: an enum whose column refuses a null lists only real members.
        assertThat(offersNullMember(spec.at(create + "privacy/enum")))
            .as("a non-nullable enum offers no null member")
            .isFalse();

        // The note body's one field, required on create and optional on patch -- the distinction a client needs.
        assertThat(typesOf(spec.at("/components/schemas/CreateNoteRequest/properties/note"))).contains("string");
        assertThat(spec.at("/components/schemas/CreateNoteRequest/required/0").asText()).isEqualTo("note");
        assertThat(spec.at("/components/schemas/UpdateNoteRequest/required").isMissingNode()).isTrue();

        // The witness body's four fields, with the caps the schema enforces rather than numbers a client would
        // discover by being refused. All four required on create -- every column is NOT NULL -- and none on the
        // patch, which is the whole difference between the two bodies.
        String witness = "/components/schemas/CreateWitnessRequest/properties/";
        assertThat(spec.at(witness + "witnessName/maxLength").asInt())
            .isEqualTo(WitnessSchemas.MAX_WITNESS_NAME_LENGTH);
        assertThat(spec.at(witness + "witnessAddress/maxLength").asInt())
            .isEqualTo(WitnessSchemas.MAX_WITNESS_ADDRESS_LENGTH);
        assertThat(spec.at(witness + "witnessOccupation/maxLength").asInt())
            .isEqualTo(WitnessSchemas.MAX_WITNESS_OCCUPATION_LENGTH);
        // A boolean and not a string: the schema refuses "true" rather than coercing it, and a document that
        // called this a string would have clients sending the one value that coerces wrongly.
        assertThat(typesOf(spec.at(witness + "declarant"))).contains("boolean");
        assertThat(spec.at("/components/schemas/CreateWitnessRequest/required").size()).isEqualTo(4);
        assertThat(spec.at("/components/schemas/UpdateWitnessRequest/required").isMissingNode()).isTrue();
        // And no null offered anywhere on the patch, because an explicit null is a 400 rather than a clear.
        assertThat(typesOf(spec.at("/components/schemas/UpdateWitnessRequest/properties/witnessName")))
            .as("a witness field cannot be cleared, so the document does not offer a null")
            .doesNotContain("null");

        // The referral body, whose two caps are the ones that keep a referral from being stored and then refused
        // by the case note it writes -- so they are asserted to be ReferralSchemas' own numbers rather than
        // whatever the document happens to say. reasons is the only outright requirement; notes and urgencyReason
        // are required conditionally, which OpenAPI cannot express and the description carries in prose.
        String referral = "/components/schemas/CreateReferralRequest/properties/";
        assertThat(spec.at(referral + "notes/maxLength").asInt()).isEqualTo(ReferralSchemas.MAX_NOTES_LENGTH);
        assertThat(spec.at(referral + "urgencyReason/maxLength").asInt())
            .isEqualTo(ReferralSchemas.MAX_URGENCY_REASON_LENGTH);
        assertThat(typesOf(spec.at(referral + "reasons"))).contains("array");
        assertThat(spec.at(referral + "reasons/minItems").asInt()).isEqualTo(1);
        // The vocabulary reaches the document from the enum that validates it, so the two cannot drift.
        List<String> publishedReasons = StreamSupport
            .stream(spec.at(referral + "reasons/items/enum").spliterator(), false)
            .map(JsonNode::asText)
            .toList();
        assertThat(publishedReasons)
            .containsExactlyElementsOf(Arrays.stream(ReferralReason.values()).map(Enum::name).toList());
        assertThat(spec.at("/components/schemas/CreateReferralRequest/required").size()).isEqualTo(1);
        assertThat(spec.at("/components/schemas/CreateReferralRequest/required/0").asText()).isEqualTo("reasons");
        // And no update body, because a referral is not edited in place: amending one is withdrawing it and
        // raising another. Asserted rather than left implicit, since the tidy-minded addition would be a PATCH.
        assertThat(spec.at("/components/schemas/UpdateReferralRequest").isMissingNode())
            .as("there is no referral patch body, because there is no referral patch")
            .isTrue();

        // The upload, which had no body at all: the media type is as load-bearing as the fields, because a client
        // that posts JSON to it fails whatever field names it uses.
        JsonNode uploadForm = spec.at(
            "/paths/~1caseworker~1cases~1{id}~1documents/post/requestBody/content/multipart~1form-data/schema");
        assertThat(uploadForm.isObject()).as("the upload declares a multipart/form-data body").isTrue();
        assertThat(uploadForm.at("/properties/file/format").asText()).isEqualTo("binary");
        assertThat(uploadForm.at("/properties/documentTypeId").isObject()).isTrue();
        assertThat(uploadForm.at("/properties/name").isObject()).isTrue();
        assertThat(uploadForm.at("/properties/direction/enum").isArray()).isTrue();
        assertThat(uploadForm.at("/required/0").asText()).isEqualTo("documentTypeId");
    }

    /**
     * The case list's four query parameters, asserted to be described rather than reflected.
     *
     * <p>The same argument as the write bodies, on the way in instead of the way out. The list binds its
     * parameters from a {@code MultiValueMap} because a repeated {@code where} cannot arrive any other way, and
     * springdoc reflects that into one required parameter literally named {@code parameters} -- a document that
     * says a bare list request is a 400 and never mentions {@code where}, {@code q}, {@code sort}, {@code page} or
     * {@code size}. {@link uk.gov.hmcts.reform.deedpoll.config.OpenAPIQueryParameters} replaces it; this pins
     * that it stays replaced, because {@code deed-poll-admin} generates its client from this document and the
     * reflected form is one a client cannot call the operation with at all.
     */
    @DisplayName("Describes the case list's query parameters rather than the map they bind from")
    @Test
    void documentsTheCaseListQueryParameters() throws Exception {
        JsonNode spec = JSON.readTree(mvc.perform(get("/v3/api-docs"))
                                          .andExpect(status().isOk())
                                          .andReturn()
                                          .getResponse()
                                          .getContentAsByteArray());

        JsonNode parameters = spec.at("/paths/~1caseworker~1cases/get/parameters");
        assertThat(parameters.isArray()).isTrue();

        Map<String, JsonNode> byName = new LinkedHashMap<>();
        parameters.forEach(parameter -> byName.put(parameter.at("/name").asText(), parameter));

        // The binding mechanism is not part of the surface, and neither is the component it was typed with.
        assertThat(byName).as("the reflected map parameter is gone").doesNotContainKey("parameters");
        assertThat(spec.at("/components/schemas/MultiValueMapStringString").isMissingNode())
            .as("and so is the schema springdoc generated for it")
            .isTrue();

        assertThat(byName.keySet()).containsExactlyInAnyOrder("filter", "where", "q", "sort", "page", "size");

        // Not one of them is required: a bare list is the service's front page, and the reflected document said
        // otherwise. This is the assertion that would have caught the published form.
        byName.forEach((name, parameter) ->
                           assertThat(parameter.at("/required").asBoolean())
                               .as("%s is optional", name)
                               .isFalse());

        // `where` repeats rather than taking one comma-separated value, and `explode` on an array is what says
        // so. A client that joined conditions with commas would send one unparseable entry, and a comma is
        // already meaningful inside a `between` or `isOneOf` value.
        assertThat(byName.get("where").at("/schema/type").asText()).isEqualTo("array");
        assertThat(byName.get("where").at("/explode").asBoolean()).isTrue();

        // The paging defaults are the domain's constants, so the document tracks CasePaging rather than a number
        // written here -- and no `maximum`, because an over-cap size is clamped and answered, not refused.
        assertThat(byName.get("page").at("/schema/default").asInt()).isEqualTo(CasePaging.FIRST_PAGE);
        assertThat(byName.get("size").at("/schema/default").asInt()).isEqualTo(CasePaging.DEFAULT_SIZE);
        assertThat(byName.get("size").at("/schema/maximum").isMissingNode())
            .as("a clamped cap is described in prose, not as a constraint a client would enforce")
            .isTrue();
        assertThat(byName.get("size").at("/description").asText())
            .contains(String.valueOf(CasePaging.MAX_SIZE));

        // The operator table reaches the document from the enum that validates it, so the two cannot drift.
        String where = byName.get("where").at("/description").asText();
        for (QueryOperator operator : QueryOperator.values()) {
            assertThat(where).as("the document lists the %s operator", operator.wire()).contains(operator.wire());
        }

        // And the sortable fields likewise, from CaseSort rather than restated.
        String sort = byName.get("sort").at("/description").asText();
        CaseSort.sortableFields()
            .forEach(field -> assertThat(sort).as("the document lists %s as sortable", field).contains(field));

        // The searched columns come from the matcher for the same reason, and the description has to say the
        // matching is not fuzzy: a consumer that promised typo tolerance would be promising for the service.
        String search = byName.get("q").at("/description").asText();
        CaseSearch.NAME_FIELDS
            .forEach(field -> assertThat(search).as("the document lists %s as searched", field).contains(field));
        assertThat(search).contains("not fuzzy");
    }

    /**
     * Every keyed write body publishes its fields, rather than the empty schema springdoc would reflect.
     *
     * <p><strong>This exists because the saved-query bodies shipped empty.</strong> Both are
     * {@code KeyedRequestBody} subclasses -- classes with no fields, deliberately, because a patch turns on the
     * difference between an omitted field and an explicit null -- so springdoc published
     * {@code CreateSavedQueryRequest: {}}. {@code OpenAPIWriteBodies} is what compensates and it was not extended
     * when the resource was added, which nothing noticed: the assertions above check paths, operation counts and
     * parameter descriptions, and none of them looks at a request body's properties.
     *
     * <p><strong>Derived from the classpath rather than a list, which is the point.</strong> A list here would
     * need editing for the tenth keyed body exactly as {@code OpenAPIWriteBodies} did for the ninth, and would be
     * forgotten the same way. Scanning for {@code KeyedRequestBody} subclasses means a new one fails this test on
     * the commit that introduces it, before it can reach a consumer -- and an empty published schema is worse than
     * a missing one, because a generated client compiles and then sends nothing.
     */
    @DisplayName("Publishes the fields of every keyed write body, so no consumer sees an empty schema")
    @Test
    void documentsEveryKeyedWriteBody() throws Exception {
        JsonNode schemas = JSON.readTree(mvc.perform(get("/v3/api-docs"))
                                             .andExpect(status().isOk())
                                             .andReturn()
                                             .getResponse()
                                             .getContentAsByteArray())
            .at("/components/schemas");

        List<Class<?>> keyedBodies = keyedRequestBodies();
        assertThat(keyedBodies)
            .as("the scan found the keyed bodies at all -- an empty list would make this test vacuous")
            .isNotEmpty();

        for (Class<?> body : keyedBodies) {
            JsonNode schema = schemas.get(body.getSimpleName());
            assertThat(schema)
                .as("%s is published at all", body.getSimpleName())
                .isNotNull();
            assertThat(schema.at("/properties").isMissingNode())
                .as("%s publishes its fields rather than an empty schema -- add it to OpenAPIWriteBodies",
                    body.getSimpleName())
                .isFalse();
            assertThat(schema.at("/properties").size())
                .as("%s publishes at least one property", body.getSimpleName())
                .isPositive();
        }
    }

    /**
     * Every concrete {@code KeyedRequestBody} subclass under the DTO packages.
     *
     * <p>Spring's own scanner, so this needs no extra dependency. Abstract classes are excluded --
     * {@code CaseWriteRequest} is a shared base and is not itself a body a route binds -- and springdoc names a
     * schema after the concrete class in any case.
     */
    private static List<Class<?>> keyedRequestBodies() {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(KeyedRequestBody.class));
        return scanner.findCandidateComponents("uk.gov.hmcts.reform.deedpoll.api").stream()
            .map(BeanDefinition::getBeanClassName)
            .map(OpenAPIPublisherTest::loaded)
            .filter(type -> !Modifier.isAbstract(type.getModifiers()))
            .toList();
    }

    private static Class<?> loaded(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException impossible) {
            // The scanner found it on this classpath, so it loads.
            throw new IllegalStateException(name, impossible);
        }
    }
}
