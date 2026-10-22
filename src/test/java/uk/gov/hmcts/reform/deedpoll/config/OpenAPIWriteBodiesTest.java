package uk.gov.hmcts.reform.deedpoll.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.storage.DocumentStorageRule;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAPIWriteBodiesTest {

    private final OpenAPIWriteBodies underTest = new OpenAPIWriteBodies();

    @Test
    void shouldDescribeWriteBodies() {
        OpenAPI openApi = openApiWithWriteSchemas();

        underTest.writeBodyCustomizer().customise(openApi);

        Schema<?> createCase = schema(openApi, "CreateCaseRequest");
        assertThat(createCase.getType()).isEqualTo("object");
        assertThat(createCase.getProperties()).isNotEmpty();
        assertThat(createCase.getAdditionalProperties()).isEqualTo(true);
        for (String body : new String[] {"CreateCaseRequest", "UpdateCaseRequest"}) {
            for (String narrative : new String[] {"returnReason", "referralNotes", "masterDirections"}) {
                Schema<?> field = schema(openApi, body).getProperties().get(narrative);
                assertThat(field.getMaxLength()).as("%s.%s", body, narrative).isEqualTo(2000);
                assertThat(field.getDescription()).as("%s.%s", body, narrative)
                    .contains("line break counts as one character");
            }
            assertThat(schema(openApi, body).getProperties().get("masterName").getDescription())
                .as("the line-break note is the narrative fields' alone")
                .isNull();
        }

        Schema<?> createNote = schema(openApi, "CreateNoteRequest");
        assertThat(createNote.getProperties()).containsKey("note");
        assertThat(createNote.getRequired()).contains("note");

        Schema<?> updateNote = schema(openApi, "UpdateNoteRequest");
        assertThat(updateNote.getProperties()).containsKey("note");

        Schema<?> createWitness = schema(openApi, "CreateWitnessRequest");
        assertThat(createWitness.getRequired())
            .containsExactlyInAnyOrder(
                "witnessName",
                "witnessAddress",
                "witnessOccupation",
                "declarant"
            );

        Schema<?> createSavedQuery = schema(openApi, "CreateSavedQueryRequest");
        assertThat(createSavedQuery.getRequired()).contains("name");

        Schema<?> referral = schema(openApi, "CreateReferralRequest");
        assertThat(referral.getRequired()).contains("reasons");

        Schema<?> direction = schema(openApi, "JudicialDirectionRequest");
        assertThat(direction.getRequired())
            .containsExactlyInAnyOrder("decision", "publishToGazette");

        Schema<?> previewEmail = schema(openApi, "PreviewEmailRequest");
        assertThat(previewEmail.getAdditionalProperties()).isEqualTo(false);

        Operation upload =
            openApi.getPaths()
                .get("/caseworker/cases/{id}/documents")
                .getPost();

        assertThat(upload.getRequestBody()).isNotNull();
        assertThat(upload.getRequestBody().getRequired()).isTrue();
        assertThat(upload.getRequestBody().getContent())
            .containsKey("multipart/form-data");
        assertThat(upload.getResponses().get("409").getDescription())
            .isEqualTo(DocumentStorageRule.CASE_NOT_READY);
    }

    private OpenAPI openApiWithWriteSchemas() {
        Components components = new Components();

        for (String name : new String[] {
            "CreateCaseRequest",
            "UpdateCaseRequest",
            "CreateNoteRequest",
            "UpdateNoteRequest",
            "CreateWitnessRequest",
            "UpdateWitnessRequest",
            "CreateSavedQueryRequest",
            "UpdateSavedQueryRequest",
            "CreateReferralRequest",
            "JudicialDirectionRequest",
            "PreviewEmailRequest",
            "SendEmailRequest"
        }) {
            components.addSchemas(name, new Schema<>());
        }

        Paths paths = new Paths();
        paths.addPathItem(
            "/caseworker/cases/{id}/documents",
            new PathItem().post(new Operation())
        );

        return new OpenAPI()
            .components(components)
            .paths(paths);
    }

    private Schema<?> schema(OpenAPI openApi, String name) {
        return openApi.getComponents().getSchemas().get(name);
    }

    @Test
    void shouldIgnoreMissingSchemasAndPaths() {
        OpenAPI openApi = new OpenAPI();

        underTest.writeBodyCustomizer().customise(openApi);

        assertThat(openApi.getComponents()).isNull();
        assertThat(openApi.getPaths()).isNull();
    }

    @Test
    void shouldPublishEmailBodiesWithoutAdditionalProperties() {
        OpenAPI openApi = openApiWithWriteSchemas();

        underTest.writeBodyCustomizer().customise(openApi);

        assertThat(schema(openApi, "PreviewEmailRequest").getAdditionalProperties())
            .isEqualTo(false);
        assertThat(schema(openApi, "SendEmailRequest").getAdditionalProperties())
            .isEqualTo(false);
    }

    @Test
    void shouldDescribeUploadAsMultipartFormData() {
        OpenAPI openApi = openApiWithWriteSchemas();

        underTest.writeBodyCustomizer().customise(openApi);

        Schema<?> form = openApi.getPaths()
            .get("/caseworker/cases/{id}/documents")
            .getPost()
            .getRequestBody()
            .getContent()
            .get("multipart/form-data")
            .getSchema();

        assertThat(form.getProperties())
            .containsKeys("file", "documentTypeId", "name", "direction");
        assertThat(form.getRequired()).contains("documentTypeId");
    }
}
