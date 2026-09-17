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
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseQuery;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseSearch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseSort;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollSchemas;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.ListFilters;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteForm;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralReason;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralSchemas;
import uk.gov.hmcts.reform.deedpoll.domain.savedqueries.SavedQuerySchemas;
import uk.gov.hmcts.reform.deedpoll.domain.witnesses.WitnessSchemas;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Describes the ten write bodies that springdoc cannot describe by reflection.
 *
 * <p><strong>Why any of this is needed.</strong> Every write payload in this service binds as a keyed map rather
 * than as declared components, because a patch turns on the difference between an omitted field and an explicit
 * null and a record of nullable components cannot express that -- the argument is in {@code KeyedRequestBody}.
 * The consequence is that the nine JSON write bodies are classes with no fields, so springdoc reflects over them
 * and publishes {@code CreateCaseRequest: {}}. The upload is worse: it takes a
 * {@code MultipartHttpServletRequest}, which springdoc does not recognise as a body at all, so that operation is
 * published with no request body whatsoever.
 *
 * <p>That matters because of what the document is for. It is published to {@code hmcts/cnp-api-docs} for
 * consumers to generate clients from, and a consumer cannot tell an empty schema from a body that takes no
 * fields. They would get nine request types with no properties and an upload they cannot call, with nothing in
 * the document hinting that anything is missing -- which is the failure {@code OpenAPIPublisherTest} was written
 * to prevent for the paths, reaching the bodies instead. A published document that is silently empty is worse
 * than no document, because a consumer trusts it.
 *
 * <p><strong>Generated from the domain's own tables, not restated here.</strong> The case body's fields come from
 * {@link DeedPollSchemas#writeBodyFields()}, which is the table that validates them; the note limit comes from
 * {@link NoteForm#MAX_NOTE_LENGTH}; the witness caps come from {@link WitnessSchemas}; the referral's caps and
 * reason vocabulary come from {@link ReferralSchemas} and {@link ReferralReason}; the upload's direction
 * values come from {@link DocumentDirection}. A field
 * added to the schema therefore appears in the document without anyone editing this file, which is the only way
 * a description of sixty-seven fields stays true.
 */
@Configuration
public class OpenAPIWriteBodies {

    private static final String CREATE_CASE = "CreateCaseRequest";
    private static final String UPDATE_CASE = "UpdateCaseRequest";
    private static final String CREATE_NOTE = "CreateNoteRequest";
    private static final String UPDATE_NOTE = "UpdateNoteRequest";
    private static final String CREATE_WITNESS = "CreateWitnessRequest";
    private static final String UPDATE_WITNESS = "UpdateWitnessRequest";
    private static final String CREATE_SAVED_QUERY = "CreateSavedQueryRequest";
    private static final String UPDATE_SAVED_QUERY = "UpdateSavedQueryRequest";

    /** The referral create body. There is no update sibling: amending a referral is raising another. */
    private static final String CREATE_REFERRAL = "CreateReferralRequest";

    /** The referral's reasons array, named once because it is the create body's only required field. */
    private static final String REASONS_FIELD = "reasons";

    /** The saved-query name, named once because it is the create body's only required field. */
    private static final String NAME_FIELD = "name";

    /** The filter key, named once because both saved-query bodies declare it. */
    private static final String FILTER_FIELD = "filter";

    private static final String NOTE_FIELD = "note";

    /** The declarant flag, named once because both witness bodies declare it. */
    private static final String DECLARANT_FIELD = "declarant";

    /** The upload route, as it appears in the document's paths. */
    private static final String UPLOAD_PATH = "/caseworker/cases/{id}/documents";

    @Bean
    public OpenApiCustomizer writeBodyCustomizer() {
        return openApi -> {
            describeCaseBodies(openApi);
            describeNoteBodies(openApi);
            describeWitnessBodies(openApi);
            describeSavedQueryBodies(openApi);
            describeReferralBody(openApi);
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

            if (!described.allowedValues().isEmpty()) {
                // An `enum` constrains the value to its listed members independently of the type union, so a
                // nullable enum whose list omits null publishes a field a validating client cannot clear -- the
                // exact thing the union was added to say, contradicted one line down. Eight fields are nullable
                // enums (type, postCounter, maritalStatus, nationality, nationalitySection, referToMasterChoice,
                // masterDecision, state), and the service accepts a null for every one of them.
                addEnumValue(field, null);
            }
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
     * The two witness bodies: four fields, all required on create and each optional on patch.
     *
     * <p>The caps are {@link WitnessSchemas}'s own constants rather than numbers written here, for the reason the
     * class comment gives -- the document has to describe what the service enforces, not what somebody remembered
     * it enforced. No {@code minLength}, unlike the note: a witness field is not trimmed and an empty string is
     * accepted, which is the reference's behaviour and is argued in {@code WitnessSchemas}.
     */
    private void describeWitnessBodies(OpenAPI openApi) {
        Schema<?> create = schema(openApi, CREATE_WITNESS);
        if (create != null) {
            create.setType("object");
            create.setProperties(null);
            witnessFields(create);
            // All four, because all four columns are NOT NULL and a witness is entered from a completed
            // declaration rather than logged incrementally the way a case is.
            create.addRequiredItem("witnessName");
            create.addRequiredItem("witnessAddress");
            create.addRequiredItem("witnessOccupation");
            create.addRequiredItem(DECLARANT_FIELD);
            create.setAdditionalProperties(true);
            create.setDescription("A witness or declarant. All four fields are required: every column is NOT "
                + "NULL. declarant must be a JSON boolean -- the string \"true\" is refused rather than coerced. "
                + "Values are stored as submitted and not trimmed. The case comes from the path and no author is "
                + "recorded, so neither can be set here. Unknown keys are ignored.");
        }

        Schema<?> update = schema(openApi, UPDATE_WITNESS);
        if (update != null) {
            update.setType("object");
            update.setProperties(null);
            witnessFields(update);
            update.setAdditionalProperties(true);
            // Nothing required and nothing nullable: an omitted field is left as stored, and an explicit null is
            // a 400 rather than a clear, because no column can hold one.
            update.setDescription("A patch. An omitted field is left as stored; a field that is present gets the "
                + "create rule. An empty patch is accepted and re-stamps modifiedOn. An explicit null is a 400, "
                + "not a clear: every column is NOT NULL. Unknown keys are ignored.");
        }
    }

    private void witnessFields(Schema<?> body) {
        body.addProperty("witnessName",
            new StringSchema().maxLength(WitnessSchemas.MAX_WITNESS_NAME_LENGTH));
        body.addProperty("witnessAddress",
            new StringSchema().maxLength(WitnessSchemas.MAX_WITNESS_ADDRESS_LENGTH)
                .description("The whole address as one free-text field, exactly as the source form holds it."));
        body.addProperty("witnessOccupation",
            new StringSchema().maxLength(WitnessSchemas.MAX_WITNESS_OCCUPATION_LENGTH));
        body.addProperty(DECLARANT_FIELD, new BooleanSchema()
            .description("Whether this witness is the declarant. One row type covers both."));
    }

    /**
     * The two saved-query bodies: {@code name} required on create, everything else optional on both.
     *
     * <p>The bounds and the vocabularies are the domain's own -- {@link SavedQuerySchemas}'s name and description
     * caps, {@link CaseSearch#MAX_TERM_LENGTH} for the term, {@link CaseQuery#MAX_CONDITIONS} for the array,
     * {@link ListFilters#filterTabs()} for the filter keys, {@link CaseSort.Direction#wireTokens()} for the
     * directions -- so a cap changed in one of those appears here without this file being edited.
     *
     * <p><strong>{@code conditions} is an array of the wire strings a list URL carried</strong>, not structured
     * objects. That is the whole reason the live query's own validator can check a stored query, and a consumer
     * that modelled it as objects would be unable to duplicate a query by reading one and posting it back. The
     * enum of sortable fields is deliberately <em>not</em> published: there are more than sixty, and
     * {@code CaseSort.sortableFields()} is the authority a client should read rather than a list frozen into a
     * document.
     */
    private void describeSavedQueryBodies(OpenAPI openApi) {
        Schema<?> create = schema(openApi, CREATE_SAVED_QUERY);
        if (create != null) {
            create.setType("object");
            create.setProperties(null);
            savedQueryFields(create);
            create.addRequiredItem(NAME_FIELD);
            create.setAdditionalProperties(true);
            create.setDescription("A saved case-list query. Only name is required: a query that narrows nothing "
                + "is legitimate, because \"everything, newest first\" is a view somebody wants a tab for. The "
                + "owner comes from the caller's token, so ownerId cannot be set here, and page is not part of a "
                + "view -- both are ignored rather than refused, as any unknown key is. Saving does not select: "
                + "the new query is in the library and in nobody's list, including its owner's.");
        }

        Schema<?> update = schema(openApi, UPDATE_SAVED_QUERY);
        if (update != null) {
            update.setType("object");
            update.setProperties(null);
            savedQueryFields(update);
            update.setAdditionalProperties(true);
            // Nothing required, and unlike the note and witness patches an explicit null IS meaningful here:
            // every column but name is nullable, so null clears it. That difference is the reason this
            // description exists rather than reusing the witness wording.
            update.setDescription("A patch. An omitted field is left as stored, and an explicit null clears the "
                + "column -- unlike the note and witness patches, where no column is nullable and a null is a "
                + "400. name cannot be cleared. An empty patch is accepted and re-stamps modifiedOn. Editing is "
                + "the owner's right, or an Administrator's if the query is shared. Unknown keys are ignored.");
        }
    }

    private void savedQueryFields(Schema<?> body) {
        body.addProperty(NAME_FIELD, new StringSchema()
            .minLength(1)
            .maxLength(SavedQuerySchemas.MAX_NAME_LENGTH)
            .description("What the query is called, as it appears in a navigation bar."));
        body.addProperty("description", new StringSchema()
            .maxLength(SavedQuerySchemas.MAX_DESCRIPTION_LENGTH));
        body.addProperty("shared", new BooleanSchema()
            .description("Whether the query is published to the library every caseworker can browse. Publishing "
                + "makes it findable, not present in anyone else's list -- each user selects what they want to "
                + "see. A shared query may also be amended or deleted by an Administrator."));
        body.addProperty(FILTER_FIELD, filterKeys());
        body.addProperty("conditions", new ArraySchema()
            .items(new StringSchema())
            .maxItems(CaseQuery.MAX_CONDITIONS)
            .description("The where conditions as the wire strings a list URL carried -- "
                + "\"field:operator:value\", split on the first two colons -- and NOT structured objects. "
                + "Validated on write by the same parser a live query goes through, so a saved query "
                + "round-trips a list URL unchanged."));
        body.addProperty("sortField", new StringSchema()
            .description("The field to order by. Any of CaseSort.sortableFields() -- the single-valued form "
                + "fields plus the audit columns -- which is not enumerated here because there are more than "
                + "sixty and a frozen copy would drift."));
        body.addProperty("sortDirection", sortDirections());
        body.addProperty("searchTerm", new StringSchema()
            .maxLength(CaseSearch.MAX_TERM_LENGTH)
            .description("The q search term this view carries, matched against the reference and the name "
                + "columns. page is deliberately not stored: page 4 of a previous result set is not a page of "
                + "this one."));
    }

    /** The filter keys, from the list's own table rather than a copy of it. */
    private Schema<?> filterKeys() {
        StringSchema keys = new StringSchema();
        keys.setDescription("Which case-list filter tab the view opens on.");
        ListFilters.filterTabs().forEach(tab -> addEnumValue(keys, tab.key()));
        return keys;
    }

    /** The two direction tokens, matched exactly as the live sort parameter matches them. */
    private Schema<?> sortDirections() {
        StringSchema directions = new StringSchema();
        directions.setDescription("Matched exactly: " + CaseSort.Direction.wireTokens()
            + ". A bare sortField with no direction is read as ascending.");
        Arrays.stream(CaseSort.Direction.values()).forEach(d -> addEnumValue(directions, d.wire()));
        return directions;
    }

    /**
     * The one referral body: {@code reasons} required, and two fields that are required conditionally.
     *
     * <p>The vocabularies and the caps are {@link ReferralReason} and {@link ReferralSchemas}'s own, so a member
     * added to the enum appears in the document without this file being edited.
     *
     * <p><strong>The two conditional rules are described in prose rather than as constraints</strong>, because
     * OpenAPI has no way to say "required when another field has a particular value" that a generated client
     * would honour. Declaring {@code notes} required outright would describe a stricter API than exists -- most
     * referrals need no explanation -- so the document says required-when, and the service is what enforces it.
     *
     * <p>There is no update body, and that is the shape of the feature: a referral is not edited in place, so
     * the only write that carries a payload is the create.
     */
    private void describeReferralBody(OpenAPI openApi) {
        Schema<?> create = schema(openApi, CREATE_REFERRAL);
        if (create == null) {
            return;
        }
        create.setType("object");
        create.setProperties(null);

        StringSchema reason = new StringSchema();
        reason.setDescription("Matched exactly, as the stored form is the wire form.");
        Arrays.stream(ReferralReason.values()).map(Enum::name).forEach(reason::addEnumItemObject);
        create.addProperty(REASONS_FIELD, new ArraySchema()
            .items(reason)
            .minItems(1)
            .description("Why the case is being referred. At least one, de-duplicated, and stored in the order "
                + "they are sent. " + ReferralReason.Other + " requires notes."));
        create.addProperty("notes", new StringSchema()
            .maxLength(ReferralSchemas.MAX_NOTES_LENGTH)
            .description("The single free-text field: the explanation " + ReferralReason.Other + " requires and "
                + "the general referral note at once, rather than two near-identical fields. Trimmed, and a "
                + "whitespace-only value counts as none."));
        create.addProperty("urgency", referralUrgency());
        create.addProperty("urgencyReason", new StringSchema()
            .maxLength(ReferralSchemas.MAX_URGENCY_REASON_LENGTH)
            .description("Why the referral jumped the queue. Required when urgency is " + Urgency.Urgent + "."));
        create.addRequiredItem(REASONS_FIELD);
        create.setAdditionalProperties(true);
        create.setDescription("A referral of the case to the judiciary, raised unclaimed into the shared pool. "
            + "reasons is the only field required outright; notes is required when " + ReferralReason.Other
            + " is among them, and urgencyReason when urgency is " + Urgency.Urgent + ". The case comes from "
            + "the path, the state is always Open, and there is no target judge -- who picks a referral up is a "
            + "later transition -- so state, migrated and any assignee are ignored rather than refused, as any "
            + "unknown key is. A case may hold many referrals over its lifetime but only one that is not "
            + "closed or withdrawn, so a second live referral is a 409 rather than a 400.");
    }

    /** The referral's own urgency, which is the case's enum reused and may differ from the case's value. */
    private Schema<?> referralUrgency() {
        StringSchema urgency = new StringSchema();
        urgency.setDescription("The referral's own urgency, separate from the case's and defaulting to "
            + Urgency.Normal + ". Matched exactly: an unknown value is refused rather than read as "
            + Urgency.Normal + ", because that would drop the one signal that gets a referral looked at.");
        Arrays.stream(Urgency.values()).map(Enum::name).forEach(urgency::addEnumItemObject);
        return urgency;
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
            .description("The document type this file is filed under. Must be an existing type, or 404. A type's "
                + "applicability to the case's marital status and deed poll type is advisory -- it is what a form "
                + "filters its options by, and the service does not enforce it."));
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
