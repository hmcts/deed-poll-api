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
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.ListFilters;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.QueryOperator;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralReason;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralSchemas;
import uk.gov.hmcts.reform.deedpoll.domain.witnesses.WitnessSchemas;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ConfirmationOutcome;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.notify.ApplicationRejectedPersonalisation;
import uk.gov.hmcts.reform.deedpoll.notify.EmailSlot;
import uk.gov.hmcts.reform.deedpoll.notify.EmailTemplate;

import java.io.OutputStream;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
     * added afterwards, the saved-query routes after those, the referral routes after those, the closure the
     * referral state machine added, the three email routes the Notify send path added, and the referral's
     * confirmation sub-resource that records the applicant's answer to the email ④ sends. Listed rather than
     * counted, because a count agrees with a spec that documents the right number of the wrong things.
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
        "/caseworker/cases/{id}/referrals/{referralId}/withdrawal",
        "/caseworker/cases/{id}/referrals/{referralId}/closure",
        "/caseworker/cases/{id}/referrals/{referralId}/confirmation",
        "/caseworker/cases/{id}/emails",
        "/caseworker/cases/{id}/emails/{template}",
        "/caseworker/cases/{id}/emails/{template}/preview"
    );

    /**
     * Every route the judicial surface has, and deliberately <strong>not</strong> folded into
     * {@link #CASEWORKER_PATHS}.
     *
     * <p>Asserted the same way and counted separately, because they are a different surface for a different
     * audience: adding them to the caseworker list would make the caseworker count -- a number the README, the
     * resume notes and a package comment all state -- misreport what a caseworker can do. Listed rather than
     * counted for the reason the caseworker paths are, and listed <em>at all</em> so that a judicial route which
     * silently vanished from the spec fails the build exactly as a caseworker one would. {@code deed-poll-admin}
     * generates its client from this document, so an unpublished route is an unbuildable judicial screen.
     */
    private static final List<String> JUDICIAL_PATHS = List.of(
        "/judicial/referrals",
        "/judicial/referrals/mine",
        "/judicial/referrals/{referralId}",
        "/judicial/referrals/{referralId}/claim",
        "/judicial/referrals/{referralId}/direction"
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
        assertThat(paths.fieldNames()).toIterable().containsAll(JUDICIAL_PATHS);
    }

    /**
     * The five judicial operations, published and counted apart from the caseworker surface's thirty-four.
     *
     * <p>Both halves for the reason the caseworker assertion gives: a count cannot tell a new verb on a known
     * path from a new path, and a path list cannot tell a route that was removed from one that never existed.
     *
     * <p><strong>Five operations across five paths</strong> -- the shared pool, this judge's own work and one
     * referral each carry a {@code GET}, and a referral's claim and its direction each carry a {@code POST}.
     * There is no third write and no judicial closure: closing a referral is the office's acknowledgement that
     * the direction has been actioned, so it lives on the caseworker surface and its absence here is the decision
     * rather than an omission.
     *
     * <p><strong>Four until {@code /judicial/referrals/mine} was published</strong>, and this is the one referral
     * follow-up that moves the contract. The number is asserted here rather than only listed because
     * {@code deed-poll-admin} generates its client from this document: an unpublished route is an unbuildable
     * screen, and the personal list exists precisely because a judge who claimed a referral could not find it
     * again.
     */
    @DisplayName("Documents the five judicial operations, separately from the caseworker count")
    @Test
    void documentsTheJudicialOperations() throws Exception {
        JsonNode paths = JSON.readTree(mvc.perform(get("/v3/api-docs"))
                                           .andExpect(status().isOk())
                                           .andReturn()
                                           .getResponse()
                                           .getContentAsByteArray())
            .get("paths");

        assertThat(paths.fieldNames()).toIterable().containsAll(JUDICIAL_PATHS);

        long operations = JUDICIAL_PATHS.stream()
            .mapToLong(path -> paths.get(path).size())
            .sum();
        assertThat(operations).isEqualTo(5);

        // The personal list is a path of its own and not a verb on the pool's, which is what a reader of the
        // count above cannot see: `/judicial/referrals` still carries exactly one operation, so `mine` cannot
        // have been documented by widening the pool's entry.
        assertThat(paths.get("/judicial/referrals").size()).isEqualTo(1);
        assertThat(paths.get("/judicial/referrals/mine").fieldNames()).toIterable().containsExactly("get");

        // And the caseworker count is untouched by their arrival, asserted here as well as above: the judicial
        // routes are a new surface, so the number that describes the old one must not have moved.
        assertThat(CASEWORKER_PATHS).doesNotContainAnyElementsOf(JUDICIAL_PATHS);
    }

    /**
     * Every documented path belongs to a list this class names -- so a route added outside both fails here.
     *
     * <p><strong>Added because the two counts above cannot see an addition.</strong> Each sums the operations of
     * the paths its own list names, so a brand-new path is simply not counted: the caseworker total stays 33, the
     * judicial total stays 5, both assertions pass, and an undocumented route reaches
     * {@code deed-poll-admin}'s generated client without anything failing. Found while adding {@code mine},
     * which had to be written into {@link #JUDICIAL_PATHS} by hand for the count to move at all.
     *
     * <p>{@code "/"} is Spring Boot's welcome route and the one legitimate path outside both surfaces. Adding a
     * name to this set is a deliberate act: a new {@code /caseworker} or {@code /judicial} route belongs in the
     * list for its own namespace, where the count then forces the number to be restated.
     */
    @DisplayName("Documents no path outside the caseworker and judicial surfaces")
    @Test
    void documentsNoUnlistedPath() throws Exception {
        JsonNode paths = JSON.readTree(mvc.perform(get("/v3/api-docs"))
                                           .andExpect(status().isOk())
                                           .andReturn()
                                           .getResponse()
                                           .getContentAsByteArray())
            .get("paths");

        List<String> documented = new ArrayList<>();
        paths.fieldNames().forEachRemaining(documented::add);

        List<String> known = new ArrayList<>(CASEWORKER_PATHS);
        known.addAll(JUDICIAL_PATHS);
        known.add("/");

        assertThat(documented)
            .describedAs("a documented path in neither surface -- add it to the list for its namespace, so that "
                         + "namespace's operation count has to be restated with it")
            .containsExactlyInAnyOrderElementsOf(known);
    }

    /**
     * The personal list's envelope, and the row type it deliberately reuses.
     *
     * <p>Asserted rather than left to the operation count, because the count cannot tell a published route from a
     * published <em>contract</em>: {@code deed-poll-admin} generates its client from this document, so the shape
     * of {@code JudicialMyReferralsResponse} is the shape the judicial dashboard is written against.
     *
     * <p><strong>The row assertion is the security one.</strong> Ownership moved to the token's IDAM subject in
     * this plan's first task, and {@code claimedById} / {@code decidedById} must not be published anywhere: a
     * document that offered a judge's subject would put one in every generated client and every browser network
     * log. {@code JudicialReferralSummary} is asserted to carry exactly the referral fields it carried when it was
     * the pool's row type and nothing else -- no owner id, no case id, no applicant name and no direction text --
     * so a field added to it in either direction fails here.
     */
    @DisplayName("Documents the personal list's envelope, with the pool's row type unchanged")
    @Test
    void documentsThePersonalReferralList() throws Exception {
        JsonNode spec = JSON.readTree(mvc.perform(get("/v3/api-docs"))
                                          .andExpect(status().isOk())
                                          .andReturn()
                                          .getResponse()
                                          .getContentAsByteArray());

        // The operation answers with the new envelope, named: a client generated against `JudicialPoolResponse`
        // here would compile and then describe the pool's complement as the pool. Reached under `*/*`, which is
        // what springdoc publishes for a controller that declares no `produces` -- asserted as the document spells
        // it rather than as the media type the service actually sends.
        assertThat(spec.at("/paths/~1judicial~1referrals~1mine/get/responses/200/content/*~1*/schema/$ref")
                       .asText())
            .isEqualTo("#/components/schemas/JudicialMyReferralsResponse");

        JsonNode envelope = spec.at("/components/schemas/JudicialMyReferralsResponse/properties");
        assertThat(envelope.fieldNames()).toIterable()
            .containsExactlyInAnyOrder("referrals", "page", "size", "total", "totalPages");
        assertThat(envelope.at("/referrals/items/$ref").asText())
            .isEqualTo("#/components/schemas/JudicialReferralSummary");

        // Paging is described the same way the pool's is, from the same constant, so the two judicial lists
        // cannot drift into disagreeing about what a page is.
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        spec.at("/paths/~1judicial~1referrals~1mine/get/parameters")
            .forEach(parameter -> byName.put(parameter.at("/name").asText(), parameter));
        assertThat(byName.keySet()).containsExactlyInAnyOrder("page", "size");
        byName.forEach((name, parameter) ->
                           assertThat(parameter.at("/required").asBoolean())
                               .as("%s is optional, as it is on the pool", name)
                               .isFalse());

        // The row type, unchanged and shared with the pool -- the referral speaking about itself, and no
        // ownership, case or application field however this document might have acquired one.
        JsonNode row = spec.at("/components/schemas/JudicialReferralSummary/properties");
        assertThat(row.fieldNames()).toIterable()
            .containsExactlyInAnyOrder("id", "state", "reasons", "notes", "urgency", "urgencyReason", "createdOn");
        assertThat(spec.at("/paths/~1judicial~1referrals/get/responses/200/content/*~1*/schema/$ref").asText())
            .as("the pool still answers with its own envelope")
            .isEqualTo("#/components/schemas/JudicialPoolResponse");

        // And the subject columns are nowhere in the document at all, asserted over the whole of it rather than
        // field by field: the two names are searched as text, so a schema this test does not know about cannot
        // publish them either.
        String published = spec.toString();
        assertThat(published)
            .as("no schema publishes a judge's IDAM subject")
            .doesNotContain("claimedById")
            .doesNotContain("decidedById");
    }

    @DisplayName("Documents the thirty-four caseworker operations and the token they need")
    @Test
    void documentsTheCaseworkerOperations() throws Exception {
        JsonNode spec = JSON.readTree(mvc.perform(get("/v3/api-docs"))
                                          .andExpect(status().isOk())
                                          .andReturn()
                                          .getResponse()
                                          .getContentAsByteArray());

        JsonNode paths = spec.get("paths");
        // Thirty-four operations across twenty-one paths: `/caseworker/cases`, the notes and witnesses collections,
        // the saved-query collection and the referrals collection each carry a GET and a POST, the case carries a
        // GET and a PATCH, the note, the witness and the saved query each carry a GET, a PATCH and a DELETE, a
        // saved query's selection carries a PUT and a DELETE, one referral carries a GET, and its withdrawal, its
        // closure and its confirmation each carry a POST -- the note's delete since 2026-09-08, the witness's since
        // the Administrator gate landed, the saved query's since the library did and the selection's with it, the
        // four referral routes with the referrals feature, the closure with the referral state machine, and the
        // confirmation with the tracking of the reply the ④ email asks for: recording an answer is its own
        // operation rather than a case edit, and it is idempotent by refusal rather than by PATCH.
        // Three of them are the Email tab's: the case's emails collection carries a GET saying which emails
        // apply, and one email type carries a POST to send it and a POST to preview it. Preview is a POST despite
        // being a read, because the caseworker's slot text is the input and a URL would log a judge's reason.
        // The judge's own operations -- the pool, their own outstanding work, one referral, claiming it and
        // recording a direction -- are deliberately not here: they are routed on the judicial surface rather than
        // this one, so this count stays the caseworker count however that surface grows.
        // Both the paths and the count are asserted because neither alone is enough: a count could not tell a new
        // verb on a known path from a new path, and the path list could not tell a route that was removed from one
        // that never existed.
        long operations = CASEWORKER_PATHS.stream()
            .mapToLong(path -> paths.get(path).size())
            .sum();
        assertThat(operations).isEqualTo(34);

        JsonNode emailResponse = spec.at("/components/schemas/CaseEmailsResponse/properties");
        assertThat(emailResponse.fieldNames()).toIterable()
            .containsExactlyInAnyOrder("availability", "history", "slotDefaults");
        JsonNode history = spec.at("/components/schemas/EmailHistoryResponse/properties");
        assertThat(history.has("state")).isFalse();
        assertThat(history.path("notifyState").path("description").asText())
            .contains("accepted by GOV.UK Notify")
            .contains("does not mean delivered");
        assertThat(history.path("body").path("description").asText())
            .contains("plain text")
            .contains("branded HTML");

        // Every one of them needs a forwarded IDAM token, which the document has to say or the client generated
        // from it cannot make a call. Declared once, globally, because the service authenticates by default.
        assertThat(spec.at("/components/securitySchemes/bearerAuth/scheme").asText()).isEqualTo("bearer");
        assertThat(spec.at("/security/0/bearerAuth").isArray()).isTrue();

        // The exception, and the only one: Azure's Always-On probe has no token.
        assertThat(spec.at("/paths/~1/get/security").isArray()).isTrue();
        assertThat(spec.at("/paths/~1/get/security").isEmpty()).isTrue();
    }

    /**
     * Both emails, named on the token that names one -- and no new operation for the second of them.
     *
     * <p><strong>This is what a second template had to prove, and the two halves are opposite failures.</strong>
     * {@code deed-poll-admin}'s Email tab was written to need no change when a template is added, which is only
     * true if the document names the new template without moving the contract: a template that the document does
     * not mention is one a client can only discover by being 404'd, and a template that arrived as a new path or a
     * new verb would mean the tab had a route to add after all. So the keys are asserted to be there and the
     * caseworker operation count is asserted to be exactly where notify-1 left it.
     *
     * <p>Read out of {@link EmailTemplate} rather than written here, for the reason the operator table and the
     * sortable fields are: a third template appears in the document -- and in this assertion -- without anyone
     * editing either file. {@link uk.gov.hmcts.reform.deedpoll.config.OpenAPIEmailTemplates} is what puts it there.
     */
    @DisplayName("Names both email templates on the token, and adds no operation for the second")
    @Test
    void documentsBothEmailTemplates() throws Exception {
        JsonNode spec = JSON.readTree(mvc.perform(get("/v3/api-docs"))
                                          .andExpect(status().isOk())
                                          .andReturn()
                                          .getResponse()
                                          .getContentAsByteArray());

        String send = "/paths/~1caseworker~1cases~1{id}~1emails~1{template}/post/parameters";
        String preview = "/paths/~1caseworker~1cases~1{id}~1emails~1{template}~1preview/post/parameters";
        for (String operation : List.of(send, preview)) {
            JsonNode token = templateToken(spec.at(operation));
            assertThat(token.isMissingNode()).as("%s declares the template token", operation).isFalse();
            String description = token.at("/description").asText();
            for (EmailTemplate template : EmailTemplate.values()) {
                assertThat(description)
                    .as("%s names the %s email", operation, template.key())
                    .contains(template.key())
                    .contains(template.displayName());
            }
            // A description and deliberately not an `enum`: an enumerated path token becomes a closed union in a
            // generated client, so a fifth template would stop an old client calling the route it already had --
            // and the GET that publishes availability is where a tab is meant to learn what it may send.
            assertThat(token.at("/schema/enum").isMissingNode())
                .as("%s leaves the token an open string, so a later template breaks no generated client",
                    operation)
                .isTrue();
            assertThat(typesOf(token.at("/schema"))).contains("string");
        }

        // And the email surface is unmoved: both emails travel through the operations that were already there, so
        // neither of them added one. The count is the whole caseworker surface, which the confirmation
        // sub-resource did move -- that route is not an email route, and the assertions below are what say the two
        // email paths still carry one operation each.
        JsonNode paths = spec.get("paths");
        assertThat(CASEWORKER_PATHS.stream().mapToLong(path -> paths.get(path).size()).sum()).isEqualTo(34);
        assertThat(paths.get("/caseworker/cases/{id}/emails/{template}").fieldNames()).toIterable()
            .containsExactly("post");
        assertThat(paths.get("/caseworker/cases/{id}/emails").fieldNames()).toIterable().containsExactly("get");

        // The availability list is what carries the second entry at run time, and its row type is unchanged by the
        // arrival of one: the template is a key on the row rather than a field per template, which is the property
        // that let a second email ship with no change to the response shape.
        assertThat(spec.at("/components/schemas/EmailTypeResponse/properties").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("template", "applicable", "reason", "provisioned", "slots");
        assertThat(typesOf(spec.at("/components/schemas/EmailTypeResponse/properties/template")))
            .contains("string");
        assertThat(spec.at("/components/schemas/CaseEmailsResponse/properties/availability/type").asText())
            .isEqualTo("array");
    }

    /**
     * The {@code {template}} path token among an operation's parameters, or a missing node.
     */
    private static JsonNode templateToken(JsonNode parameters) {
        return StreamSupport.stream(parameters.spliterator(), false)
            .filter(parameter -> "template".equals(parameter.at("/name").asText()))
            .findFirst()
            .orElse(JSON.missingNode());
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
     * Every operation that takes a body, asserted to actually describe one -- the judicial direction included.
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

        // The judicial direction: two required fields and one optional, with the cap and the vocabulary taken from
        // what enforces them rather than from whatever the document happens to say.
        String direction = "/components/schemas/JudicialDirectionRequest/properties/";
        assertThat(spec.at(direction + "directionsText/maxLength").asInt())
            .isEqualTo(ReferralSchemas.MAX_DIRECTIONS_LENGTH);
        List<String> publishedDecisions = StreamSupport
            .stream(spec.at(direction + "decision/enum").spliterator(), false)
            .map(JsonNode::asText)
            .toList();
        assertThat(publishedDecisions)
            .containsExactlyElementsOf(Arrays.stream(MasterDecision.values()).map(Enum::name).toList());
        // A boolean and required, which is the pair that matters: publication puts the applicant's former and new
        // names in a public notice, so a document calling this a string would have clients sending "false" -- the
        // one value a truthy read gets wrong, and a disclosure that cannot be taken back.
        assertThat(typesOf(spec.at(direction + "publishToGazette"))).contains("boolean");
        assertThat(spec.at("/components/schemas/JudicialDirectionRequest/required").size()).isEqualTo(2);
        assertThat(StreamSupport
                       .stream(spec.at("/components/schemas/JudicialDirectionRequest/required").spliterator(),
                               false)
                       .map(JsonNode::asText)
                       .toList())
            .containsExactlyInAnyOrder("decision", "publishToGazette");
        // Nothing nullable anywhere on it: a direction is recorded once and complete, so there is no field to
        // clear and no patch that could clear one.
        assertThat(typesOf(spec.at(direction + "decision")))
            .as("a direction cannot be cleared, so the document offers no null")
            .doesNotContain("null");

        // The two email bodies, which carry the slots and nothing else. Both are asserted rather than one, because
        // they are separate types for separate operations and the customiser could describe either alone.
        for (String email : List.of("PreviewEmailRequest", "SendEmailRequest")) {
            String slots = "/components/schemas/" + email + "/properties/";
            assertThat(spec.at(slots + EmailSlot.JUDGES_REASON.placeholder() + "/maxLength").asInt())
                .isEqualTo(ReferralSchemas.MAX_DIRECTIONS_LENGTH);
            assertThat(spec.at(slots + EmailSlot.ADDITIONAL_NOTES.placeholder() + "/maxLength").asInt())
                .isEqualTo(ReferralSchemas.MAX_NOTES_LENGTH);
            // The slots are the whole body: a client must not be told it may send reference_number, which is the
            // one thing this payload exists to refuse.
            assertThat(spec.at("/components/schemas/" + email + "/properties").size())
                .isEqualTo(EmailSlot.values().length);
            assertThat(spec.at(slots + ApplicationRejectedPersonalisation.REFERENCE_NUMBER).isMissingNode())
                .as("%s does not offer a resolved value as a field", email)
                .isTrue();
            // The only body in the document that closes itself, and the only one where the service refuses an
            // unknown key rather than stripping it. Publishing true here would describe an API that quietly
            // ignored an attempt to set the reference an applicant is told.
            assertThat(spec.at("/components/schemas/" + email + "/additionalProperties").asBoolean(true))
                .as("%s refuses unknown keys rather than ignoring them", email)
                .isFalse();
            // Nothing declared required: judges_reason is required of the email and not of the request, because it
            // is pre-populated from the direction -- a client declaring it mandatory would refuse the ordinary
            // case, which is a caseworker sending the judge's words unaltered.
            assertThat(spec.at("/components/schemas/" + email + "/required").isMissingNode())
                .as("%s declares no required field", email)
                .isTrue();
        }

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

        // And the tab keys, which springdoc reflects as a bare optional string: the keys are a list in the domain
        // rather than an enum, so a consumer had no way to learn them from this document at all. deed-poll-admin
        // renders the tab bar from here, which is what makes the waiting list's own key discoverable rather than
        // something to be read out of this repository. Keys and labels both, from ListFilters rather than
        // restated, so a sixth tab is published without this assertion or the customiser being edited.
        String filter = byName.get("filter").at("/description").asText();
        ListFilters.filterTabs().forEach(tab -> assertThat(filter)
            .as("the document lists the %s tab", tab.key())
            .contains(tab.key())
            .contains(tab.label()));
        assertThat(filter)
            .as("and says an unrecognised key falls back rather than being refused")
            .contains(ListFilters.LIST_FILTERS.get(0).key());
    }

    /**
     * The confirmation sub-resource's whole contract: its body, its answer, and the two fields the wait adds to
     * the case list and the case read.
     *
     * <p><strong>Published because a screen is built against it.</strong> Recording the applicant's answer to the
     * ④ email and showing which cases are still waiting is a {@code deed-poll-admin} screen, and that repository
     * generates its client from this document -- so an unpublished field is a screen that cannot be written, and a
     * field published under the wrong name is one written against a call that 400s.
     *
     * <p><strong>The negative halves are the security ones.</strong> {@code confirmationRecordedBy} and
     * {@code confirmationReceivedAt} come from the token and the clock, so the <em>request</em> body must not
     * offer them -- a document that did would have clients sending an author the service strips, which is an
     * attribution a caller believes it set. And the wait is a caseworker field: no judicial schema may carry it,
     * asserted over the whole document as text rather than schema by schema, so a type this test does not know
     * about cannot publish one either.
     */
    @DisplayName("Documents the confirmation body, its answer, and the wait on the list and the case")
    @Test
    void documentsTheConfirmationContract() throws Exception {
        JsonNode spec = JSON.readTree(mvc.perform(get("/v3/api-docs"))
                                          .andExpect(status().isOk())
                                          .andReturn()
                                          .getResponse()
                                          .getContentAsByteArray());

        // The route answers with the referral, which is what lets a client re-render the tab from the response
        // rather than reading the referral again.
        assertThat(spec.at("/paths/~1caseworker~1cases~1{id}~1referrals~1{referralId}~1confirmation"
                               + "/post/responses/200/content/*~1*/schema/$ref").asText())
            .isEqualTo("#/components/schemas/ReferralResponse");

        // The body: the outcome vocabulary from the enum that validates it and the column it is stored in, so the
        // document cannot offer a label Postgres lacks; the note's cap from the schema that enforces it.
        String confirmation = "/components/schemas/RecordConfirmationRequest/properties/";
        List<String> publishedOutcomes = StreamSupport
            .stream(spec.at(confirmation + "outcome/enum").spliterator(), false)
            .map(JsonNode::asText)
            .toList();
        assertThat(publishedOutcomes)
            .containsExactlyElementsOf(Arrays.stream(ConfirmationOutcome.values()).map(Enum::name).toList());
        assertThat(spec.at(confirmation + "note/maxLength").asInt())
            .isEqualTo(ReferralSchemas.MAX_CONFIRMATION_NOTE_LENGTH);

        // The outcome is required and the note is not: the answer is the fact, and a required field whose usual
        // value is empty is one every caller fills in without reading.
        assertThat(StreamSupport
                       .stream(spec.at("/components/schemas/RecordConfirmationRequest/required").spliterator(),
                               false)
                       .map(JsonNode::asText)
                       .toList())
            .containsExactly("outcome");

        // Two fields the body does not offer, and one it never could. The author and the received date are the
        // token's and the clock's, and the document says they are ignored rather than refused -- which is what
        // `additionalProperties: true` next to it means, and why publishing `false` here would describe an API
        // that 400s the body a tidy-minded client sends.
        assertThat(spec.at(confirmation + "confirmationRecordedBy").isMissingNode())
            .as("the body cannot name an author")
            .isTrue();
        assertThat(spec.at(confirmation + "confirmationReceivedAt").isMissingNode())
            .as("the body cannot backdate a reply")
            .isTrue();
        assertThat(spec.at("/components/schemas/RecordConfirmationRequest/additionalProperties").asBoolean(false))
            .as("an unknown key is stripped, as it is on every other keyed body")
            .isTrue();
        String bodyDescription = spec.at("/components/schemas/RecordConfirmationRequest/description").asText();
        assertThat(bodyDescription).contains("confirmationRecordedBy").contains("token").contains("409");

        // The answer: all four columns, because they are what the referrals tab renders -- the date it was asked,
        // what came back, when it was recorded and by whom.
        JsonNode referral = spec.at("/components/schemas/ReferralResponse/properties");
        assertThat(referral.fieldNames()).toIterable()
            .contains("confirmationRequestedAt", "confirmationOutcome", "confirmationReceivedAt",
                      "confirmationRecordedBy");

        // The row says how long and the case says since when, which is the split the two DTOs keep: a day count
        // on the case read would be a number that was true when it was serialised.
        assertThat(spec.at("/components/schemas/CaseSummaryResponse/properties/daysAwaitingConfirmation")
                       .isObject())
            .as("the list row carries the days waited")
            .isTrue();
        assertThat(spec.at("/components/schemas/CaseSummaryResponse/properties/awaitingConfirmationSince")
                       .isMissingNode())
            .as("and not the date, which is the case read's")
            .isTrue();
        // A day count on the row, a date on the case, and both typed: a client that read "43" as a date, or the
        // date as a count, would render a waiting list nobody could act on.
        assertThat(typesOf(spec.at("/components/schemas/CaseSummaryResponse/properties/daysAwaitingConfirmation")))
            .contains("integer");
        for (String caseSchema : List.of("CaseDetailResponse", "SavedCaseResponse")) {
            JsonNode since = spec.at("/components/schemas/" + caseSchema + "/properties/awaitingConfirmationSince");
            assertThat(since.isObject())
                .as("%s publishes the wait beside the state", caseSchema)
                .isTrue();
            assertThat(since.at("/format").asText())
                .as("%s publishes it as a date-time, as it does every other case date", caseSchema)
                .isEqualTo("date-time");
        }

        // And no judicial schema publishes any of it, asserted over the whole document: a judge decided the
        // non-publication request and has no part in what the applicant then chose.
        for (String judicial : List.of("JudicialReferralSummary", "JudicialReferralDetail", "JudicialCaseView")) {
            JsonNode properties = spec.at("/components/schemas/" + judicial + "/properties");
            assertThat(properties.isObject()).as("%s is published at all", judicial).isTrue();
            List<String> names = new ArrayList<>();
            properties.fieldNames().forEachRemaining(names::add);
            assertThat(names)
                .as("%s publishes nothing about the applicant's confirmation", judicial)
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("confirmation"));
        }
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
