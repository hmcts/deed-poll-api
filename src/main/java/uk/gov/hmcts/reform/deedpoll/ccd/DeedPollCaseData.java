package uk.gov.hmcts.reform.deedpoll.ccd;

import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;

/**
 * The CCD case's data: the link to the relational record, and a label for humans.
 *
 * <p>The SDK derives the definition's case fields from this class, so a field added here is a field added to the
 * definition -- and to the pinned copy {@code DeedPollDefinitionTest} holds it against. Nothing reads case data back
 * as truth (plan 02, decision 1); these two exist to find the record and to recognise it.
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
}
