package uk.gov.hmcts.reform.deedpoll.health;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whether this database can actually serve deed-poll traffic.
 *
 * <p>Ported from {@code reference/ts/health.ts}, whose reasoning is the reason this class exists rather than
 * a bare connection check. {@code select 1} proves only that Postgres answers. It says nothing about whether
 * the migrations have run, and an unmigrated pod passes it while every request 500s -- which is the worst
 * shape of readiness failure, because the probe is green, so Kubernetes keeps sending traffic and nothing
 * escalates.
 *
 * <p>One query against {@code information_schema}, not one per table, and it returns the <strong>missing</strong>
 * names rather than a boolean, so whatever reports the failure can say which tables.
 * {@link DeedPollSchemaHealthIndicator} is that reporter.
 *
 * <p>Separate from the indicator because the two answer different questions. This one is "what is absent",
 * which has a factual answer a test can assert against a real database; the indicator turns that into UP or
 * DOWN, which is a decision about what readiness means. Bundling them would make the query only reachable
 * through {@code Health}, and every test of it an assertion about a details map.
 */
@Component
public class DeedPollSchema {

    /**
     * Every table the deed-poll domain reads or writes: the six named by the {@code @@map}s in
     * {@code src/test/resources/reference/deed-poll.prisma}, plus the two saved-query tables,
     * {@code deedpoll_referral}, {@code deedpoll_notification} and {@code deedpoll_ccdcreationattempt}, which have
     * no reference model because saved queries, referrals, the notification outbox and CCD linking are new
     * behaviour rather than a port.
     *
     * <p>Listed explicitly rather than derived, and the reference's reason for that survives the port even
     * though its own reason does not: Prisma has no runtime schema introspection, whereas Hibernate does know
     * every table its entities are mapped to. Deriving it from the entities would make the check circular --
     * readiness would then assert that the schema matches whatever the entities happen to say, which is
     * already what {@code ddl-auto=validate} asserts at start-up, and a table nothing is mapped to yet would
     * be invisible to both.
     *
     * <p>So <strong>this has to be updated by hand when a model is added.</strong> A missing entry is a silent
     * gap: readiness would pass on a database lacking the new table. {@code DeedPollSchemaHealthTest} guards
     * against that by checking the ported names against the reference schema file itself, exactly as
     * {@code health.test.ts} does -- and by checking the rest against the migrations, since a table with no
     * reference model has nothing in the reference to be checked against.
     */
    public static final List<String> DEED_POLL_TABLES = List.of(
        "deedpoll_deedpoll",
        "deedpoll_notes",
        "deedpoll_witness",
        "deedpoll_supportingdocument",
        "deedpoll_supportingdocumenttype",
        "deedpoll_referencecounter",
        "deedpoll_savedquery",
        "deedpoll_savedqueryselection",
        "deedpoll_referral",
        "deedpoll_notification",
        "deedpoll_ccdcreationattempt",
        "deedpoll_deedpreparation",
        "deedpoll_stagedpayload"
    );

    /**
     * {@code current_schema()} rather than a literal {@code 'public'}, so it follows the connection's
     * {@code search_path} and answers for the schema this application is actually querying -- plan 02 adds a
     * {@code ccd} schema to the same database, and a check that looked in the wrong one would be worse than no
     * check at all.
     *
     * <p>A named {@code in (:tables)} list where the reference binds a Postgres array to {@code any()}. Same
     * query, and fully parameterised: the template expands the one parameter into a placeholder per table, so
     * no table name is ever interpolated into SQL, and the statement is a constant rather than a format.
     */
    private static final String MISSING_TABLES_SQL = """
        select table_name from information_schema.tables
        where table_schema = current_schema() and table_name in (:tables)
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public DeedPollSchema(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    /**
     * The deed-poll tables that are not present, in the schema this connection resolves to.
     *
     * <p>Throws if the database is unreachable, which is deliberate and is the reference's decision too:
     * "cannot connect" and "connected but unmigrated" are different faults wanting different responses, and
     * flattening them here would lose the distinction the caller logs.
     *
     * @return the absent table names, in {@link #DEED_POLL_TABLES} order, empty if the schema is complete
     * @throws org.springframework.dao.DataAccessException if the query cannot be run at all
     */
    public List<String> missingDeedPollTables() {
        Set<String> present = new HashSet<>(
            jdbcTemplate.queryForList(MISSING_TABLES_SQL, Map.of("tables", DEED_POLL_TABLES), String.class));

        // Filtered from our own list rather than trusting the rows: a row for something that is not ours must
        // not be read as satisfying one of ours. The bound `in` list should make that impossible, which is why
        // this is belt and braces rather than logic -- the reference keeps the same guard.
        return DEED_POLL_TABLES.stream().filter(table -> !present.contains(table)).toList();
    }
}
