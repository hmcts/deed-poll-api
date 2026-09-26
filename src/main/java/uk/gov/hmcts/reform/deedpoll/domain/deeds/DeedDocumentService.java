package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadedFile;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocumentType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentTypeRepository;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A case's generated deed, as a stored document: produced once, kept, and handed back thereafter.
 *
 * <p><strong>Generated once per case and never again</strong> -- decision 6 of
 * {@code docs/plans/notify-4b-generate-the-deed.md}, and the reason is what LOC020 is. It does not describe the
 * change of name; it <em>is</em> the deed, which the applicant prints, signs in wet ink before witnesses and
 * sends to the court to be sealed. If a second request regenerated it, the bytes this service holds and the
 * paper in the court's hands could differ -- and nothing downstream would notice, because both would look like
 * a valid deed. So a second request for a case that already has one returns the first, and the generator is not
 * called at all.
 *
 * <p><strong>"Already has one" means any deed, not this template's deed.</strong> A case gets one deed; which
 * one depends on its type. A lookup narrowed to the requested template would answer "no deed yet" for a case
 * whose type was corrected between the two requests, and store a second legal instrument for the same
 * applicant. So both deed types are looked at, and a mismatch is <em>refused</em>: the stored deed is not
 * replaced -- it may already be signed -- and it is not handed back either, because the caller asking for the
 * other deed is about to put it in front of an applicant under the other deed's covering words.
 *
 * <p><strong>The mismatch is reachable, which is why it refuses rather than warns.</strong> An earlier version
 * of this class returned the stored deed with a log line, on the ground that the exclusion group makes the two
 * forms emails mutually exclusive per case. It does not, quite: V20's index -- like V15's -- excludes a
 * {@code Failed} row with a {@code PERMANENT} verdict, deliberately, so that a send Notify certainly never made
 * can be replaced. So a case whose first forms email failed permanently, and whose type was then corrected, is
 * offered the <em>other</em> forms email and nothing in the outbox stands in the way. Returning the stored deed
 * there would email an applicant a minor's covering letter carrying an adult's deed -- exactly the failure the
 * routing exists to prevent, arrived at with only a log line to say so. A {@code ConflictError} stops the send
 * instead, and the caseworker resolves the contradiction: either the case's type is wrong, or the deed the case
 * already holds has to be withdrawn.
 *
 * <p><strong>A document filed under a deed's type is not the same thing as a deed this service generated.</strong>
 * {@link DeedDocumentTypes} creates real {@code deedpoll_supportingdocumenttype} rows, and those rows are
 * published by {@code GET /caseworker/document-types} and accepted as an arbitrary {@code documentTypeId} by the
 * upload endpoint -- so from the first deed generated in an environment onward, a caseworker filing the signed
 * deed coming back from the applicant can pick "LOC020 Change of name deed" from the same dropdown. The lookup
 * below keys on the type's name, which cannot tell that row from a generated one, and answering with it would
 * email the applicant their own returned paperwork as the deed to print, sign and swear. So the direction
 * decides: the court's deed went {@code Outgoing}, and anything else filed under a deed type is <em>refused</em>
 * in the same words a mismatch is. Refused rather than ignored, because the two readings of an
 * {@code Incoming} row -- a mis-filed upload, or a generated deed whose direction a caseworker patched -- are
 * indistinguishable here, and the second would have this service generate a <em>second</em> deed, which is the
 * failure decision 6 exists to prevent. A caseworker can re-file a document; nobody can un-swear a deed.
 *
 * <p><strong>Written through {@link DocumentService}, not around it.</strong> The blob goes to the
 * {@link uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentStore} seam -- CDAM in plan 03 -- and its row
 * gets the store's key, the mime type, the size and the filename in one transaction, with
 * {@code DocumentService}'s own rollback cleanup behind it: a generated deed whose row never commits leaves no
 * orphaned bytes. Duplicating any of that here would be a second upload path to maintain and the one that did
 * not get the fix.
 *
 * <p><strong>The deed is {@code Outgoing}.</strong> Every other document on a case arrives from the applicant;
 * this one is the court's, sent out, and the direction is what the documents grid renders. It is passed
 * explicitly rather than left to {@code DocumentService}'s {@code Incoming} default.
 *
 * <p><strong>It does not decide which deed a case takes.</strong> The template is an argument, for the reason
 * {@link DeedTemplate} sets out.
 */
@Slf4j
@Service
public class DeedDocumentService {

    private final DeedGenerator generator;
    private final DocumentService documents;
    private final DeedPollRepository deedPolls;
    private final DeedPollSupportingDocumentRepository documentRows;
    private final DeedPollSupportingDocumentTypeRepository types;

    public DeedDocumentService(DeedGenerator generator,
                               DocumentService documents,
                               DeedPollRepository deedPolls,
                               DeedPollSupportingDocumentRepository documentRows,
                               DeedPollSupportingDocumentTypeRepository types) {
        this.generator = generator;
        this.documents = documents;
        this.deedPolls = deedPolls;
        this.documentRows = documentRows;
        this.types = types;
    }

