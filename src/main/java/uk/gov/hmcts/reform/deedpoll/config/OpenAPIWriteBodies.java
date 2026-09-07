package uk.gov.hmcts.reform.deedpoll.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollSchemas;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteForm;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Describes the five write bodies that springdoc cannot describe by reflection.
 *
 * <p><strong>Why any of this is needed.</strong> Every write payload in this service binds as a keyed map rather
 * than as declared components, because a patch turns on the difference between an omitted field and an explicit
 * null and a record of nullable components cannot express that -- the argument is in {@code KeyedRequestBody}.
 * The consequence is that the four JSON write bodies are classes with no fields, so springdoc reflects over them
 * and publishes {@code CreateCaseRequest: {}}. The upload is worse: it takes a
 * {@code MultipartHttpServletRequest}, which springdoc does not recognise as a body at all, so that operation is
 * published with no request body whatsoever.
 *
 * <p>That matters because of what the document is for. It is published to {@code hmcts/cnp-api-docs} for
 * consumers to generate clients from, and a consumer cannot tell an empty schema from a body that takes no
 * fields. They would get four request types with no properties and an upload they cannot call, with nothing in
 * the document hinting that anything is missing -- which is the failure {@code OpenAPIPublisherTest} was written
 * to prevent for the paths, reaching the bodies instead. A published document that is silently empty is worse
 * than no document, because a consumer trusts it.
 *
 * <p><strong>Generated from the domain's own tables, not restated here.</strong> The case body's fields come from
 * {@link DeedPollSchemas#writeBodyFields()}, which is the table that validates them; the note limit comes from
 * {@link NoteForm#MAX_NOTE_LENGTH}; the upload's direction values come from {@link DocumentDirection}. A field
 * added to the schema therefore appears in the document without anyone editing this file, which is the only way
 * a description of sixty-seven fields stays true.
 */
@Configuration
public class OpenAPIWriteBodies {

    private static final String CREATE_CASE = "CreateCaseRequest";
    private static final String UPDATE_CASE = "UpdateCaseRequest";
    private static final String CREATE_NOTE = "CreateNoteRequest";
    private static final String UPDATE_NOTE = "UpdateNoteRequest";

    private static final String NOTE_FIELD = "note";

    /** The upload route, as it appears in the document's paths. */
    private static final String UPLOAD_PATH = "/caseworker/cases/{id}/documents";

    @Bean
    public OpenApiCustomizer writeBodyCustomizer() {
        return openApi -> {
            describeCaseBodies(openApi);
            describeNoteBodies(openApi);
            describeUploadBody(openApi);
        };
    }

    /**
     * The two case bodies: every writable field, with the type and constraint the schema enforces.
     *
     * <p>{@code additionalProperties} is left true rather than set false, because that is what the service
     * actually does -- an unknown key is stripped, not refused, which is the reference's
     * {@code z.object()} behaviour. Publishing {@code false} would describe a stricter API than exists and make
     * a generated client reject a payload the service would have accepted.
     */
    private void describeCaseBodies(OpenAPI openApi) {
        for (String name : new String[]{CREATE_CASE, UPDATE_CASE}) {
            Schema<?> body = schema(openApi, name);
            if (body == null) {
                continue;
            }
            body.setType("object");
            body.setProperties(null);
            DeedPollSchemas.writeBodyFields().forEach((field, described) ->
                body.addProperty(field, fieldSchema(described)));
            body.setAdditionalProperties(true);
        }
        note(openApi, CREATE_CASE, "Every field is optional: a case is logged incrementally. An omitted field "
            + "is left alone and an explicit null clears the column, where the column is nullable. Unknown keys "
            + "are ignored. dateReceived is needed to allocate a reference number, so a create without it is a "
            + "400 attributed to that field.");
        note(openApi, UPDATE_CASE, "A patch: an omitted field is left alone, an explicit null clears the column "
            + "where the column is nullable, and unknown keys are ignored. referencenumber, displayReference "
            + "and the audit columns are not writable. privacy is honoured only for a caller entitled to set "
            + "it, and state is re-derived from the merged dates.");
    }

    private Schema<?> fieldSchema(DeedPollSchemas.FieldSchema described) {
        Schema<?> field = switch (described.type()) {
            case STRING -> new StringSchema();
            case BOOLEAN -> new BooleanSchema();
            case INTEGER -> new IntegerSchema();
            case NUMBER -> new NumberSchema();
            case DATE -> new StringSchema().format("date-time");
            case INTEGER_ARRAY -> new ArraySchema().items(new IntegerSchema());
        };
        if (described.maxLength() > 0) {
            field.setMaxLength(described.maxLength());
        }
        if (!described.allowedValues().isEmpty()) {
            described.allowedValues().forEach(value -> addEnumValue(field, value));
        }
        if (described.nullable()) {
            // Says which fields an explicit null may be sent for, which is the point of the whole payload shape:
            // without it a client cannot tell which columns a patch is able to clear.
            //
            // Expressed as a type union rather than with setNullable, because springdoc emits OpenAPI 3.1 and
            // 3.1 removed the `nullable` keyword -- setNullable is silently dropped, which is a worse outcome
            // than either alternative because it looks like it worked.
            Set<String> nullable = new LinkedHashSet<>();
            nullable.add(field.getType());
            nullable.add("null");
            field.setTypes(nullable);
        }
        return field;
    }

    @SuppressWarnings("unchecked")
    private void addEnumValue(Schema<?> field, String value) {
        ((Schema<String>) field).addEnumItemObject(value);
    }

    /** The two note bodies: one field, required on create and optional on patch. */
    private void describeNoteBodies(OpenAPI openApi) {
        Schema<?> create = schema(openApi, CREATE_NOTE);
        if (create != null) {
            create.setType("object");
            create.setProperties(null);
            create.addProperty(NOTE_FIELD, noteText());
            create.addRequiredItem(NOTE_FIELD);
            create.setAdditionalProperties(true);
            create.setDescription("The note text. Trimmed before validation, and the trimmed value is what is "
                + "stored. The author comes from the caller's token and the case from the path, so neither can "
                + "be set here. Unknown keys are ignored.");
        }

        Schema<?> update = schema(openApi, UPDATE_NOTE);
        if (update != null) {
            update.setType("object");
            update.setProperties(null);
            update.addProperty(NOTE_FIELD, noteText());
            update.setAdditionalProperties(true);
            // Not required, and explicitly not nullable: the column is NOT NULL, so an explicit null is a 400
            // rather than a clear. An empty patch is accepted and re-stamps modifiedBy.
            update.setDescription("An empty patch is accepted and re-stamps modifiedBy. An explicit null for "
                + "note is a 400, not a clear: the column is NOT NULL. Unknown keys are ignored.");
        }
    }

    private Schema<?> noteText() {
        return new StringSchema().minLength(1).maxLength(NoteForm.MAX_NOTE_LENGTH);
    }

    /**
     * The upload body, which springdoc publishes as nothing at all.
     *
     * <p>Set on the operation rather than as a component, because a multipart body is described by its media
     * type and there is no request class for a component to be named after.
     */
    private void describeUploadBody(OpenAPI openApi) {
        Operation upload = openApi.getPaths() == null || openApi.getPaths().get(UPLOAD_PATH) == null
            ? null
            : openApi.getPaths().get(UPLOAD_PATH).getPost();
        if (upload == null) {
            return;
        }

        ObjectSchema form = new ObjectSchema();
        form.addProperty("file", new StringSchema().format("binary")
            .description("The file. A PDF, image or Word document, within the configured size limit."));
        form.addProperty("documentTypeId", new StringSchema().format("uuid")
            .description("The document type this file is filed under. Must be a type the case's marital status "
                + "and deed poll type allow."));
        form.addProperty("name", new StringSchema()
            .description("An optional display name. Defaults to the uploaded filename, capped to the column."));

        StringSchema direction = new StringSchema();
        direction.setDescription("Whether the document was received or sent. Optional.");
        Arrays.stream(DocumentDirection.values()).map(Enum::name).forEach(direction::addEnumItemObject);
        form.addProperty("direction", direction);

        form.addRequiredItem("documentTypeId");

        upload.setRequestBody(new RequestBody()
            .required(true)
            .description("The file and the document row to record against it.")
            .content(new Content().addMediaType(
                org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE,
                new MediaType().schema(form))));
    }

    private Schema<?> schema(OpenAPI openApi, String name) {
        Map<String, Schema> schemas = openApi.getComponents() == null
            ? null
            : openApi.getComponents().getSchemas();
        return schemas == null ? null : schemas.get(name);
    }

    private void note(OpenAPI openApi, String name, String description) {
        Schema<?> body = schema(openApi, name);
        if (body != null) {
            body.setDescription(description);
        }
    }
}
