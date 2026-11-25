package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The supporting-document type the deletion tests' document fixtures file their rows under.
 *
 * <p>They used to take whichever type sorted first, but several test classes empty
 * {@code deedpoll_supportingdocumenttype} in their own clean-up, so in a run where one of those went first in the same
 * JVM the lookup found nothing and every fixture insert failed on {@code "documentTypeId"}'s NOT NULL. This one is
 * inserted when missing and removed again when unused, so the fixtures no longer depend on test order, and a test that
 * lists the document types never sees it.
 */
public final class DeletionTestDocumentType {

    /** The type's id, a fixed text no migration or other test uses. */
    public static final String ID = "deletion-test-evidence";

    private DeletionTestDocumentType() {
    }

    /**
     * Inserts the type if it is missing.
     *
     * @return its id, for the fixture's {@code "documentTypeId"}
     */
    public static String ensure(JdbcTemplate jdbc) {
        jdbc.update("""
            INSERT INTO deedpoll_supportingdocumenttype (id, name) VALUES (?, 'Deletion test evidence')
            ON CONFLICT (id) DO NOTHING
            """, ID);
        return ID;
    }

    /**
     * Removes the type once no document refers to it, for a test class's clean-up after its cases are gone.
     */
    public static void removeIfUnused(JdbcTemplate jdbc) {
        jdbc.update("""
            DELETE FROM deedpoll_supportingdocumenttype t WHERE t.id = ?
               AND NOT EXISTS (SELECT 1 FROM deedpoll_supportingdocument d WHERE d."documentTypeId" = t.id)
            """, ID);
    }
}
