package uk.gov.hmcts.reform.deedpoll.ccd;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.type.Document;
import uk.gov.hmcts.ccd.sdk.type.FieldType;
import uk.gov.hmcts.ccd.sdk.type.ListValue;
import uk.gov.hmcts.ccd.sdk.type.TTL;

import java.util.List;

/**
 * The CCD case's data: the link to the relational record, a label for humans, and the documents CDAM holds for it.
 *
 * <p>The SDK derives the definition's case fields from this class, so a field added here is a field added to the
 * definition -- and to the pinned copy {@code DeedPollDefinitionTest} holds it against. Nothing reads case data back
 * as truth (plan 02, decision 1); every field is composed by {@link DeedPollCaseView} from the record.
 */
@Data
@NoArgsConstructor
public class DeedPollCaseData {

    /** The deed poll's UUID: the link, and the only thing correlation uses (plan 02, decision 8). */
    @CCD(label = "Deed poll ID")
    private String deedPollId;

    /**
     * The {@code KBDP…} display reference, for humans only -- its prefix can change and the UUID cannot.
     */
    @CCD(label = "Deed poll reference")
    private String deedPollReference;

    /**
     * The record's supporting documents that are attached in CDAM (plan 03a, task 5), each item keyed on the
     * deed-poll document id. Its name is {@link CaseDocumentsEvent#FIELD}.
     *
     * <p>Here because CDAM's download check asks data-store whether the document is in the case's data. The view
     * never sets a {@link Document}'s hash token: data-store strips it from the event before this service sees it,
     * and the token is transient (decision 8).
     */
    @CCD(label = "Supporting documents", access = SupportingDocumentsAccess.class)
    private List<ListValue<Document>> supportingDocuments;

    /**
     * The platform's time-to-live, which Retain and Dispose needs: data-store applies {@code ConfirmDisposal}'s TTL
     * increment only to a case type with a field whose id is exactly {@code TTL}, and creates the value itself if the
     * case has none. Nothing here sets or reads it -- the view leaves it null -- and only the system profile holds it
     * ({@link TtlAccess}); no human role may suspend or override a deletion.
     */
    @JsonProperty("TTL")
    @CCD(label = "Time to live", typeOverride = FieldType.TTL, access = TtlAccess.class)
    private TTL timeToLive;
}
