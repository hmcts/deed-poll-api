package uk.gov.hmcts.reform.deedpoll.domain.savedqueries;

import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.PatchField;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseQuery;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseSearch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseSort;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.ListFilters;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shape validation for a saved-query create payload and a saved-query patch.
 *
 * <p><strong>Not a port.</strong> Saved queries are new behaviour, so unlike {@code DeedPollSchemas},
 * {@code NoteSchemas} and {@code WitnessSchemas} there is no {@code reference/ts} schema behind this one and
 * none to look for.
 *
 * <p><strong>Validated on write, not on read.</strong> A query whose field was later renamed fails when it
 * is run, with the same 400 a hand-edited URL gets. Re-validating on read would put the cost of every rename
 * on every list load, and the caseworker would see it as their saved query having silently vanished.
 *
 * <p><strong>Every validator here already exists, and none of them is reimplemented.</strong> That is the
 * whole design of the stored shape rather than a convenience:
 *
 * <ul>
 *   <li><strong>Conditions</strong> -- {@link CaseQuery#parse}, called on the wire strings exactly as they
 *       are stored. It is what validates a live {@code where} parameter, so a saved query cannot accept a
 *       condition the list would refuse, and the 20-condition cap applies <em>because {@code CaseQuery}
 *       applies it</em> and not via a check here that could drift from it.</li>
 *   <li><strong>Sort</strong> -- {@link CaseSort#parse}, given the recomposed {@code field:direction}
 *       string. The two columns are decomposed for storage only; recomposing and handing the wire form to
 *       the real parser is what keeps {@code CaseSort.sortableFields()} the single authority on which
 *       fields can be ordered by.</li>
 *   <li><strong>Filter</strong> -- {@link ListFilters#filterFor}, against {@code LIST_FILTERS}.</li>
 *   <li><strong>The search term's length</strong> -- {@link CaseSearch#MAX_TERM_LENGTH}, the cap the live
 *       search itself applies. Bounded by the same constant so a saved query cannot store a term the list
 *       would truncate when it ran it, which would make the stored view and the view it produces differ.</li>
 * </ul>
 *
 * <p><strong>The refusals those validators raise are carried through with their field names unchanged.</strong>
 * A bad condition stays addressed to {@code where.0} and a bad order to {@code sort}, which are the
 * addresses a query builder already renders against its own controls -- the client editing a saved query is
 * the client that built the live one, and giving the same mistake two addresses depending on which endpoint
 * received it would make it handle each separately for no gain. Their errors are merged into this class's,
 * so a payload with a misspelled name and a misspelled field reports both at once rather than one per round
 * trip.
 *
 * <p><strong>An unknown filter key is refused here, where the case list falls back to "all".</strong> The
 * two are different situations rather than an inconsistency: a list URL may have been bookmarked against an
 * older version and showing everything is a safe answer to that, whereas a saved query is written once and
 * read for months, so one that silently means "all" is a wrong view presented as a saved one -- and there is
 * a caseworker in the act of saving it who can be told.
 *
 * <p>Unknown keys are stripped rather than reported, as elsewhere: only the names below are read, and
 * neither input record has anywhere to put anything else. Notably that includes {@code page}, which is not
 * part of a view, and {@code ownerId} -- which comes from the token, so a caller cannot post a query
 * attributed to somebody else.
 */
public final class SavedQuerySchemas {

    /**
     * {@code name}'s cap.
     *
     * <p>Bounded here rather than only in the UI because the name is user text rendered inside a navigation
     * bar in another service, and the column is unbounded {@code text}: without a cap the only limit is the
     * request body size, and a query named with a paragraph would break the layout of every list page it
     * appears on for every user who selected it.
     */
    public static final int MAX_NAME_LENGTH = 100;

    /**
     * {@code description}'s cap, wider than the name because it is prose rather than a label.
     */
    public static final int MAX_DESCRIPTION_LENGTH = 500;

    /** The summary every refusal from this class carries; the per-field detail is in the errors. */
    private static final String INVALID_SAVED_QUERY = "Saved query is not valid";

    private static final String NAME = "name";
    private static final String DESCRIPTION = "description";
    private static final String SHARED = "shared";
    private static final String FILTER = "filter";
    private static final String CONDITIONS = "conditions";
    private static final String SORT_FIELD = "sortField";
    private static final String SORT_DIRECTION = "sortDirection";
    private static final String SEARCH_TERM = "searchTerm";

    private SavedQuerySchemas() {
    }

    /**
     * Validates a create payload.
     *
     * <p>{@code name} is the only required field. A query that narrows nothing is legitimate -- "everything,
     * newest first" is a view somebody wants a tab for -- so an otherwise empty payload is accepted.
     *
     * @throws InvalidInputError with one entry per offending field, including the entries
     *     {@link CaseQuery#parse} and {@link CaseSort#parse} raise under their own field names
     */
    public static CreateSavedQueryInput parseCreateSavedQuery(Map<String, Object> raw) {
        List<FieldError> errors = new ArrayList<>();

        String name = requiredName(raw, errors);
        Optional<String> description = boundedText(raw, DESCRIPTION, MAX_DESCRIPTION_LENGTH, errors);
        Optional<Boolean> shared = flag(raw, errors);
        Optional<String> filter = filterKey(raw, errors);
        List<String> conditions = conditions(raw, errors).orElse(List.of());
        Optional<CaseSort> sort = sort(raw, errors);
        Optional<String> searchTerm = boundedText(raw, SEARCH_TERM, CaseSearch.MAX_TERM_LENGTH, errors);

        refuseIfAny(errors);
        return new CreateSavedQueryInput(
            name,
            description,
            shared.orElse(false),
            new SavedQueryDefinition(filter, conditions, sort, searchTerm));
    }

    /**
     * Validates a patch. An empty patch is accepted and changes nothing; every field that IS present gets
     * the create rule.
     *
     * <p>A field present as null is a clear where the column is nullable, and refused where it is not --
     * {@code name}, {@code shared} and {@code conditions}. Better a validation error naming the field than a
     * database error naming the constraint, which is the choice every other schema here makes.
     *
     * <p><strong>The order is one field although it is two columns.</strong> Sending {@code sortDirection}
     * alone is refused rather than merged with whatever field the stored query happens to name: a direction
     * is only meaningful against a field, and {@link CaseSort} is validated as a whole, so a half patch
     * would have nothing to validate against.
     *
     * @throws InvalidInputError with one entry per present-but-unacceptable field
     */
    public static UpdateSavedQueryInput parseUpdateSavedQuery(Map<String, Object> raw) {
        List<FieldError> errors = new ArrayList<>();

        PatchField<String> name = patchedName(raw, errors);
        PatchField<String> description = patchedText(raw, DESCRIPTION, MAX_DESCRIPTION_LENGTH, errors);
        PatchField<Boolean> shared = patchedFlag(raw, errors);
        PatchField<String> filter = patchedFilter(raw, errors);
        PatchField<List<String>> conditions = patchedConditions(raw, errors);
        PatchField<CaseSort> sort = patchedSort(raw, errors);
        PatchField<String> searchTerm = patchedText(raw, SEARCH_TERM, CaseSearch.MAX_TERM_LENGTH, errors);

        refuseIfAny(errors);
        return new UpdateSavedQueryInput(name, description, shared, filter, conditions, sort, searchTerm);
    }

    // --- name ---

    private static String requiredName(Map<String, Object> raw, List<FieldError> errors) {
        Object value = raw.get(NAME);
        if (value == null) {
            // Absent and explicitly null take the same message: what the caller has to do about it is
            // identical, and the column can hold neither.
            errors.add(new FieldError(NAME, NAME + " is required"));
            return null;
        }
        return acceptedName(value, errors).orElse(null);
    }

    private static PatchField<String> patchedName(Map<String, Object> raw, List<FieldError> errors) {
        if (!raw.containsKey(NAME)) {
            return PatchField.absent();
        }
        Object value = raw.get(NAME);
        if (value == null) {
            errors.add(new FieldError(NAME, NAME + " cannot be null"));
            return PatchField.absent();
        }
        return acceptedName(value, errors).map(PatchField::of).orElseGet(PatchField::absent);
    }

    /**
     * The trimmed name, or empty with the reason recorded.
     *
     * <p>Trimmed before both checks and stored trimmed, for the reason the notes payload trims: without it
     * {@code "   "} smuggles a blank name past the emptiness check, and a navigation entry rendering as
     * nothing is not something the caseworker who saved it can diagnose.
     */
    private static Optional<String> acceptedName(Object value, List<FieldError> errors) {
        if (!(value instanceof String text)) {
            errors.add(new FieldError(NAME, NAME + " must be text"));
            return Optional.empty();
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            errors.add(new FieldError(NAME, NAME + " is required"));
            return Optional.empty();
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            errors.add(new FieldError(NAME, NAME + " must be " + MAX_NAME_LENGTH + " characters or fewer"));
            return Optional.empty();
        }
        return Optional.of(trimmed);
    }

    // --- description and searchTerm: optional bounded text, where blank means none ---

    /**
     * An optional text field, trimmed, absent when the caller sent nothing or sent only whitespace.
     *
     * <p>Blank becomes absent rather than an empty string, because the two would otherwise be different
     * stored values that no reader can tell apart: a description of {@code ""} renders as no description,
     * and a search term of {@code ""} narrows nothing, so storing either would be a value that lies about
     * having been set.
     *
     * @param maxLength the cap, or 0 for none
     */
    private static Optional<String> boundedText(
        Map<String, Object> raw, String field, int maxLength, List<FieldError> errors) {

        Object value = raw.get(field);
        if (value == null) {
            return Optional.empty();
        }
        return acceptedText(field, value, maxLength, errors);
    }

    private static PatchField<String> patchedText(
        Map<String, Object> raw, String field, int maxLength, List<FieldError> errors) {

        if (!raw.containsKey(field)) {
            return PatchField.absent();
        }
        Object value = raw.get(field);
        if (value == null) {
            return PatchField.cleared();
        }
        Optional<String> accepted = acceptedText(field, value, maxLength, errors);
        // A blank patch clears, which is the same statement the create path makes by dropping it: a caller
        // emptying the box means "this has no description now", not "leave the old one".
        return accepted.map(PatchField::of).orElseGet(PatchField::cleared);
    }

    private static Optional<String> acceptedText(
        String field, Object value, int maxLength, List<FieldError> errors) {

        if (!(value instanceof String text)) {
            errors.add(new FieldError(field, field + " must be text"));
            return Optional.empty();
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        if (maxLength > 0 && trimmed.length() > maxLength) {
            errors.add(new FieldError(field, field + " must be " + maxLength + " characters or fewer"));
            return Optional.empty();
        }
        return Optional.of(trimmed);
    }

    // --- shared ---

    private static Optional<Boolean> flag(Map<String, Object> raw, List<FieldError> errors) {
        if (!raw.containsKey(SHARED)) {
            return Optional.empty();
        }
        Object value = raw.get(SHARED);
        if (value == null) {
            // The column has a default and no null, so accepting one here would only move the failure.
            errors.add(new FieldError(SHARED, SHARED + " cannot be null"));
            return Optional.empty();
        }
        if (value instanceof Boolean set) {
            return Optional.of(set);
        }
        // Refused rather than coerced, as the witness declarant is: publishing a query is visible to every
        // other caseworker, so a truthy string quietly read as true is a disclosure rather than a typo.
        errors.add(new FieldError(SHARED, SHARED + " must be true or false"));
        return Optional.empty();
    }

    private static PatchField<Boolean> patchedFlag(Map<String, Object> raw, List<FieldError> errors) {
        if (!raw.containsKey(SHARED)) {
            return PatchField.absent();
        }
        return flag(raw, errors).map(PatchField::of).orElseGet(PatchField::absent);
    }

    // --- filter ---

    private static Optional<String> filterKey(Map<String, Object> raw, List<FieldError> errors) {
        Object value = raw.get(FILTER);
        if (value == null) {
            return Optional.empty();
        }
        return acceptedFilter(value, errors);
    }

    private static PatchField<String> patchedFilter(Map<String, Object> raw, List<FieldError> errors) {
        if (!raw.containsKey(FILTER)) {
            return PatchField.absent();
        }
        Object value = raw.get(FILTER);
        if (value == null) {
            return PatchField.cleared();
        }
        return acceptedFilter(value, errors).map(PatchField::of).orElseGet(PatchField::cleared);
    }

    /**
     * The filter key, checked against {@code LIST_FILTERS} through the resolver the list itself uses.
     *
     * <p>{@link ListFilters#filterFor} falls back to the first filter for a key it does not know, so
     * "unknown" is read off the fallback rather than by testing the key against a set restated here -- which
     * is what keeps {@code LIST_FILTERS} the one statement of which filters exist.
     */
    private static Optional<String> acceptedFilter(Object value, List<FieldError> errors) {
        if (!(value instanceof String key)) {
            errors.add(new FieldError(FILTER, FILTER + " must be text"));
            return Optional.empty();
        }
        String trimmed = key.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        ListFilters.ListFilter resolved = ListFilters.filterFor(trimmed);
        if (!resolved.key().equals(trimmed)) {
            errors.add(new FieldError(FILTER, FILTER + " names no case list filter: " + quoted(trimmed)));
            return Optional.empty();
        }
        return Optional.of(trimmed);
    }

    // --- conditions ---

    /**
     * The conditions as they will be stored, having been run through {@link CaseQuery#parse}.
     *
     * <p>The parsed query is deliberately discarded: what is stored is the wire strings, and {@code parse}
     * is called for its refusals rather than its result. Storing what it produced would be storing a
     * structured form, which is the shape decision 6 of the plan exists to avoid.
     *
     * @return the strings to store, or empty when the field was absent or unacceptable
     */
    private static Optional<List<String>> conditions(Map<String, Object> raw, List<FieldError> errors) {
        Object value = raw.get(CONDITIONS);
        if (value == null) {
            return Optional.empty();
        }
        return acceptedConditions(value, errors);
    }

    private static PatchField<List<String>> patchedConditions(
        Map<String, Object> raw, List<FieldError> errors) {

        if (!raw.containsKey(CONDITIONS)) {
            return PatchField.absent();
        }
        Object value = raw.get(CONDITIONS);
        if (value == null) {
            // NOT NULL, and an empty array is how a caller says "ask nothing" -- so a null here is a client
            // bug rather than a clear, and it is worth saying so.
            errors.add(new FieldError(CONDITIONS, CONDITIONS + " cannot be null; send an empty list instead"));
            return PatchField.absent();
        }
        return acceptedConditions(value, errors).map(PatchField::of).orElseGet(PatchField::absent);
    }

    private static Optional<List<String>> acceptedConditions(Object value, List<FieldError> errors) {
        Optional<List<String>> wire = wireStrings(value);
        if (wire.isEmpty()) {
            errors.add(new FieldError(CONDITIONS, CONDITIONS + " must be a list of field:operator:value text"));
            return Optional.empty();
        }
        List<String> conditions = wire.get();
        try {
            CaseQuery.parse(conditions);
        } catch (InvalidInputError refused) {
            // Carried through with the field names CaseQuery chose -- where.0, and `where` itself for the
            // condition cap -- so the same mistake has the same address whichever endpoint received it.
            errors.addAll(refused.errors());
            return Optional.empty();
        }
        return Optional.of(conditions);
    }

    /**
     * The value as a list of strings, or empty if it is not one.
     *
     * <p>A bare string is refused rather than read as a single condition. It would be a convenience with a
     * cost: a client sending one condition would then store a different shape from one sending two, and the
     * round trip that makes a saved query a list URL again depends on there being one shape.
     */
    private static Optional<List<String>> wireStrings(Object value) {
        Collection<?> raw;
        if (value instanceof Collection<?> collection) {
            raw = collection;
        } else if (value instanceof Object[] array) {
            raw = List.of(array);
        } else {
            return Optional.empty();
        }
        List<String> strings = new ArrayList<>();
        for (Object element : raw) {
            if (!(element instanceof String condition)) {
                return Optional.empty();
            }
            strings.add(condition);
        }
        return Optional.of(List.copyOf(strings));
    }

    // --- sort ---

    private static Optional<CaseSort> sort(Map<String, Object> raw, List<FieldError> errors) {
        Object field = raw.get(SORT_FIELD);
        Object direction = raw.get(SORT_DIRECTION);
        if (field == null) {
            if (direction != null) {
                errors.add(directionWithoutField());
            }
            // Absent rather than CaseSort.NEWEST_FIRST: a query that names no order lets the list apply
            // whatever its default is, where storing the default would pin today's choice forever.
            return Optional.empty();
        }
        return acceptedSort(field, direction, errors);
    }

    private static PatchField<CaseSort> patchedSort(Map<String, Object> raw, List<FieldError> errors) {
        boolean fieldGiven = raw.containsKey(SORT_FIELD);
        boolean directionGiven = raw.containsKey(SORT_DIRECTION);
        if (!fieldGiven && !directionGiven) {
            return PatchField.absent();
        }
        Object field = raw.get(SORT_FIELD);
        Object direction = raw.get(SORT_DIRECTION);
        if (field == null) {
            if (direction != null) {
                errors.add(directionWithoutField());
                return PatchField.absent();
            }
            // Both null, or the direction alone cleared alongside a null field: the order is removed, and
            // both columns go with it because they are one value.
            return PatchField.cleared();
        }
        return acceptedSort(field, direction, errors).map(PatchField::of).orElseGet(PatchField::absent);
    }

    /**
     * The order, validated by recomposing the wire string and handing it to {@link CaseSort#parse}.
     *
     * <p>Recomposed rather than checked in parts. The parser knows three distinct refusals -- a misspelling,
     * a derived field with no column, and a multiselect that holds several values at once -- and checking
     * {@code sortableFields()} by hand here would collapse all three into "not sortable" while duplicating
     * the set it reads.
     */
    private static Optional<CaseSort> acceptedSort(Object field, Object direction, List<FieldError> errors) {
        if (!(field instanceof String name)) {
            errors.add(new FieldError(SORT_FIELD, SORT_FIELD + " must be text"));
            return Optional.empty();
        }
        if (direction != null && !(direction instanceof String)) {
            errors.add(new FieldError(SORT_DIRECTION, SORT_DIRECTION + " must be text"));
            return Optional.empty();
        }
        String wire = direction == null ? name.trim() : name.trim() + ":" + ((String) direction).trim();
        try {
            return Optional.of(CaseSort.parse(wire));
        } catch (InvalidInputError refused) {
            errors.addAll(refused.errors());
            return Optional.empty();
        }
    }

    private static FieldError directionWithoutField() {
        return new FieldError(SORT_FIELD, SORT_FIELD + " is required when " + SORT_DIRECTION + " is given");
    }

    private static void refuseIfAny(List<FieldError> errors) {
        if (!errors.isEmpty()) {
            throw new InvalidInputError(INVALID_SAVED_QUERY, errors);
        }
    }

    /** A caller's token, quoted and truncated, safe to put in a message. */
    private static String quoted(String token) {
        int maxQuoted = 60;
        String bounded = token.length() <= maxQuoted ? token : token.substring(0, maxQuoted) + "…";
        return "'" + bounded + "'";
    }
}
