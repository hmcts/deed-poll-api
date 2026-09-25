package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
 * applicant. So both deed types are looked at, and a mismatch is returned with a warning rather than added to:
 * of the two ways to be wrong, a caseworker seeing the wrong deed on the case and asking about it is
 * recoverable, and two signed deeds in circulation for one change of name is not. The mismatch is not reachable
 * through the emails themselves, which the exclusion group already makes mutually exclusive per case.
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
     * constant, so there is no predicate a partial index could carry to say "a deed of a case". What closes the
     * race in practice is the caller -- the send that asks for a deed is itself guarded by the outbox's unique
     * indexes and shares this transaction, so two concurrent sends cannot both commit. A caller outside that
     * path wanting the guarantee has to serialise itself.
     *
     * @param deedPollId the case the deed is for
     * @param template which of the court's deeds the case takes -- see {@link DeedTemplate}
     * @return the stored document, whether it was generated by this call or an earlier one
     * @throws NotFoundError if there is no such case
     * @throws IllegalStateException if the template no longer has the shape the generator writes
     */
    @Transactional
    public DeedPollSupportingDocument storeDeed(String deedPollId, DeedTemplate template) {
        DeedPoll deedPoll = deedPolls.findById(deedPollId)
            .orElseThrow(() -> new NotFoundError("DeedPoll " + deedPollId + " not found"));

        Optional<DeedPollSupportingDocument> stored = existingDeed(deedPollId);
        if (stored.isPresent()) {
            DeedPollSupportingDocument existing = stored.get();
            if (!DeedDocumentTypes.name(template).equals(existing.getDocumentType().getName())) {
                // Not reachable through the emails, which are mutually exclusive per case. Logged rather than
                // refused, because the deed that exists may already be signed: see the class comment.
                log.warn("Case {} was asked for its {} deed but already holds {}, which is what it keeps",
                         deedPollId, template.formCode(), existing.getDocumentType().getName());
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
