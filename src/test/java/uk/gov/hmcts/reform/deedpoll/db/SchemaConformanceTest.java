package uk.gov.hmcts.reform.deedpoll.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.gov.hmcts.reform.deedpoll.reference.PrismaSchema;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Asserts the Flyway schema conforms to the vendored reference schema
 * ({@code reference/deed-poll.prisma}), which is the specification for this port.
 *
 * <p><strong>Forward coverage only.</strong> Every field the reference declares must have a
 * matching column, of a matching type, with matching nullability. The reverse is not
 * asserted: a column this schema has and the reference does not is allowed, because this
 * service will grow fields the TypeScript store never had. That is the same limitation the
 * TypeScript conformance harness states about itself, and it means a green run here proves
 * nothing was dropped -- not that nothing was added.
 *
 * <p><strong>One addition is already deliberate, and this is where to read about it before
 * "restoring parity".</strong> V1 to V3 were written to be byte-compatible with the reference, so
 * that a {@code prisma migrate diff} against this schema stayed empty. V4 breaks that on purpose:
 * it adds three indexes -- {@code createdOn}, {@code (state, createdOn)} and the
 * {@code documentTypeId} foreign key -- for access paths the reference has no equivalent of,
 * because it has no equivalent of these finders. A future diff will therefore propose DROPPING
 * them, and it is wrong: {@code DeedPollRepository}'s unpaginated ordered finders and
 * {@code countByDocumentTypeId} are what depend on them, and the fix is to add the matching
 * {@code @@index} declarations upstream in {@code hmcts/deed-poll-admin}. Nothing here needs the
 * parity anyway -- an index is not part of the ported data model, this factory only asserts that
 * every reference index EXISTS, and {@code ddl-auto: validate} does not look at indexes at all --
 * so both directions are silent and only a reader could get it wrong.
 *
 * <p>Runs Flyway against a throwaway Postgres, so it tests the migrations as an environment
 * will actually apply them rather than testing a model of them.
 */
@Testcontainers
class SchemaConformanceTest {

    /**
     * Postgres reports a foreign key's delete rule as a single character. Mapped back to the
     * reference's vocabulary so a failure names the rule the schema asked for rather than a
     * letter the reader has to look up.
     */
    private static final Map<String, String> DELETE_RULES = Map.of(
        "a", "NoAction", "r", "Restrict", "c", "Cascade", "n", "SetNull", "d", "SetDefault");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static PrismaSchema reference;
    private static Map<String, Map<String, Column>> tables;
    private static Map<String, List<String>> enumTypes;
    private static Set<String> indexedColumns;
    private static List<ForeignKey> foreignKeys;

    /**
     * One column as Postgres reports it. Array columns report {@code dataType} "ARRAY" and
     * carry the element type in {@code udtName} ("_int4" for {@code integer[]}); enum
     * columns report {@code dataType} "USER-DEFINED" with the type name in {@code udtName}.
     */
    private record Column(String name, String dataType, String udtName,
                          Integer charMaxLength, Integer numericPrecision, Integer numericScale,
                          boolean nullable) {
    }

    /** One foreign key as Postgres reports it, with its columns in constraint order. */
    private record ForeignKey(String name, String table, List<String> columns,
                              String targetTable, List<String> targetColumns, String onDelete) {
    }

