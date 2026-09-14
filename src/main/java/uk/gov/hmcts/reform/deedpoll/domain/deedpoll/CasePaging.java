package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;

import java.util.ArrayList;
import java.util.List;

/**
 * Which slice of the case list a request asked for: a page number and a page size.
 *
 * <p>New surface rather than a port. It is a domain type and not an HTTP concern for the reason
 * {@link CaseQuery} and {@link CaseSort} are: the question "the second twenty-five of this result set" has
 * to outlive the thing that answers it, and Postgres today is CCD's Elasticsearch indexer later.
 *
 * <p><strong>The page number is 1-based, and the offset is not published.</strong> A page number appears in
 * a URL a caseworker reads and shares, and "page 1" is the first page to every human who has ever used a
 * paginated list. {@link #index()} is where the zero-based translation happens, once, next to the only
 * caller that needs it.
 *
 * <p><strong>The size cap is not negotiable by the client.</strong> An uncapped page size is how a list
 * endpoint becomes a denial of service against its own database -- one request for every row of a table
 * with no index for the query in front of it. So an over-cap size is clamped to {@link #MAX_SIZE} rather
 * than honoured, and the record's own constructor refuses one, so there is no way in -- not a request, not
 * a later caller in this codebase -- to a page larger than the cap.
 *
 * <p><strong>Clamped for too large, refused for unintelligible.</strong> The two are different mistakes:
 * {@code size=1000} is a caller asking for as much as they can have, and giving them the most that is safe
 * is the answer to that; {@code size=lots} is a caller who cannot be given anything, because there is no
 * reading of it that is not a guess. {@code page=0} is refused for the same reason and not clamped to 1 --
 * a caller who thinks pages start at zero is off by one on every page they ask for after this one, and
 * silently answering page 1 hides that from them.
 *
 * @param page which page, 1-based
 * @param size how many rows on it, never more than {@link #MAX_SIZE}
 */
public record CasePaging(int page, int size) {

    /** The first page, because a page number is 1-based. */
    public static final int FIRST_PAGE = 1;

    /**
     * How many rows a request that does not say gets.
     *
     * <p>Twenty-five is a screenful of a six-column table without a scroll, and it is what the list page
     * renders. A default is needed at all because the alternative -- every row when a caller does not ask
     * -- is the uncapped response the cap exists to prevent, just spelled differently.
     */
    public static final int DEFAULT_SIZE = 25;

    /** The most rows any request can have, whatever it asks for. */
    public static final int MAX_SIZE = 100;

    /** What a request with no paging parameters means. */
    public static final CasePaging FIRST = new CasePaging(FIRST_PAGE, DEFAULT_SIZE);

    /** The summary every refusal from this class carries. */
    private static final String INVALID_PAGING = "Case list paging is not valid";

    /** How much of a caller's token a refusal quotes back, so the response stays bounded. */
    private static final int MAX_QUOTED = 60;

    public CasePaging {
        // A guard and not validation: parse() has already told a caller about anything it could. What this
        // catches is a later caller in this codebase constructing one directly, which is the only remaining
        // way past the cap.
        if (page < FIRST_PAGE) {
            throw new IllegalArgumentException("A page number is 1-based, and was " + page);
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("A page size is 1 to " + MAX_SIZE + ", and was " + size);
        }
    }

    /**
     * Reads the {@code page} and {@code size} parameters into a validated slice.
     *
     * <p>Both are read together and refused together, so a request that got both wrong is told about both
     * -- the same reason {@link CaseQuery#parse} collects its conditions' errors. They are one question
     * ("which rows"), asked with two parameters.
     *
     * @param page the raw {@code page} parameter, or null for a request that sent none
     * @param size the raw {@code size} parameter, or null for a request that sent none
     * @return the slice asked for, or {@link #FIRST} for a request that asked for none
     * @throws InvalidInputError with a {@link FieldError} per unusable parameter
     */
    public static CasePaging parse(String page, String size) {
        List<FieldError> errors = new ArrayList<>();
        int number = number("page", page, FIRST_PAGE, errors);
        int rows = number("size", size, DEFAULT_SIZE, errors);

        if (number < FIRST_PAGE) {
            errors.add(new FieldError("page", "is 1-based, and was " + quoted(page)));
        }
        if (rows < 1) {
            errors.add(new FieldError("size", "takes at least one row, and was " + quoted(size)));
        }
        if (!errors.isEmpty()) {
            throw new InvalidInputError(INVALID_PAGING, errors);
        }
        // Clamped, deliberately silently: the cap is a property of the service and not a mistake the caller
        // made, and the response says what size was applied. See the class comment.
        return new CasePaging(number, Math.min(rows, MAX_SIZE));
    }

    /**
     * The zero-based page index the database is asked for.
     *
     * <p>The one place the 1-based wire number becomes an offset, so nothing else has to remember which
     * convention it is holding.
     */
    public int index() {
        return page - FIRST_PAGE;
    }

    /**
     * One numeric parameter, defaulted when absent and collected as an error when it is not a number.
     *
     * <p>An unreadable value collects its own error and then reads as the default, so the range check below
     * does not report a second failure about a number the caller never sent: {@code page=lots} is one
     * mistake, and being told it is both unintelligible and out of range would be noise.
     */
    private static int number(String name, String raw, int whenAbsent, List<FieldError> errors) {
        if (raw == null || raw.isBlank()) {
            return whenAbsent;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException notANumber) {
            errors.add(new FieldError(name, "takes a whole number, and was given " + quoted(raw)));
            return whenAbsent;
        }
    }

    /** A caller's token, quoted and truncated, safe to put in a message. */
    private static String quoted(String token) {
        if (token == null) {
            return "'none'";
        }
        String bounded = token.length() <= MAX_QUOTED ? token : token.substring(0, MAX_QUOTED) + "…";
        return "'" + bounded + "'";
    }
}
