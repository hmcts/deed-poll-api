package uk.gov.hmcts.reform.deedpoll.domain.savedqueries;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditActor;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntities;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntry;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditTrail;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.SavedQuery;
import uk.gov.hmcts.reform.deedpoll.entity.SavedQuerySelection;
import uk.gov.hmcts.reform.deedpoll.repository.SavedQueryRepository;
import uk.gov.hmcts.reform.deedpoll.repository.SavedQuerySelectionRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Saved and shared case-list queries: the library, and who may change what in it.
 *
 * <p><strong>Not a port.</strong> There is nothing for this in {@code src/test/resources/reference} -- the
 * Dynamics original had saved system views, and {@code ListFilters} is those five ported as fixed filters. So
 * there is no reference service to compare against and none to look for.
 *
 * <p><strong>Every rule about who may do what lives here rather than on the route</strong>, as
 * {@code WitnessService.removeWitness} and {@code NoteService.removeNote} put theirs. The controller states no
 * rule of its own, so a later caller that is not an HTTP route -- a citizen surface, a batch tidy-up -- is held
 * to the same rules rather than to whichever ones its own author remembered.
 *
 * <p><strong>Resolve, then refuse -- which is the opposite order from the witness delete, and deliberately.
 * </strong> A witness delete can check first because its check is a <em>role</em> check: entity-independent, so
 * it can run before the lookup and a caller without the role cannot use the status code to tell an existing
 * witness from a missing one. A saved query's permission is a property of the entity -- whether it is
 * {@code shared}, and whether {@code ownerId} is the caller -- so there is nothing to check until it has been
 * read. Do not attempt to invert it, and do not invent a role that would make inverting it possible.
 *
 * <p>The property that ordering was protecting is still required, and is obtained the other way round:
 *
 * <ul>
 *   <li><strong>Not visible</strong> -- not shared, and not the caller's -- is a {@link NotFoundError} carrying
 *       exactly the message an id that never existed gets, for every operation on the query itself and for
 *       {@link #addSelection}. So no id is discoverable: a caller cannot probe for other people's private
 *       queries by watching status codes. {@link #removeSelection} reports nothing at all rather than a 404,
 *       which discloses no more because its answer does not vary with the id -- see the method for why the
 *       asymmetry is deliberate.</li>
 *   <li><strong>Visible but not editable</strong> -- somebody else's shared query, and the caller is not an
 *       administrator -- is a {@link ForbiddenError}. The caller can already see it, so there is nothing left
 *       to conceal, and a 404 here would be a lie a client cannot act on.</li>
 *   <li><strong>Visible, shared, and the caller is an administrator</strong> is permitted. Owner-only editing
 *       freezes a query the moment its owner goes on leave or changes team, while everyone subscribed carries
 *       on relying on it.</li>
 *   <li><strong>Visible, unshared, and the caller is an administrator but not the owner</strong> cannot arise.
 *       Such a query is not visible to them at all, so it is the 404 case -- the visibility rule is what keeps
 *       the administrator override away from private queries, and there is no second check to write.</li>
 *   <li><strong>Asking to publish without being an administrator</strong> -- {@code shared: true} on a create,
 *       or a patch that would leave it true -- is a {@link ForbiddenError}, and it is a question about the
 *       <em>caller</em> rather than about the query: saving one for yourself is every caseworker's right and
 *       publishing one to the library is an administrator's. Refused before anything is written, so no
 *       half-published row survives a refusal. <strong>Clearing the flag is not gated</strong>: an
 *       administrator who publishes a query and later loses the role still owns it, and withdrawing it from the
 *       library is de-escalation. See {@link SavedQueryPermissions#publishableBy}.</li>
 *   <li><strong>Asking to publish somebody else's unshared query</strong> is the 404 case and not the publish
 *       refusal, because {@link #editable} answers first. A 403 there would confirm the id names something
 *       publishable.</li>
 * </ul>
 *
 * <p><strong>A live reference, not a copy.</strong> Subscribers read the owner's edits, so an edit here changes
 * what everyone who selected the query sees. That is the point -- a shared definition that diverges nine ways
 * in a month is the failure mode of the spreadsheets this service replaces -- and duplication is the escape
 * hatch: a client reads one and posts a new one, which needs no endpoint because there is no invariant to
 * protect.
 *
 * <p><strong>Create, update and delete each record one audit event, with field names and no values.</strong> A
 * saved query belongs to no case, and value rows are disposed by case, so a value recorded here could never be
 * disposed. Selecting and unselecting record nothing: they are one caseworker's own list, not a change to a query.
 */
@Service
public class SavedQueryService {

    private final SavedQueryRepository savedQueries;
    private final SavedQuerySelectionRepository selections;
    private final AuditTrail audit;

    public SavedQueryService(SavedQueryRepository savedQueries, SavedQuerySelectionRepository selections,
                             AuditTrail audit) {
        this.savedQueries = savedQueries;
        this.selections = selections;
        this.audit = audit;
    }

    /**
     * Every query this caller may see -- the shared library plus their own -- by name, each flagged.
     *
     * <p>The caller's selections are read once and matched in memory rather than asked per query: the list is
     * loaded on every page of the admin app, and a flag per row would otherwise be a query per row.
     *
     * <p>Ordered by name, which is v1's answer to ordering. Per-user ordering is a recorded open question and
     * would need a position column on the selection, so it is a known small addition rather than a surprise.
     */
    @Transactional(readOnly = true)
    public List<SavedQueryView> findSavedQueries(CurrentUser caller) {
        String userId = caller.requireUserId();
        Set<UUID> selected = selections.findByIdUserId(userId).stream()
            .map(selection -> selection.getId().getSavedQueryId())
            .collect(Collectors.toUnmodifiableSet());

        return savedQueries.findBySharedTrueOrOwnerIdOrderByNameAsc(userId).stream()
            .map(query -> new SavedQueryView(
                query, selected.contains(query.getId()), SavedQueryPermissions.editableBy(query, caller)))
            .toList();
    }

    /**
     * Saves a new query, owned by the caller.
     *
     * <p><strong>The owner is the token's, never the body's.</strong> {@link CreateSavedQueryInput} has nowhere
     * to put an owner, so a caller cannot post a query attributed to somebody else -- structurally, rather than
     * by a check a later method could omit.
     *
     * <p>{@code ownerName} is captured here and never refreshed, including when the owner edits their own
     * query. A departed owner's query then still says whose it was rather than rendering blank, which is what
     * the column is for; and an administrator amending a shared query cannot become its owner by doing so.
     * Refreshing it only on an owner's own edit would be a second rule for a cosmetic gain.
     *
     * <p>Creating is not selecting. A new query is in the library and in nobody's list, including its owner's,
     * because those are separate statements -- see {@code SavedQuerySelection}.
     */
    @Transactional
    public SavedQueryView createSavedQuery(CreateSavedQueryInput dto, CurrentUser caller) {
        // Before anything is built, so a refusal cannot leave a half-written row. Only asked when the body
        // actually requests publication: saving an unpublished query is every caseworker's right, and checking
        // the role unconditionally would refuse the common case to guard the rare one.
        if (dto.shared()) {
            SavedQueryPermissions.requirePublishableBy(caller);
        }

        SavedQuery query = new SavedQuery();
        query.setName(dto.name());
        query.setDescription(dto.description().orElse(null));
        query.setShared(dto.shared());
        query.setOwnerId(caller.requireUserId());
        query.setOwnerName(caller.name());
        apply(query, dto.definition());
        query.setCreatedBy(caller.name());
        query.setModifiedBy(caller.name());
        SavedQuery saved = savedQueries.save(query);
        audit(AuditAction.SAVED_QUERY_CREATED, caller, saved, created(dto));
        return viewOf(saved, caller);
    }

    /**
     * One saved query, if the caller may see it.
     *
     * @throws NotFoundError if there is no such query, or it is neither shared nor theirs
     */
    @Transactional(readOnly = true)
    public SavedQueryView findSavedQuery(String id, CurrentUser caller) {
        return viewOf(visible(id, caller), caller);
    }

    /**
     * Amends a saved query. The owner, or an administrator if it is shared.
     *
     * <p>Only the fields the patch names are written. A field the patch names as null clears the column where
     * the column is nullable, and is refused by {@link SavedQuerySchemas#parseUpdateSavedQuery} before it
     * reaches here where it is not. An empty patch is accepted and re-stamps {@code modifiedOn} and
     * {@code modifiedBy}, which is what an empty case or witness patch does.
     *
     * <p>The order is one field although it is two columns: clearing it empties both, because a direction
     * without a field is not a state {@code CaseSort} can hold.
     *
     * @throws NotFoundError if there is no such query, or it is neither shared nor theirs
     * @throws ForbiddenError if the caller can see it but may not amend it
     */
    @Transactional
    public SavedQueryView updateSavedQuery(String id, UpdateSavedQueryInput dto, CurrentUser caller) {
        SavedQuery query = editable(id, caller);

        // **Only a patch that would leave the query published is gated**, which is narrower than "names
        // `shared`" and deliberately so. Clearing the flag is de-escalation, and an Administrator who published
        // a query and later lost the role still owns it -- gating the clear would strand it in the library with
        // only another Administrator able to withdraw it. After `editable` for the reason the whole method is
        // ordered that way: a caller who may not touch this query at all gets the 404 or the 403 it already
        // earns, rather than a refusal that confirms the query exists and is publishable.
        if (Boolean.TRUE.equals(dto.shared().value())) {
            SavedQueryPermissions.requirePublishableBy(caller);
        }

        dto.name().ifPresent(query::setName);
        dto.description().ifPresent(query::setDescription);
        dto.shared().ifPresent(query::setShared);
        dto.filter().ifPresent(query::setFilter);
        dto.conditions().ifPresent(query::setConditions);
        dto.sort().ifPresent(sort -> {
            query.setSortField(sort == null ? null : sort.field());
            query.setSortDirection(sort == null ? null : sort.direction().wire());
        });
        dto.searchTerm().ifPresent(query::setSearchTerm);

        query.setModifiedBy(caller.name());
        // Stamped here rather than left to @PreUpdate, which Hibernate only fires when its dirty check finds a
        // changed property -- so an empty patch, or one resending the stored values, would leave modifiedOn
        // where it was. The same reason WitnessService.updateWitness stamps it.
        query.setModifiedOn(AuditClock.now());
        SavedQuery saved = savedQueries.save(query);
        audit(AuditAction.SAVED_QUERY_UPDATED, caller, saved, updated(dto));
        return viewOf(saved, caller);
    }

    /**
     * Deletes a saved query. The owner, or an administrator if it is shared.
     *
     * <p>Permitted even where others have selected it -- decision 2 -- and their selection rows go with it,
     * through the foreign key's {@code ON DELETE CASCADE}. An orphaned selection would resurface as a broken
     * navigation entry in every one of their lists. Whether the owner should be <em>warned</em> that three
     * people have it in their list is a recorded open question and needs a count this service does not expose.
     *
     * <p>Deleting twice 404s on the second call rather than answering 204 again, because absence is reported by
     * throwing -- consistent with the reads, and unlike the selection sub-resource, whose delete is idempotent
     * because unselecting something already unselected is not a mistake.
     *
     * @throws NotFoundError if there is no such query, or it is neither shared nor theirs
     * @throws ForbiddenError if the caller can see it but may not delete it
     */
    @Transactional
    public void removeSavedQuery(String id, CurrentUser caller) {
        SavedQuery query = editable(id, caller);
        savedQueries.delete(query);
        audit(AuditAction.SAVED_QUERY_DELETED, caller, query, List.of());
    }

    private void audit(AuditAction action, CurrentUser caller, SavedQuery query, List<String> fields) {
        audit.record(AuditEntry.of(action, AuditActor.of(caller), AuditEntities.SAVED_QUERY,
                                   query.getId().toString(), null).withFields(fields),
                     List.of());
    }

    /** The fields a create set: the name and the flag always, the rest where the body carried them. */
    private static List<String> created(CreateSavedQueryInput dto) {
        List<String> fields = new ArrayList<>(List.of("name"));
        dto.description().ifPresent(value -> fields.add("description"));
        fields.add("shared");
        SavedQueryDefinition definition = dto.definition();
        definition.filter().ifPresent(value -> fields.add("filter"));
        if (!definition.conditions().isEmpty()) {
            fields.add("conditions");
        }
        definition.sort().ifPresent(value -> fields.add("sort"));
        definition.searchTerm().ifPresent(value -> fields.add("searchTerm"));
        return fields;
    }

    /** The fields a patch named, in the order the patch declares them. */
    private static List<String> updated(UpdateSavedQueryInput dto) {
        List<String> fields = new ArrayList<>();
        if (dto.name().present()) {
            fields.add("name");
        }
        if (dto.description().present()) {
            fields.add("description");
        }
        if (dto.shared().present()) {
            fields.add("shared");
        }
        if (dto.filter().present()) {
            fields.add("filter");
        }
        if (dto.conditions().present()) {
            fields.add("conditions");
        }
        if (dto.sort().present()) {
            fields.add("sort");
        }
        if (dto.searchTerm().present()) {
            fields.add("searchTerm");
        }
        return fields;
    }

    /**
     * Adds a query to this caller's own list, idempotently.
     *
     * <p><strong>Visibility is the whole permission, and deliberately not edit rights.</strong> Amending a query
     * is the owner's right; choosing to see it is every caseworker's, which is the point of selection being a
     * sub-resource rather than a field. So a caller may select somebody else's shared query, which is the case
     * the library exists for, and gets the ordinary 404 for one that is neither shared nor theirs -- a caller
     * must not be able to discover a private query by selecting it.
     *
     * <p>A second call is not a conflict: it writes nothing and answers as the first did. Checked rather than
     * left to an upsert because the row is <em>only</em> its key -- there is no column for a second write to
     * update -- so "already selected" is the whole of the state and a repeat is genuinely nothing to do.
     *
     * <p><strong>A selection is not a grant.</strong> The row records a choice; what the caller may see is still
     * decided by {@link SavedQueryPermissions#visibleTo}, read afresh on every list. So a query unshared by its
     * owner after being selected stops appearing for the subscriber, and the row simply stays -- see
     * {@link #removeSelection} for why it is left rather than tidied.
     *
     * @throws NotFoundError if there is no such query, or it is neither shared nor theirs
     */
    @Transactional
    public void addSelection(String id, CurrentUser caller) {
        SavedQuery query = visible(id, caller);
        String userId = caller.requireUserId();
        if (!selections.existsByIdSavedQueryIdAndIdUserId(query.getId(), userId)) {
            selections.save(new SavedQuerySelection(query.getId(), userId));
        }
    }

    /**
     * Removes a query from this caller's own list, idempotently. Unselecting something not selected is not an
     * error, so this reports nothing and cannot fail.
     *
     * <p><strong>No visibility check, which is an asymmetry with {@link #addSelection} and is the point rather
     * than an oversight.</strong> The row is keyed by the query and <em>this caller</em>, so the delete reaches
     * one row that is theirs by construction and no check could make it safer: the scoping is in the key, not in
     * a guard. What a check would add is a state a caller cannot leave. A query unshared after being selected
     * leaves a row behind that its owner may re-share at any time, resurfacing the query in a list the caller
     * removed it from months earlier -- and requiring visibility to unselect is exactly what would stop them
     * clearing it, because the query they need to unselect is the one they can no longer see.
     *
     * <p>Nothing is disclosed by that. The answer does not vary: an unknown id, an id that is not an identifier,
     * somebody else's private query and a query the caller had genuinely selected all report the same nothing,
     * so there is no status code to probe with. The caller is still resolved first, so an unidentified one is
     * refused rather than deleting against a null user.
     */
    @Transactional
    public void removeSelection(String id, CurrentUser caller) {
        String userId = caller.requireUserId();
        identifier(id).ifPresent(queryId -> selections.deleteByIdSavedQueryIdAndIdUserId(queryId, userId));
    }

    /**
     * A stored query the caller may see, or a 404 that says nothing about which of the two reasons it is.
     *
     * <p>An id that is not a UUID at all is the same 404 rather than a 400. It is an id nothing could ever have
     * been stored under, so "not found" is the true answer, and it is the answer a mistyped case id already
     * gets on every other route here -- where the column is text, so the lookup simply misses.
     */
    private SavedQuery visible(String id, CurrentUser caller) {
        return identifier(id)
            .flatMap(savedQueries::findById)
            .filter(query -> SavedQueryPermissions.visibleTo(query, caller))
            .orElseThrow(() -> new NotFoundError("Saved query " + id + " not found"));
    }

    /** A stored query the caller may amend or delete: visible first, then editable. */
    private SavedQuery editable(String id, CurrentUser caller) {
        SavedQuery query = visible(id, caller);
        SavedQueryPermissions.requireEditableBy(query, caller);
        return query;
    }

    private SavedQueryView viewOf(SavedQuery query, CurrentUser caller) {
        boolean selected = selections.existsByIdSavedQueryIdAndIdUserId(query.getId(), caller.requireUserId());
        return new SavedQueryView(query, selected, SavedQueryPermissions.editableBy(query, caller));
    }

    /** The stored form of a validated definition: the wire strings as they came, and the sort decomposed. */
    private static void apply(SavedQuery query, SavedQueryDefinition definition) {
        query.setFilter(definition.filter().orElse(null));
        query.setConditions(List.copyOf(definition.conditions()));
        query.setSortField(definition.sortField());
        query.setSortDirection(definition.sortDirection());
        query.setSearchTerm(definition.searchTerm().orElse(null));
    }

    /** The id as a UUID, or empty for anything that is not one -- which no row can be stored under. */
    private static Optional<UUID> identifier(String id) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException notAnIdentifier) {
            return Optional.empty();
        }
    }
}
