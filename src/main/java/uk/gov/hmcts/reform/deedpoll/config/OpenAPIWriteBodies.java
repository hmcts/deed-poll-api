package uk.gov.hmcts.reform.deedpoll.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
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
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseQuery;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseSearch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseSort;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollSchemas;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.ListFilters;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteSettingsService;
import uk.gov.hmcts.reform.deedpoll.domain.masters.MasterService;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteForm;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralReason;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralSchemas;
import uk.gov.hmcts.reform.deedpoll.domain.savedqueries.SavedQuerySchemas;
import uk.gov.hmcts.reform.deedpoll.domain.settings.BankHolidays;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettings;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettingsService;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettingsTimes;
import uk.gov.hmcts.reform.deedpoll.domain.witnesses.WitnessSchemas;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ConfirmationOutcome;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;
import uk.gov.hmcts.reform.deedpoll.notify.EmailSlot;
import uk.gov.hmcts.reform.deedpoll.notify.EmailSlots;
import uk.gov.hmcts.reform.deedpoll.storage.DocumentStorageRule;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Describes the fourteen write bodies that springdoc cannot describe by reflection.
 *
 * <p><strong>Why any of this is needed.</strong> Every write payload in this service binds as a keyed map rather
 * than as declared components, because a patch turns on the difference between an omitted field and an explicit
 * null and a record of nullable components cannot express that -- the argument is in {@code KeyedRequestBody}.
 * The consequence is that the thirteen JSON write bodies are classes with no fields, so springdoc reflects over
 * them and publishes {@code CreateCaseRequest: {}}. The upload is worse: it takes a
 * {@code MultipartHttpServletRequest}, which springdoc does not recognise as a body at all, so that operation is
 * published with no request body whatsoever.
 *
 * <p>That matters because of what the document is for. It is published to {@code hmcts/cnp-api-docs} for
 * consumers to generate clients from, and a consumer cannot tell an empty schema from a body that takes no
 * fields. They would get thirteen request types with no properties and an upload they cannot call, with nothing in
 * the document hinting that anything is missing -- which is the failure {@code OpenAPIPublisherTest} was written
 * to prevent for the paths, reaching the bodies instead. A published document that is silently empty is worse
 * than no document, because a consumer trusts it.
 *
 * <p><strong>Generated from the domain's own tables, not restated here.</strong> The case body's fields come from
 * {@link DeedPollSchemas#writeBodyFields()}, which is the table that validates them; the note limit comes from
 * {@link NoteForm#MAX_NOTE_LENGTH}; the witness caps come from {@link WitnessSchemas}; the referral's caps and
 * reason vocabulary come from {@link ReferralSchemas} and {@link ReferralReason}; the judicial direction's cap
 * and decision vocabulary from {@link ReferralSchemas} and {@link MasterDecision}; the confirmation's cap and
 * outcome vocabulary from {@link ReferralSchemas} and {@link ConfirmationOutcome}; the upload's direction
 * values come from {@link DocumentDirection}; the email slots and their caps from {@link EmailSlot}. A field
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
    private static final String ASSIGN_REFERRAL = "AssignReferralRequest";

    /**
     * The confirmation body -- the applicant's answer to the email that asks whether to proceed.
     *
     * <p>A referral body rather than an email one, although the email is what asks the question: the answer is a
     * fact about that referral's outcome, so it is recorded on the referral's own sub-resource.
     */
    private static final String RECORD_CONFIRMATION = "RecordConfirmationRequest";

    /** The judicial direction body -- the only payload the judicial surface accepts. */
    private static final String JUDICIAL_DIRECTION = "JudicialDirectionRequest";

    /** The two email bodies. Identical in shape and separate types, for the reason the DTOs give. */
    private static final String[] EMAIL_BODIES = {"PreviewEmailRequest", "SendEmailRequest"};

    /** The direction's decision, named once because it is required and the resource is named after it. */
    private static final String DECISION_FIELD = "decision";

    /** The Gazette flag, named once because it is the direction's second required field. */
    private static final String GAZETTE_FIELD = "changesToStandardPublication";

    /** The referral's reasons array, named once because it is the create body's only required field. */
    private static final String REASONS_FIELD = "reasons";

    /** The applicant's answer, named once because it is the confirmation body's only required field. */
    private static final String OUTCOME_FIELD = "outcome";

    /** The saved-query name, named once because it is the create body's only required field. */
    private static final String NAME_FIELD = "name";

    /** The filter key, named once because both saved-query bodies declare it. */
    private static final String FILTER_FIELD = "filter";

    private static final String NOTE_FIELD = "note";

    private static final String REVISION_PROPERTY = "revision";

    /** What the bounds on a witness text field measure, said once for the three of them. */
    private static final String WITNESS_TRIMMED =
        "Trimmed first: the bounds hold for the trimmed value, which is what is stored, so a blank is refused.";

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
            describeAssignment(openApi);
            describeConfirmationBody(openApi);
            describeDirectionBody(openApi);
            describeEmailBodies(openApi);
            describeUploadBody(openApi);
            describeGazetteSettingsBody(openApi);
            describeGazetteBodies(openApi);
            describeProcessSettingsBody(openApi);
            describeMasterBodies(openApi);
            describeBankHolidayBody(openApi);
        };
    }

    private void describeGazetteBodies(OpenAPI openApi) {
        for (String name : java.util.List.of("PreviewGazetteRequest", "SubmitGazetteRequest")) {
            Schema<?> body = schema(openApi, name);
            if (body == null) {
                continue;
            }
            body.setType("object");
            body.setProperties(null);
            body.addProperty("caseIds", new ArraySchema().items(new StringSchema().minLength(1).maxLength(255))
                .minItems(1).maxItems(100).uniqueItems(true));
            body.setRequired(java.util.List.of("caseIds"));
            if (name.equals("SubmitGazetteRequest")) {
                body.addProperty("selectionFingerprint", new StringSchema().minLength(1).maxLength(512)
                    .description("Exact opaque token returned by preview; retain it with the same key on a retry."));
                body.addProperty("recipientRevision", new IntegerSchema().minimum(java.math.BigDecimal.ZERO));
                body.setRequired(java.util.List.of("caseIds", "selectionFingerprint", "recipientRevision"));
            }
            body.setAdditionalProperties(false);
        }
    }

    private void describeGazetteSettingsBody(OpenAPI openApi) {
        Schema<?> settings = schema(openApi, "UpdateGazetteSettingsRequest");
        if (settings == null) {
            return;
        }
        settings.setType("object");
        settings.setProperties(null);
        settings.addProperty("recipientEmail", new StringSchema()
            .maxLength(GazetteSettingsService.MAX_EMAIL_LENGTH)
            .pattern(GazetteSettingsService.EMAIL_PATTERN)
            .description("One email address; outer whitespace is trimmed."
                + " Local part is at most 64 characters. Null and blank are refused."));
        settings.addProperty(REVISION_PROPERTY, new IntegerSchema().minimum(java.math.BigDecimal.ZERO)
            .description("The revision returned by the settings read; stale values return 409."));
        settings.setRequired(java.util.List.of("recipientEmail", REVISION_PROPERTY));
        settings.setAdditionalProperties(false);
    }

    private void describeProcessSettingsBody(OpenAPI openApi) {
        Schema<?> settings = schema(openApi, "UpdateProcessSettingsRequest");
        if (settings == null) {
            return;
        }
        settings.setType("object");
        settings.setProperties(null);
        settings.addProperty(ProcessSettings.REFERRAL_CLAIM_EXPIRY_MINUTES, wholeNumber(
            ProcessSettingsService.MIN_REFERRAL_CLAIM_EXPIRY_MINUTES,
            ProcessSettingsService.MAX_REFERRAL_CLAIM_EXPIRY_MINUTES,
            "The processing window: how long a Master has to give a direction before the referral is unassigned, in"
                + " whole minutes, from 1440 (1 day) to 43200 (30 days). Null, a fraction and a string are refused."));
        settings.addProperty(ProcessSettings.REMINDER_INTERVAL_HOURS, wholeNumber(
            ProcessSettingsService.MIN_REMINDER_INTERVAL_HOURS, ProcessSettingsService.MAX_REMINDER_INTERVAL_HOURS,
            "How often a Master is reminded of their undirected referrals, in whole hours, from 1 to 168. Optional"
                + " for one release; absent leaves it unchanged, null is refused."));
        settings.addProperty(ProcessSettings.MASTER_WINDOW_CAP, wholeNumber(
            ProcessSettingsService.MIN_MASTER_WINDOW_CAP, ProcessSettingsService.MAX_MASTER_WINDOW_CAP,
            "How many referrals one Master may be assigned per referral window, from 1 to 100. Optional for one"
                + " release; absent leaves it unchanged, null is refused."));
        for (String field : ProcessSettings.WINDOW_FIELDS) {
            settings.addProperty(field, new StringSchema().pattern(ProcessSettingsTimes.PATTERN)
                .description("A referral window time, Europe/London, HH:mm on the quarter hour. Each window starts"
                    + " before it ends, and the morning window ends before the afternoon one starts. Optional for"
                    + " one release; absent leaves it unchanged, null is refused."));
        }
        settings.addProperty(REVISION_PROPERTY, new IntegerSchema().minimum(java.math.BigDecimal.ZERO)
            .description("The revision returned by the settings read; stale values return 409."));
        settings.setRequired(java.util.List.of(ProcessSettings.REFERRAL_CLAIM_EXPIRY_MINUTES, REVISION_PROPERTY));
        settings.setAdditionalProperties(false);
    }

    private void describeMasterBodies(OpenAPI openApi) {
        for (String name : java.util.List.of("CreateMasterRequest", "UpdateMasterRequest")) {
            Schema<?> body = schema(openApi, name);
            if (body == null) {
                continue;
            }
            boolean create = name.startsWith("Create");
            body.setType("object");
            body.setProperties(null);
            body.addProperty(MasterService.NAME, new StringSchema().minLength(1)
                .maxLength(MasterService.MAX_NAME_LENGTH)
                .description("Trimmed first: the bounds hold for the trimmed value, so a blank is refused."));
            body.addProperty(MasterService.EMAIL, new StringSchema().maxLength(MasterService.MAX_EMAIL_LENGTH)
                .description("Trimmed and lower-cased; exactly one @ and no whitespace. An address another Master"
                    + " holds, in any case, returns 409."
                    + (create ? "" : " Changing a linked Master's email unlinks it until the new address signs in.")));
            body.addProperty(MasterService.AVAILABLE, new BooleanSchema()
                .description(create ? "Defaults to true." : "False marks a Master who may not be assigned."));
            if (create) {
                body.setRequired(java.util.List.of(MasterService.NAME, MasterService.EMAIL));
            } else {
                body.addProperty(MasterService.REVISION, new IntegerSchema().minimum(java.math.BigDecimal.ZERO)
                    .description("The revision returned by the list; stale values return 409."));
                body.setRequired(java.util.List.of(MasterService.REVISION));
                body.setMinProperties(2);
            }
            body.setAdditionalProperties(false);
        }
    }

    private void describeBankHolidayBody(OpenAPI openApi) {
        Schema<?> body = schema(openApi, "CreateBankHolidayRequest");
        if (body == null) {
            return;
        }
        body.setType("object");
        body.setProperties(null);
        body.addProperty(BankHolidays.DATE, new StringSchema().format("date")
            .description("yyyy-MM-dd. A date already listed returns 409."));
        body.addProperty(BankHolidays.NAME, new StringSchema().minLength(1).maxLength(BankHolidays.MAX_NAME_LENGTH)
            .description("Trimmed first: the bounds hold for the trimmed value, so a blank is refused."));
        body.setRequired(java.util.List.of(BankHolidays.DATE, BankHolidays.NAME));
        body.setAdditionalProperties(false);
    }

    private static IntegerSchema wholeNumber(int minimum, int maximum, String description) {
        IntegerSchema schema = new IntegerSchema();
        schema.format("int32").minimum(java.math.BigDecimal.valueOf(minimum))
            .maximum(java.math.BigDecimal.valueOf(maximum)).description(description);
        return schema;
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
        if (described.lineBreaksCountOnce()) {
            // maxLength counts a CRLF as two, and the service counts it as one, as admin's form and a browser do.
            // Without this a client trusting the document would refuse text the service accepts.
            field.setDescription("A line break counts as one character towards maxLength, whether it is sent "
                + "as LF, CRLF or CR.");
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

    /** A witness text field's bounds, which hold for the trimmed value. */
    private Schema<?> witnessText(int maxLength) {
        return new StringSchema().minLength(1).maxLength(maxLength);
    }

    private Schema<?> noteText() {
        return new StringSchema().minLength(1).maxLength(NoteForm.MAX_NOTE_LENGTH);
    }

    /**
     * The two witness bodies: four fields, all required on create and each optional on patch.
     *
     * <p>The caps are {@link WitnessSchemas}'s own constants rather than numbers written here, for the reason the
     * class comment gives -- the document has to describe what the service enforces, not what somebody remembered
     * it enforced. Each text field publishes {@code minLength: 1}, as the note does: it is trimmed first and a
     * blank is refused, which is the reference's behaviour since review finding R15 and is argued in
     * {@code WitnessSchemas}.
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
                + "The three text fields are trimmed, stored trimmed, and refused if blank; each maximum is measured "
                + "after trimming. The case comes from the path and no author is "
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
        body.addProperty("witnessName", witnessText(WitnessSchemas.MAX_WITNESS_NAME_LENGTH)
            .description(WITNESS_TRIMMED));
        body.addProperty("witnessAddress", witnessText(WitnessSchemas.MAX_WITNESS_ADDRESS_LENGTH)
            .description("The whole address as one free-text field, exactly as the source form holds it. "
                + WITNESS_TRIMMED));
        body.addProperty("witnessOccupation", witnessText(WitnessSchemas.MAX_WITNESS_OCCUPATION_LENGTH)
            .description(WITNESS_TRIMMED));
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
                + "they are sent. " + ReferralReason.Other + " requires notes. The Minor* details (MinorTransgender "
                + "and the rest) say what kind of minor's application it is: " + ReferralReason.Minor + " requires "
                + "at least one, each is accepted only beside it, and the three consent pairs (parental, "
                + "stepparent, a 16 or 17 year old's own) cannot both be named."));
        create.addProperty("notes", new StringSchema()
            .maxLength(ReferralSchemas.MAX_NOTES_LENGTH)
            .description("The single free-text field: the explanation " + ReferralReason.Other + " requires and "
                + "the general referral note at once, rather than two near-identical fields. Trimmed, and a "
                + "whitespace-only value counts as none."));
        create.addProperty("urgency", referralUrgency());
        create.addProperty("urgencyReason", new StringSchema()
            .maxLength(ReferralSchemas.MAX_URGENCY_REASON_LENGTH)
            .description("Why the referral jumped the queue. Required when urgency is " + Urgency.Urgent + "."));
        create.addProperty(ReferralSchemas.MASTER_ID, masterId("The Masters-list entry to assign the referral to. "
            + "Optional: without it the referral is created Open, waiting for a caseworker to assign it."));
        create.addRequiredItem(REASONS_FIELD);
        create.setAdditionalProperties(true);
        create.setDescription("A referral of the case to a Master, assigned to the one masterId names or, without "
            + "it, waiting for assignment. "
            + "reasons is the only field required outright; notes is required when " + ReferralReason.Other
            + " is among them, and urgencyReason when urgency is " + Urgency.Urgent + ". The case comes from "
            + "the path and the state from the assignment, so state and migrated are ignored rather than refused, "
            + "as any unknown key is. A case may hold many referrals over its lifetime but only one that is not "
            + "closed or withdrawn, so a second live referral is a 409 rather than a 400. A refused assignment "
            + "refuses the whole create, and nothing is written.");
    }

    /** A Masters-list entry id, as the create and the reassignment both take it. */
    private static Schema<?> masterId(String description) {
        return new StringSchema().format("uuid").description(description + " An unknown or unavailable Master is "
            + "a 400 on masterId: \"" + ReferralSchemas.SELECT_A_MASTER + "\".");
    }

    /**
     * The reassignment body, and the two refusals an assignment adds to the create and the reassignment (plan D2):
     * the 400 on {@code masterId} and the 409 at the per-Master cap, beside the 409 for a referral that is not
     * waiting for a direction. springdoc publishes none of them, because {@code DomainErrorHandler} answers them.
     */
    private void describeAssignment(OpenAPI openApi) {
        Schema<?> body = schema(openApi, ASSIGN_REFERRAL);
        if (body != null) {
            body.setType("object");
            body.setProperties(null);
            body.addProperty(ReferralSchemas.MASTER_ID, masterId("The Masters-list entry to assign the referral to."));
            body.setRequired(java.util.List.of(ReferralSchemas.MASTER_ID));
            body.setAdditionalProperties(true);
            body.setDescription("Assigns the referral to a Master, or moves it to another. Any caseworker, while the "
                + "referral is Open (waiting for assignment) or Claimed (waiting for a direction). The Master it "
                + "already has is a no-op that records nothing. A reassignment restarts the Master's processing "
                + "window, and the referral counts towards the new Master's cap in the current window.");
        }
        if (openApi.getPaths() == null) {
            return;
        }
        refusals(openApi.getPaths().get("/caseworker/cases/{id}/referrals"), false);
        refusals(openApi.getPaths().get("/caseworker/cases/{id}/referrals/{referralId}/assignment"), true);
    }

    private static void refusals(PathItem path, boolean reassignment) {
        Operation operation = path == null ? null : reassignment ? path.getPut() : path.getPost();
        if (operation == null) {
            return;
        }
        ApiResponses responses = operation.getResponses() == null ? new ApiResponses() : operation.getResponses();
        responses.addApiResponse("400", new ApiResponse().description((reassignment ? "" : "The body is not valid, "
            + "each field's error named; or ") + "masterId names no Master, or one who is not available: \""
            + ReferralSchemas.SELECT_A_MASTER + "\"."));
        responses.addApiResponse("409", new ApiResponse().description((reassignment
            ? "The referral is not waiting for a direction (it is Decided, Closed or Withdrawn); or "
            : "The case already has a referral that is not closed or withdrawn; or ")
            + "the Master already holds the per-Master cap for the current referral window, with an error on "
            + "masterId: \"This Master already has 10 referrals for the morning of 28 October 2026. Choose another "
            + "Master.\" The window is the morning or afternoon of a working day in Europe/London; outside the "
            + "windows a referral counts towards the next one."));
        operation.setResponses(responses);
    }

    /**
     * The confirmation body: what the applicant answered, and what the caseworker wants the case to say about it.
     *
     * <p>The vocabulary is {@link ConfirmationOutcome}'s and the cap is
     * {@link ReferralSchemas#MAX_CONFIRMATION_NOTE_LENGTH}, so both reach the document from what enforces them.
     *
     * <p><strong>{@code outcome} is declared required and enumerated, and both halves matter to a consumer.</strong>
     * The two answers mean opposite things -- enrol the deed knowing it will be published, or refund it -- so there
     * is no default and a client that could omit it would be recording an answer on the applicant's behalf. There is
     * deliberately no member for "no reply yet": silence is the absence of an outcome, which is a null column and is
     * what the waiting list is.
     *
     * <p>Nothing here can name the referral, the case, the author, the date the reply arrived, or any of the case's
     * own dates. The first two are the path, the next two are the token and the clock, and the last is a separate act
     * by the caseworker -- recording an answer moves no case date. Those keys are stripped as any unknown one is, and
     * {@code RecordConfirmationRequest} says why each absence is a rule.
     */
    private void describeConfirmationBody(OpenAPI openApi) {
        Schema<?> confirmation = schema(openApi, RECORD_CONFIRMATION);
        if (confirmation == null) {
            return;
        }
        confirmation.setType("object");
        confirmation.setProperties(null);

        StringSchema outcome = new StringSchema();
        outcome.setDescription("What the applicant answered. Matched exactly, as the stored form is the wire form: "
            + ConfirmationOutcome.Confirmed + " means they still want the deed enrolled knowing it will be "
            + "published, and " + ConfirmationOutcome.Withdrawn + " that they do not and want a refund.");
        Arrays.stream(ConfirmationOutcome.values()).map(Enum::name).forEach(outcome::addEnumItemObject);
        confirmation.addProperty(OUTCOME_FIELD, outcome);
        confirmation.addProperty(NOTE_FIELD, new StringSchema()
            .maxLength(ReferralSchemas.MAX_CONFIRMATION_NOTE_LENGTH)
            .description("What the caseworker wants the case timeline to say about the reply. Optional -- the "
                + "outcome is the fact -- trimmed, and a whitespace-only value counts as none."));
        confirmation.addRequiredItem(OUTCOME_FIELD);
        confirmation.setAdditionalProperties(true);
        confirmation.setDescription("The applicant's answer to the Request confirmation to proceed email, as the "
            + "caseworker who read their reply records it. Recording it does not move the case on: no date is set "
            + "or cleared, and the caseworker then does whatever the process requires through the ordinary case "
            + "edit. Idempotent by refusal rather than last-write-wins -- a second answer is a 409 naming what is "
            + "already recorded and when, and an answer on a referral that was never asked to confirm is a 409 "
            + "too. The referral comes from the path, and the recording caseworker and the date come from the "
            + "token and the clock, so confirmationRecordedBy and confirmationReceivedAt are ignored rather than "
            + "refused, as any unknown key is.");
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
     * The one judicial body: a judge's direction, with two required fields and one optional, taken by both the
     * direction {@code POST} and the {@code PUT} that revises it.
     *
     * <p>The decision vocabulary is {@link MasterDecision}'s and the cap is
     * {@link ReferralSchemas#MAX_DIRECTIONS_LENGTH}, so both reach the document from what enforces them.
     *
     * <p><strong>{@code changesToStandardPublication} is declared required and as a boolean, and both halves matter to
     * a consumer.</strong> Publication puts the applicant's former and new names in a public notice, so the schema
     * refuses the string {@code "false"} rather than reading it truthily -- a document that called this a string
     * would have clients sending the one value that coerces wrongly. Required because a direction that did not
     * answer it is not a direction.
     *
     * <p>Nothing here can name the referral, the case, the state, the author or the date: the referral is the
     * path, the case is resolved from it, and the rest come from the clock and the token. Those keys are stripped
     * as any unknown one is, and {@code JudicialDirectionRequest} says why each absence is a rule.
     */
    private void describeDirectionBody(OpenAPI openApi) {
        Schema<?> direction = schema(openApi, JUDICIAL_DIRECTION);
        if (direction == null) {
            return;
        }
        direction.setType("object");
        direction.setProperties(null);

        StringSchema decision = new StringSchema();
        decision.setDescription("What the judge decided. Matched exactly, as the stored form is the wire form.");
        Arrays.stream(MasterDecision.values()).map(Enum::name).forEach(decision::addEnumItemObject);
        direction.addProperty(DECISION_FIELD, decision);
        direction.addProperty("directionsText", new StringSchema()
            .maxLength(ReferralSchemas.MAX_DIRECTIONS_LENGTH)
            .description("What the judge directed. Optional on a standard publication: an approval that needs no "
                + "comment is a real answer. Required when changesToStandardPublication is true, to say whether "
                + "to publish nothing or what to publish. Trimmed, and a whitespace-only value counts as none."));
        direction.addProperty(GAZETTE_FIELD, new BooleanSchema()
            .description("The Master's answer to \"Are there any changes to the standard publications in the "
                + "Gazette?\": true for do not publish, or publish only as directionsText says; false for publish "
                + "as standard. The judge's decision, never derived from a caseworker's referral reasons. A JSON "
                + "boolean -- the string \"false\" is refused rather than coerced, because publication cannot be "
                + "taken back."));
        direction.addRequiredItem(DECISION_FIELD);
        direction.addRequiredItem(GAZETTE_FIELD);
        direction.setAdditionalProperties(true);
        direction.setDescription("A judge's direction on a referral, always complete -- there is no draft and "
            + "no patch, because the check-your-answers page and the attestation are the admin's. POST records "
            + "it on a referral this caller currently holds, so a referral withdrawn under them or taken back by "
            + "the inactivity sweep is a 409 rather than a late direction on a referral that moved on. PUT "
            + "replaces the whole of a direction this caller gave, while the referral is Decided and not yet "
            + "closed, and is validated identically. The referral comes from the path and the case is resolved "
            + "from it -- a judicial "
            + "URL carries no case id -- and the state, decidedBy and decisionDate come from the transition, "
            + "the token and the clock, so all of them are ignored rather than refused, as any unknown key is.");
    }

    /**
     * The two email bodies: the slots a caseworker fills, and nothing else.
     *
     * <p>Generated from {@link EmailSlot} itself, so a slot added to a template appears in the document without
     * this file being edited and carries the cap the schema actually enforces.
     *
     * <p><strong>{@code additionalProperties} is set false here, and it is the only body in this file where that
     * is true of the service.</strong> Everywhere else an unknown key is stripped, so publishing {@code false}
     * would describe a stricter API than exists. An email payload is the exception by design: the interesting
     * unknown keys are exactly the ones a client must not send -- {@code reference_number},
     * {@code applied_online} -- and stripping them would return success while ignoring the part that mattered.
     * They are refused and named, so the document says so.
     *
     * <p>{@code judges_reason} is described as required in prose rather than declared required, because it is
     * required <em>of the email</em> and not of the request: it is pre-populated from the judge's direction, so a
     * body that omits it is accepted whenever the referral has one. A generated client declaring it mandatory
     * would refuse the ordinary case, which is a caseworker sending the judge's words unaltered.
     */
    private void describeEmailBodies(OpenAPI openApi) {
        for (String name : EMAIL_BODIES) {
            Schema<?> body = schema(openApi, name);
            if (body == null) {
                continue;
            }
            body.setType("object");
            body.setProperties(null);
            Arrays.stream(EmailSlot.values()).forEach(slot ->
                body.addProperty(slot.placeholder(), slotSchema(slot)));
            body.setAdditionalProperties(false);
            body.setDescription("The slots a caseworker fills in, and the whole of what a client may set. Every "
                + "other value in the email -- the reference number, the applicant's former names, and which "
                + "refund paragraph is rendered -- is resolved from the case, and a request naming one of them is "
                + "a 400 that says which: a caseworker altering the reference an applicant is told, or flipping "
                + "the refund branch, is a correctness and a fraud problem rather than a convenience. An unknown "
                + "key is likewise refused and named rather than ignored, unlike every other body here. "
                + "judges_reason is required of the email but not of the request: it is pre-populated from the "
                + "referral's direction, so a body omitting it is accepted whenever a direction was recorded, and "
                + "refused with that field named when none was. documents_required is required of the request "
                + "itself -- nothing pre-populates it -- so an empty body for that email is a 400. The properties "
                + "here are every slot any template takes, which is not the set any one template takes: which "
                + "slots a given template accepts, and what they open with, come from "
                + "GET /caseworker/cases/{id}/emails, and a slot belonging to another template is refused by name. "
                + "An over-long slot is refused rather than "
                + "truncated, because a truncated quotation of a judge is worse than being asked to shorten one.");
        }
    }

    /**
     * One slot, with the cap the slot enforces and the sentence the slot is refused with.
     *
     * <p><strong>Three answers rather than two, because "required" alone is not the contract.</strong> A slot
     * nothing pre-populates is required <em>of the request</em> -- omitting it is a 400 -- while one that is
     * pre-populated is required of the email and not of the request. Deriving the description from
     * {@link EmailSlot#required()} alone published the second sentence for both, which told a consumer that
     * {@code documents_required} would be filled in for them. The reason comes from
     * {@link EmailSlot#requiredBecause()}, so the document and the refusal are one statement.
     */
    private Schema<?> slotSchema(EmailSlot slot) {
        return new StringSchema()
            .maxLength(slot.maxLength())
            .description(slotDescription(slot));
    }

    /** What omitting this slot means, in the words the service would refuse it with. */
    private String slotDescription(EmailSlot slot) {
        if (!slot.required()) {
            return "Optional. Anything the caseworker wants to add to the approved wording.";
        }
        if (EmailSlots.isPrePopulated(slot)) {
            return "Required of the email but not of the request: pre-populated from the referral's direction, so "
                + "a body omitting it is accepted whenever a direction was recorded and refused with this field "
                + "named when none was.";
        }
        return "Required of the request, and pre-populated from nothing -- " + slot.requiredBecause()
            + ". An omitted or blank value is a 400 naming this field.";
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

        // Only the CDAM store refuses this, and only for a record not yet linked to a CCD case (plan 03a, task 4).
        // The same sentence the refusal carries, so the document and the refusal cannot drift.
        ApiResponses responses = upload.getResponses() == null ? new ApiResponses() : upload.getResponses();
        ApiResponse notReady = responses.get("409") == null ? new ApiResponse() : responses.get("409");
        responses.addApiResponse("409", notReady.description(DocumentStorageRule.CASE_NOT_READY));
        upload.setResponses(responses);
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
