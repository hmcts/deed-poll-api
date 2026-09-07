package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.entity.ReferenceCounter;

/**
 * The per-year reference-number counter.
 *
 * <p>The only interesting operation is {@link #allocateNextSerial(int)}, and it is hand-written SQL
 * rather than a derived query because what makes it correct is the lock it takes.
 */
@Repository
public interface ReferenceCounterRepository extends JpaRepository<ReferenceCounter, Integer> {

    /**
     * Increments the counter for a year and returns the serial just allocated, atomically.
     *
     * <p><strong>Why this statement and not a read followed by a write.</strong> The source Power
     * Automate flow read the current maximum reference number and added one, which hands two
     * concurrent creates the same serial. The plan asks for the increment to happen inside a
     * transaction holding a row lock -- {@code SELECT … FOR UPDATE} -- and that is what this does,
     * in one statement: {@code ON CONFLICT … DO UPDATE} takes the same exclusive row lock, and a
     * second transaction reaching it waits for the first to commit and then re-reads the committed
     * row before incrementing, so no two callers can be handed the same serial.
     *
     * <p>Expressing it as one statement rather than a literal {@code SELECT … FOR UPDATE} followed by
     * an {@code UPDATE} also fixes the case a read-then-lock cannot: the FIRST allocation of a year,
     * where there is no row to lock yet and two concurrent callers would race to insert one. Seeding
     * and incrementing are the same statement here, so the first allocation of a year is as safe as
     * the thousandth. It is also exactly what the reference does -- a Prisma {@code upsert} with an
     * {@code increment}, which compiles to this.
     *
     * <p>Identifiers are quoted because {@code "lastSerial"} is mixed case; unquoted, Postgres folds
     * it to {@code lastserial} and the statement does not find the column.
     *
     * <p><strong>Deliberately not {@code @Modifying}</strong>, despite being an INSERT. That annotation makes
     * Spring Data run the query through {@code executeUpdate()} and return the number of rows affected, which
     * is 1 for every allocation -- so the {@code RETURNING} value would be discarded and every case would be
     * handed serial 1. Left as a selecting query, the returned row is the serial.
     *
     * <p>{@code MANDATORY} because the lock is only worth taking if it is held until the caller commits. On its
     * own transaction the serial would be committed the moment it was allocated, so a create that then failed
     * would leave a permanent gap in an externally visible sequence; demanding the caller's transaction says so
     * rather than leaving it to be noticed.
     *
     * @param year the year the serial belongs to; the counter is shared across KBDP and KBDR
     * @return the serial allocated, 1 for the first allocation of the year
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(nativeQuery = true, value = """
        INSERT INTO "deedpoll_referencecounter" ("year", "lastSerial")
        VALUES (:year, 1)
        ON CONFLICT ("year")
        DO UPDATE SET "lastSerial" = "deedpoll_referencecounter"."lastSerial" + 1
        RETURNING "lastSerial"
        """)
    int allocateNextSerial(@Param("year") int year);
}