    @BeforeAll
    static void migrateAndIntrospect() throws SQLException {
        reference = PrismaSchema.loadReference();
        String jdbcUrl = ContainerDatabase.awaitJdbcUrl(POSTGRES);

        Flyway.configure()
            .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();

        try (Connection connection = DriverManager.getConnection(
            jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
            tables = readColumns(connection);
            enumTypes = readEnums(connection);
            indexedColumns = readIndexedColumns(connection);
            foreignKeys = readForeignKeys(connection);
        }
    }

    @Test
    @DisplayName("every reference model has its table")
    void everyReferenceModelHasItsTable() {
        for (PrismaSchema.Model model : reference.models().values()) {
            assertThat(tables)
                .as("table for reference model %s", model.name())
                .containsKey(model.table());
        }
    }

    @TestFactory
    @DisplayName("every reference field has a conforming column")
    List<DynamicTest> everyReferenceFieldHasAConformingColumn() {
        List<DynamicTest> tests = new ArrayList<>();
        for (PrismaSchema.Model model : reference.models().values()) {
            for (PrismaSchema.Field field : model.fields()) {
                tests.add(DynamicTest.dynamicTest(
                    "%s.%s -> %s.%s".formatted(model.name(), field.name(), model.table(), field.column()),
                    () -> assertFieldConforms(model, field)));
            }
        }
        return tests;
    }

    @TestFactory
    @DisplayName("every reference enum exists with its members in declaration order")
    List<DynamicTest> everyReferenceEnumExistsInDeclarationOrder() {
        return reference.enums().entrySet().stream()
            .map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
                assertThat(enumTypes)
                    .as("enum type %s", entry.getKey())
                    .containsKey(entry.getKey());
                // Order matters: a member's position is what ties it to the Dynamics
                // option-set integer documented in the reference, and those integers are
                // what the Dataverse exports hold.
                assertThat(enumTypes.get(entry.getKey()))
                    .as("members of %s, in declaration order", entry.getKey())
                    .containsExactlyElementsOf(entry.getValue());
            }))
            .toList();
    }

    @TestFactory
    @DisplayName("every reference @@index exists")
    List<DynamicTest> everyReferenceIndexExists() {
        List<DynamicTest> tests = new ArrayList<>();
        for (PrismaSchema.Model model : reference.models().values()) {
            for (PrismaSchema.Index index : model.indexes()) {
                List<String> columns = index.fields().stream()
                    .map(field -> reference.columnFor(model.name(), field))
                    .toList();
                String key = indexKey(model.table(), columns);
                tests.add(DynamicTest.dynamicTest(key, () -> assertThat(indexedColumns)
                    .as("an index on %s", key)
                    .contains(key)));
            }
        }
        return tests;
    }

    @TestFactory
    @DisplayName("every reference relation is a foreign key with the declared delete rule")
    List<DynamicTest> everyReferenceRelationIsAForeignKey() {
        List<DynamicTest> tests = new ArrayList<>();
        for (PrismaSchema.Model model : reference.models().values()) {
            for (PrismaSchema.Relation relation : model.relations()) {
                tests.add(DynamicTest.dynamicTest(
                    "%s.%s -> %s (onDelete: %s)".formatted(
                        model.name(), relation.name(), relation.targetModel(), relation.onDelete()),
                    () -> assertRelationConforms(model, relation)));
            }
        }
        return tests;
    }

    /**
     * The delete rule is asserted, not just the key's existence, because it is behaviour: the
     * reference makes the case's three children {@code Cascade} deliberately, and a document
     * type {@code Restrict} so one still in use cannot be deleted. A foreign key with the
     * wrong rule loses or keeps rows silently.
     */
    private static void assertRelationConforms(PrismaSchema.Model model, PrismaSchema.Relation relation) {
        List<String> columns = relation.fields().stream()
            .map(field -> reference.columnFor(model.name(), field))
            .toList();
        PrismaSchema.Model target = reference.model(relation.targetModel());
        List<String> targetColumns = relation.references().stream()
            .map(field -> reference.columnFor(target.name(), field))
            .toList();

        assertThat(foreignKeys)
            .as("foreign key %s.%s -> %s.%s",
                model.table(), columns, target.table(), targetColumns)
            .anySatisfy(key -> {
                assertThat(key.table()).isEqualTo(model.table());
                assertThat(key.columns()).isEqualTo(columns);
                assertThat(key.targetTable()).isEqualTo(target.table());
                assertThat(key.targetColumns()).isEqualTo(targetColumns);
                assertThat(key.onDelete()).isEqualTo(relation.onDelete());
            });
    }

    private static void assertFieldConforms(PrismaSchema.Model model, PrismaSchema.Field field) {
        Map<String, Column> columns = tables.get(model.table());
        assertThat(columns).as("table %s", model.table()).isNotNull();

        Column column = columns.get(field.column());
        assertThat(column)
            .as("column %s.%s for reference field %s.%s",
                model.table(), field.column(), model.name(), field.name())
            .isNotNull();

        assertTypeConforms(field, column);
        assertNullabilityConforms(field, column);
    }

    private static void assertTypeConforms(PrismaSchema.Field field, Column column) {
        PrismaSchema.Type type = field.type();

        if (type.list()) {
            assertThat(column.dataType()).as("%s is an array", field.column()).isEqualTo("ARRAY");
            assertThat(column.udtName())
                .as("element type of %s", field.column())
                .isEqualTo(elementUdtName(type.base(), field));
            return;
        }

        if (reference.isEnum(type.base())) {
            assertThat(column.udtName()).as("enum type of %s", field.column()).isEqualTo(type.base());
            return;
        }

        switch (type.base()) {
            case "String" -> assertStringType(field, column);
            case "Int" -> assertThat(column.dataType()).as("type of %s", field.column()).isEqualTo("integer");
            case "Boolean" -> assertThat(column.dataType()).as("type of %s", field.column()).isEqualTo("boolean");
            case "DateTime" -> assertThat(column.dataType())
                .as("type of %s", field.column()).isEqualTo("timestamp without time zone");
            case "Decimal" -> assertDecimalType(field, column);
            default -> fail("No expected column type for reference field %s of type %s"
                .formatted(field.name(), type.base()));
        }
    }

    private static void assertStringType(PrismaSchema.Field field, Column column) {
        if (field.type().length() == null) {
            assertThat(column.dataType()).as("type of %s", field.column()).isEqualTo("text");
        } else {
            assertThat(column.dataType()).as("type of %s", field.column()).isEqualTo("character varying");
            assertThat(column.charMaxLength()).as("length of %s", field.column()).isEqualTo(field.type().length());
        }
    }

    private static void assertDecimalType(PrismaSchema.Field field, Column column) {
        assertThat(column.dataType()).as("type of %s", field.column()).isEqualTo("numeric");
        if (field.type().precision() != null) {
            assertThat(column.numericPrecision())
                .as("precision of %s", field.column()).isEqualTo(field.type().precision());
            assertThat(column.numericScale())
                .as("scale of %s", field.column()).isEqualTo(field.type().scale());
        }
    }

    private static void assertNullabilityConforms(PrismaSchema.Field field, Column column) {
        if (field.type().list()) {
            // Not asserted for arrays. A required Prisma list produces a NULLABLE column
            // with an empty-array default -- the reference store's own columns are nullable
            // -- so requiring NOT NULL here would diverge from the schema being ported.
            return;
        }
        assertThat(column.nullable())
            .as("%s is nullable (reference declares it %s)",
                field.column(), field.type().optional() ? "optional" : "required")
            .isEqualTo(field.type().optional());
    }

    private static String elementUdtName(String base, PrismaSchema.Field field) {
        if ("Int".equals(base)) {
            return "_int4";
        }
        return fail("No expected array element type for reference field %s of type %s[]"
            .formatted(field.name(), base));
    }

    private static Map<String, Map<String, Column>> readColumns(Connection connection) throws SQLException {
        String sql = """
            SELECT table_name, column_name, data_type, udt_name,
                   character_maximum_length, numeric_precision, numeric_scale, is_nullable
              FROM information_schema.columns
             WHERE table_schema = 'public'
             ORDER BY table_name, ordinal_position
            """;
        Map<String, Map<String, Column>> result = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                Column column = new Column(
                    rows.getString("column_name"),
                    rows.getString("data_type"),
                    rows.getString("udt_name"),
                    nullableInt(rows, "character_maximum_length"),
                    nullableInt(rows, "numeric_precision"),
                    nullableInt(rows, "numeric_scale"),
                    "YES".equals(rows.getString("is_nullable")));
                result.computeIfAbsent(rows.getString("table_name"), table -> new LinkedHashMap<>())
                    .put(column.name(), column);
            }
        }
        return result;
    }

    private static Map<String, List<String>> readEnums(Connection connection) throws SQLException {
        String sql = """
            SELECT t.typname AS name, e.enumlabel AS value
              FROM pg_type t
              JOIN pg_enum e ON e.enumtypid = t.oid
              JOIN pg_namespace n ON n.oid = t.typnamespace
             WHERE n.nspname = 'public'
             ORDER BY t.typname, e.enumsortorder
            """;
        Map<String, List<String>> result = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                result.computeIfAbsent(rows.getString("name"), name -> new ArrayList<>())
                    .add(rows.getString("value"));
            }
        }
        return result;
    }

    /**
     * Every index in the schema as "table(col,col)". A set of keys rather than index names,
     * because the reference declares indexes by column and names them nowhere.
     */
    private static Set<String> readIndexedColumns(Connection connection) throws SQLException {
        String sql = """
            SELECT c.relname AS table_name,
                   string_agg(a.attname, ',' ORDER BY k.ord) AS columns
              FROM pg_index i
              JOIN pg_class c ON c.oid = i.indrelid
              JOIN pg_class ic ON ic.oid = i.indexrelid
              JOIN pg_namespace n ON n.oid = c.relnamespace
              JOIN unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord) ON true
              JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.attnum
             WHERE n.nspname = 'public'
             GROUP BY ic.relname, c.relname
            """;
        Set<String> result = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                result.add(rows.getString("table_name") + "(" + rows.getString("columns") + ")");
            }
        }
        return result;
    }

    private static List<ForeignKey> readForeignKeys(Connection connection) throws SQLException {
        String sql = """
            SELECT con.conname AS name,
                   src.relname AS table_name,
                   tgt.relname AS target_table,
                   con.confdeltype AS delete_rule,
                   (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                      FROM unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)
                      JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum)
                     AS columns,
                   (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                      FROM unnest(con.confkey) WITH ORDINALITY AS k(attnum, ord)
                      JOIN pg_attribute a ON a.attrelid = con.confrelid AND a.attnum = k.attnum)
                     AS target_columns
              FROM pg_constraint con
              JOIN pg_class src ON src.oid = con.conrelid
              JOIN pg_class tgt ON tgt.oid = con.confrelid
              JOIN pg_namespace n ON n.oid = src.relnamespace
             WHERE con.contype = 'f' AND n.nspname = 'public'
             ORDER BY con.conname
            """;
        List<ForeignKey> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                result.add(new ForeignKey(
                    rows.getString("name"),
                    rows.getString("table_name"),
                    List.of(rows.getString("columns").split(",")),
                    rows.getString("target_table"),
                    List.of(rows.getString("target_columns").split(",")),
                    DELETE_RULES.get(rows.getString("delete_rule"))));
            }
        }
        return result;
    }

    private static String indexKey(String table, List<String> columns) {
        return table + "(" + String.join(",", columns) + ")";
    }

    private static Integer nullableInt(ResultSet rows, String column) throws SQLException {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }
}