    /**
     * The case's deed: the stored one if it has one already, otherwise generated, stored and returned.
     *
     * <p>Idempotent by reading before writing, in one transaction with the write. That is weaker than a unique
     * index and it is worth being explicit about why there is not one: the type row's id is data rather than a
     * constant, so there is no predicate a partial index could carry to say "a deed of a case".
     *
     * <p><strong>What the read-before-write does and does not close.</strong> Two concurrent sends of the
     * <em>same</em> forms email under different request ids both find no deed, both generate one, and the loser
     * commits its row as well -- because the outbox refuses its insert with a row of the same template, which is
     * the idempotent {@code 200} rather than a throw. The case then shows two deed rows. That is benign and
     * deliberately not guarded: {@code DeedGenerator} stamps every zip entry with a fixed time, so the two blobs
     * are byte-identical, and only one of them was ever emailed. What the read <em>does</em> close is the case
     * that matters -- a second, <em>different</em> deed -- because the two contradictory sends cannot both reach
     * here: one of them is refused by the mismatch below.
     *
     * @param deedPollId the case the deed is for
     * @param template which of the court's deeds the case takes -- see {@link DeedTemplate}
     * @return the stored document, whether it was generated by this call or an earlier one
     * @throws NotFoundError if there is no such case
     * @throws ConflictError if the case already holds the other deed, or holds a document filed under a deed's
     *     type that the court never sent out -- see the class comment for both
     * @throws IllegalStateException if the template no longer has the shape the generator writes
     */
    @Transactional
    public DeedPollSupportingDocument storeDeed(String deedPollId, DeedTemplate template) {
        DeedPoll deedPoll = deedPolls.findById(deedPollId)
            .orElseThrow(() -> new NotFoundError("DeedPoll " + deedPollId + " not found"));

        Optional<DeedPollSupportingDocument> stored = existingDeed(deedPollId);
        if (stored.isPresent()) {
            DeedPollSupportingDocument existing = stored.get();
            String held = existing.getDocumentType().getName();
            if (existing.getDirection() != DocumentDirection.Outgoing) {
                // Asked before the template is compared, because the direction says whether this row is a deed
                // at all and a mismatch message would name the wrong cause. See the class comment: this is
                // either a caseworker's upload mis-filed under a deed's type or a generated deed whose
                // direction was patched, and this service cannot tell -- so it refuses both rather than email
                // the first or regenerate over the second.
                log.warn("Case {} holds an {} document filed as {}, which is not a deed this service sent out, "
                             + "so the {} send was refused",
                         deedPollId, existing.getDirection(), held, template.formCode());
                throw new ConflictError(
                    "This case holds a document filed as its " + held + " that the court did not send out, so it "
                        + "is not a deed this service generated. Re-file that document under the type it belongs "
                        + "to, or withdraw it, before this email can be sent");
            }
            if (!DeedDocumentTypes.name(template).equals(held)) {
                // Refused rather than answered with the deed the case holds: the caller is about to send that
                // deed out under the other deed's covering words. Neither replaced nor returned -- see the
                // class comment for how this is reached and why it is a caseworker's decision.
                log.warn("Case {} was asked for its {} deed but already holds {}, so the send was refused",
                         deedPollId, template.formCode(), held);
                throw new ConflictError(
                    "This case already has its " + held + ", and an applicant signs and swears one deed. The "
                        + template.formCode() + " deed this email carries is a different legal instrument, so "
                        + "either the case's type is wrong or the deed it already holds has to be withdrawn "
                        + "before this email can be sent");
            }
            return existing;
        }

        GeneratedDeed deed = generator.generate(deedPoll, template);
        DeedPollSupportingDocumentType type = typeFor(template);
        UploadedFile file = new UploadedFile(
            deed.content(), DeedFilename.of(deedPoll, deed), deed.mimeType(), deed.size());

        return documents.uploadDocument(deedPollId, type.getId(), file,
                                        Optional.of(type.getName()), Optional.of(DocumentDirection.Outgoing));
    }

    /**
     * The case's deed of either kind, oldest first so that a case which somehow holds two answers with the one
     * an applicant may already have signed.
     *
     * <p><strong>Both directions, deliberately.</strong> Narrowing the query to {@code Outgoing} would make a
     * generated deed whose direction a caseworker patched invisible here, and the next send would generate a
     * second deed -- so the row is fetched whatever its direction and {@code storeDeed} refuses the ones that
     * are not the court's own.
     */
    private Optional<DeedPollSupportingDocument> existingDeed(String deedPollId) {
        return documentRows.findFirstByDeedPollIdAndDocumentTypeNameInOrderByCreatedOnAsc(deedPollId, deedTypeNames());
    }

    private static Set<String> deedTypeNames() {
        return Arrays.stream(DeedTemplate.values())
            .map(DeedDocumentTypes::name)
            .collect(Collectors.toSet());
    }

    /**
     * The document type a deed of this template is filed under, created if this environment has not needed one
     * yet -- see {@link DeedDocumentTypes} for why nothing seeds it.
     *
     * <p>{@code findFirst} rather than a unique lookup: the column has no unique index, so two rows of the same
     * name are possible (two first deeds racing, or a caseworker-created namesake if the type write operations
     * ever get an HTTP surface). Either row is a correct place to file the document, and answering
     * deterministically matters more than there being exactly one.
     */
    private DeedPollSupportingDocumentType typeFor(DeedTemplate template) {
        String name = DeedDocumentTypes.name(template);
        return types.findFirstByNameOrderByIdAsc(name).orElseGet(() -> created(template, name));
    }

    private DeedPollSupportingDocumentType created(DeedTemplate template, String name) {
        DeedPollSupportingDocumentType type = new DeedPollSupportingDocumentType();
        type.setName(name);
        type.setApplicableTypes(DeedDocumentTypes.applicableTypes(template));
        // applicableMaritalStatuses stays empty: the adult deed prints the applicant's marital status but is
        // not restricted by it, and an empty multiselect is how the column says "any".
        log.info("Creating the {} document type, which this environment had none of", name);
        return types.save(type);
    }
}
