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
import java.util.List;
import java.util.Map;

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
 * <p>Runs Flyway against a throwaway Postgres, so it tests the migrations as an environment
 * will actually apply them rather than testing a model of them.
 */
@Testcontainers
class SchemaConformanceTest {

    /**
     * Reference models whose tables the Flyway baseline has reached. V1 and V2 cover the
     * enums and the parent case table; the five child models arrive with V3.
     */
    private static final List<String> PORTED_MODELS = List.of("DeedPoll");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static PrismaSchema reference;
    private static Map<String, Map<String, Column>> tables;
    private static Map<String, List<String>> enumTypes;

    /**
     * One column as Postgres reports it. Array columns report {@code dataType} "ARRAY" and
     * carry the element type in {@code udtName} ("_int4" for {@code integer[]}); enum
     * columns report {@code dataType} "USER-DEFINED" with the type name in {@code udtName}.
     */
    private record Column(String name, String dataType, String udtName,
                          Integer charMaxLength, Integer numericPrecision, Integer numericScale,
                          boolean nullable) {
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
        }
    }

    @Test
    @DisplayName("every ported reference model has its table")
    void everyPortedModelHasItsTable() {
        for (String modelName : PORTED_MODELS) {
            PrismaSchema.Model model = reference.model(modelName);
            assertThat(tables)
                .as("table for reference model %s", modelName)
                .containsKey(model.table());
        }
    }

    @TestFactory
    @DisplayName("every reference field has a conforming column")
    List<DynamicTest> everyReferenceFieldHasAConformingColumn() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String modelName : PORTED_MODELS) {
            PrismaSchema.Model model = reference.model(modelName);
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

    private static Integer nullableInt(ResultSet rows, String column) throws SQLException {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }
}
