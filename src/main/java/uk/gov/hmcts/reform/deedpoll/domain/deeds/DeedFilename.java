package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

import java.util.Locale;

/**
 * What the file is called on the applicant's own computer.
 *
 * <p><strong>Not the storage key.</strong> {@code LocalFilesystemDocumentStore} keys blobs by UUID for good
 * reasons of its own, and the download's {@code Content-Disposition} offers back the row's
 * {@code originalName} -- so if that were left as the key, an applicant would find
 * {@code 7c9f1e52-....docx} in their downloads folder with nothing to tell it apart from any other document
 * the court sent them. This is the one document in the service whose name nobody else supplies: an uploaded
 * file arrives with the name the caseworker's own machine gave it, and a generated one has to be named here.
 *
 * <p><strong>Three parts, in the order that reads.</strong> The court's form code as the court writes it,
 * because the email carrying the file names it ("LOC020"); what the document is, in words, because a form code
 * alone means nothing to the applicant; and the case reference as the applicant has seen it, because they
 * quote it in every dealing with the court and it is what tells one household's two deeds apart. The extension
 * comes off {@link DeedOutputFormat}, never a literal, so a later PDF step changes it in one place
 * (decision 5).
 *
 * <p><strong>Hyphenated ASCII, deliberately.</strong> The file travels through a Notify attachment, an email
 * client and whatever filesystem the applicant has, and a name that survives all three without quoting is
 * worth more than a prettier one with spaces in it.
 */
final class DeedFilename {

    /** Everything that is not a letter, a digit or a hyphen becomes a separator. */
    private static final String NOT_FILENAME_SAFE = "[^A-Za-z0-9]+";

    private static final String SEPARATOR = "-";

    private DeedFilename() {
    }

    /**
     * The filename for one case's copy of one deed.
     *
     * <p>A case with no {@code displayReference} -- a seeded or imported one -- gets the name without it,
     * rather than a trailing separator or the word "null". The reference keeps its own upper case, because that
     * is how the applicant has seen it, but it is cleaned rather than trusted: it is generated as
     * {@code KBDP2026-00001} and needs nothing doing to it today, yet it is a database column, and a column is
     * exactly what gets seeded or migrated with something else in it. A name assembled from a column is also
     * the shortest route to a path in a response header, so nothing that is not a letter, a digit or a hyphen
     * reaches it.
     */
    static String of(DeedPoll deedPoll, GeneratedDeed deed) {
        String document = deed.template().formCode()
            + SEPARATOR
            + clean(deed.template().displayName()).toLowerCase(Locale.UK);
        String reference = clean(deedPoll.getDisplayReference());
        String name = reference.isEmpty() ? document : document + SEPARATOR + reference;
        return name + "." + deed.format().extension();
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        String slug = value.replaceAll(NOT_FILENAME_SAFE, SEPARATOR);
        // A separator at either end is one a slug should not keep. The replacement collapses every run into one,
        // so there is at most one at each end, and trimming it by hand rather than with a second, anchored regex
        // keeps a long run of separators from costing a scan per position.
        int start = slug.startsWith(SEPARATOR) ? SEPARATOR.length() : 0;
        int end = slug.endsWith(SEPARATOR) ? slug.length() - SEPARATOR.length() : slug.length();
        return start < end ? slug.substring(start, end) : "";
    }
}
