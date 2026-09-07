package uk.gov.hmcts.reform.deedpoll.domain.witnesses;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollWitness;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollWitnessRepository;

import java.util.List;

/**
 * Nested CRUD for a case's witnesses and declarants.
 *
 * <p>Ported from {@code reference/ts/witnesses/service.ts}. Every operation is scoped to a parent case,
 * so a witness can never be read or mutated outside the case it belongs to. The parent's existence is
 * checked on create -- the foreign key would fail anyway, but a 404 says "no such case" where a
 * constraint violation says "23503".
 *
 * <p><strong>How the reference's belt-and-braces scoping ports.</strong> The reference applies the scope
 * twice on the write paths -- a {@code findFirst} guard that produces the {@code NotFoundError}, and
 * {@code deedPollId} repeated in the {@code where} of the {@code update} / {@code delete} that follow --
 * and its own comment points at the notes service for why. Here, as in {@code NoteService}, that becomes
 * structural: the write goes through the entity the guard loaded, that entity was fetched by id
 * <em>and</em> parent, and {@link DeedPollWitnessRepository} offers no finder that omits the parent. There
 * is no path that writes a witness fetched by id alone.
 *
 * <p><strong>No audit columns.</strong> Unlike a note, {@code deedpoll_witness} has {@code createdOn} and
 * {@code modifiedOn} but no {@code createdBy} / {@code modifiedBy} -- see {@code DeedPollWitness} in
 * {@code reference/deed-poll.prisma} -- so no author is threaded through these methods and there is no
 * {@code WitnessAuthor} to match {@code NoteAuthor}. Attribution for a witness change is the case's own
 * {@code modifiedBy} plus the declaration on file.
 *
 * <p>The service returns entities, not view models, exactly as the reference does. The reference has no
 * witnesses view model at all: witnesses are rendered as part of the case detail page, whose tab is
 * {@code DetailView}'s job, and the caseworker API maps to its own DTOs.
 *
 * <p><strong>Nothing in plan 01 calls the three write methods.</strong> The caseworker surface is eleven
 * operations and witness writes are deliberately not among them -- {@code deed-poll-admin} has no consumer
 * for them. They are ported because the reference has them and the source form does, so that when a
 * consumer does arrive it finds a scoped implementation rather than inventing an unscoped one.
 */
@Service
public class WitnessService {

    private final DeedPollRepository deedPolls;
    private final DeedPollWitnessRepository witnesses;

    public WitnessService(DeedPollRepository deedPolls, DeedPollWitnessRepository witnesses) {
        this.deedPolls = deedPolls;
        this.witnesses = witnesses;
    }

    /**
     * Adds a witness to a case.
     *
     * @throws NotFoundError if there is no such case
     */
    @Transactional
    public DeedPollWitness createWitness(String deedPollId, CreateWitnessInput dto) {
        DeedPoll parent = requireCase(deedPollId);
        DeedPollWitness witness = new DeedPollWitness();
        witness.setDeedPoll(parent);
        witness.setWitnessName(dto.witnessName());
        witness.setWitnessAddress(dto.witnessAddress());
        witness.setWitnessOccupation(dto.witnessOccupation());
        witness.setDeclarant(dto.declarant());
        return witnesses.save(witness);
    }

    /**
     * Witnesses for a case, oldest first.
     *
     * <p>The parent is checked, so a missing case is a 404 rather than an empty list -- the same reasoning
     * {@code NoteService.findNotes} sets out. Filtering by {@code deedPollId} alone answers an empty list
     * both for a case id that does not exist and for a real case with no witnesses; those are different
     * facts, only one of them is true, and a mistyped id would be told the case has no witnesses. The
     * check costs one indexed primary-key lookup.
     *
     * @throws NotFoundError if there is no such case
     */
    @Transactional(readOnly = true)
    public List<DeedPollWitness> findWitnesses(String deedPollId) {
        requireCase(deedPollId);
        return witnesses.findByDeedPollIdOrderByCreatedOnAsc(deedPollId);
    }

    /**
     * One witness of one case.
     *
     * @throws NotFoundError if there is no such witness, or it belongs to another case
     */
    @Transactional(readOnly = true)
    public DeedPollWitness findWitness(String deedPollId, String id) {
        return witnesses.findByIdAndDeedPollId(id, deedPollId)
            .orElseThrow(() -> new NotFoundError("Witness " + id + " not found"));
    }

    /**
     * Amends a witness.
     *
     * <p>Only the fields the patch names are written, which is what makes an omitted field different from
     * a cleared one -- and here a cleared one is refused by {@link WitnessSchemas#parseUpdateWitness}
     * before it reaches this method, because none of the four columns is nullable.
     *
     * @throws NotFoundError if there is no such witness, or it belongs to another case
     */
    @Transactional
    public DeedPollWitness updateWitness(String deedPollId, String id, UpdateWitnessInput dto) {
        DeedPollWitness witness = findWitness(deedPollId, id);
        dto.witnessName().ifPresent(witness::setWitnessName);
        dto.witnessAddress().ifPresent(witness::setWitnessAddress);
        dto.witnessOccupation().ifPresent(witness::setWitnessOccupation);
        dto.declarant().ifPresent(witness::setDeclarant);
        // Stamped here rather than left to @PreUpdate, which Hibernate only fires when its dirty check
        // finds a changed property -- so an empty patch, or one resending the values already stored, would
        // leave modifiedOn where it was. The reference's `prisma.update` writes @updatedAt on every call.
        witness.setModifiedOn(AuditClock.now());
        return witnesses.save(witness);
    }

    /**
     * Deletes a witness.
     *
     * @throws NotFoundError if there is no such witness, or it belongs to another case
     */
    @Transactional
    public void removeWitness(String deedPollId, String id) {
        witnesses.delete(findWitness(deedPollId, id));
    }

    private DeedPoll requireCase(String deedPollId) {
        return deedPolls.findById(deedPollId)
            .orElseThrow(() -> new NotFoundError("DeedPoll " + deedPollId + " not found"));
    }
}
