package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The one term a caseworker typed into the search box.
 *
 * <p>New surface rather than a port -- the Dynamics original had a quick-find box and nothing we hold
 * describes what it searched. It sits beside {@link CaseQuery} and {@link CaseSort} with the same
 * discipline: this says <em>what</em> was searched for and never <em>how</em> a database is asked, so the
 * wire format survives the move to CCD's Elasticsearch indexer. {@link CaseSpecifications} is the only
 * thing that turns one of these into a predicate.
 *
 * <p><strong>One parameter on the existing list, not a search endpoint.</strong> A search has to be
 * bookmarkable and survive the back button, which is the same reason the filter and the conditions are
 * query parameters. It composes with them: {@code ?filter=open&where=type:is:Adult&q=Smith} means all
 * three, so "Smith, in Enroled, received in June" is one URL.
 *
 * <p><strong>A reference exactly, or a name loosely.</strong> Those are the two things a caseworker types
 * into one box, and they need different treatment:
 *
 * <ul>
 *   <li><strong>{@code displayReference}</strong> is {@code text} holding the rendered
 *       {@code {prefix}{year}-{serial}} form, so it takes the case-, hyphen- and whitespace-tolerant
 *       normalisation {@link #normalisedReference()} performs: {@code kbdp2026-10100} and
 *       {@code KBDP202610100} finding the same case is a statement about this column.</li>
 *   <li><strong>{@code referencenumber}</strong> is an {@code integer} holding only the serial component,
 *       so <strong>no</strong> lowering and no hyphen-stripping apply to it. It is matched only when the
 *       whole normalised term reads as an integer ({@link #serial()}), and compared numerically -- a term
 *       that does not parse contributes no predicate at all rather than a cast Postgres would refuse.</li>
 *   <li><strong>The six name columns</strong> are matched as a case-insensitive substring, because a
 *       caseworker searching "Smith" should find "Smithson" and should not have to match the stored
 *       capitalisation.</li>
 * </ul>
 *
 * <p><strong>Not addresses and not narrative.</strong> {@link #NAME_FIELDS} is deliberately six columns
 * and not "every text column": an address or a referral note matching a name is a row a caseworker cannot
 * see why they were shown, and those fields are excluded from the query builder for the same reason.
 * Anything more specific than a name is what {@code where} is for.
 *
 * @param term the term as it was typed, trimmed; empty for a request that searched for nothing
 */
public record CaseSearch(String term) {

    /**
     * What a request with no {@code q} searched for, and what an empty box means.
     */
    public static final CaseSearch NONE = new CaseSearch("");

    /**
     * The six columns a term is matched loosely against, in the order a refusal or a document lists them.
     *
     * <p>Names only -- the applicant's, the one they are leaving behind, their parents' and the
     * representative acting for them. {@code CaseSearchTest} pins every one of them to a stored, readable
     * field, so a column renamed in the schema fails a test rather than a search.
     */
    public static final List<String> NAME_FIELDS = List.of(
        "newForename", "newSurname", "oldForename", "oldSurname", "parents", "representativeName");

    /**
     * The most a search term may carry.
     *
     * <p>Bounded for the reason the page size and the condition count are: the term becomes a
     * {@code like '%…%'} against six columns with no index, so an unbounded one is a way to make the list
     * endpoint expensive with a single request. A hundred characters is far more than any name and any
     * reference.
     */
    public static final int MAX_TERM_LENGTH = 100;

    /** Everything the reference forms tolerate between the parts of a reference: space and hyphen. */
    private static final Pattern REFERENCE_NOISE = Pattern.compile("[\\s-]+");

    public CaseSearch {
        term = term == null ? "" : term.trim();
    }

    /**
     * Reads the {@code q} parameter into a term.
     *
     * <p>A blank term is {@link #NONE} and not an empty search: a box submitted with nothing in it, or with
     * only spaces, is a request for the unnarrowed list, and matching {@code like '%%'} against six columns
     * to answer it would be the same list at six times the cost.
     *
     * <p>An over-long term is truncated rather than refused, which is the choice {@code CasePaging} makes
     * about an over-cap size and for the same reason: the caller gets rows either way, and a 400 on a search
     * box is a dead end where a bounded search is an answer. Nothing depends on the discarded tail -- no
     * name and no reference is a hundred characters long -- so there is nothing for a caller to detect.
     *
     * @param q the raw parameter, or null for a request that sent none
     */
    public static CaseSearch parse(String q) {
        if (q == null || q.isBlank()) {
            return NONE;
        }
        String trimmed = q.trim();
        return new CaseSearch(
            trimmed.length() <= MAX_TERM_LENGTH ? trimmed : trimmed.substring(0, MAX_TERM_LENGTH));
    }

    /** Whether this searches for anything at all. */
    public boolean isEmpty() {
        return term.isEmpty();
    }

    /**
     * The term as {@code displayReference} would have to read to match it: lower case, with the spaces and
     * hyphens taken out.
     *
     * <p>Both forms of the same reference normalise to the same string, which is what makes
     * {@code KBDP202610100} find the case whose stored reference is {@code KBDP2026-10100}. The column is
     * normalised the same way in the predicate, so the comparison is between two normalised values rather
     * than between a normalised term and a stored one.
     */
    public String normalisedReference() {
        return REFERENCE_NOISE.matcher(term).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /**
     * The serial the term names, or empty when it does not name one.
     *
     * <p>{@code referencenumber} holds the serial alone and is an {@code integer}, so only a term that reads
     * as a whole one can be compared with it. {@code kbdp2026-10100} therefore yields nothing here and is
     * matched against {@code displayReference} instead -- which is the column that holds the rendered form.
     */
    public Optional<Integer> serial() {
        String normalised = normalisedReference();
        if (normalised.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.valueOf(normalised));
        } catch (NumberFormatException notASerial) {
            // Not an error: a term that is a name rather than a serial simply contributes no numeric
            // predicate. Refusing here would make every name search a 400.
            return Optional.empty();
        }
    }
}
