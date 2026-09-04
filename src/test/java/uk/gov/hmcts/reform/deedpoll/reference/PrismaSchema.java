package uk.gov.hmcts.reform.deedpoll.reference;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads the vendored reference schema at {@code reference/deed-poll.prisma} so tests can
 * assert the Flyway migrations against it rather than against a hand-maintained duplicate
 * of the same field list.
 *
 * <p>Deliberately a small, strict parser rather than a Prisma library: the reference file
 * is the only input it will ever see, and a parser that quietly ignores a construct it does
 * not understand would silently shrink the conformance check. Anything unrecognised is
 * therefore dropped only when it is provably not a stored field -- comments, block
 * attributes, and relation fields whose type is another model.
 */
public final class PrismaSchema {

    private static final String RESOURCE = "/reference/deed-poll.prisma";

    private static final Pattern BLOCK_START = Pattern.compile("^(model|enum)\\s+(\\w+)\\s*\\{");
    private static final Pattern FIELD_MAP = Pattern.compile("@map\\(\"([^\"]+)\"\\)");
    private static final Pattern TABLE_MAP = Pattern.compile("@@map\\(\"([^\"]+)\"\\)");
    private static final Pattern VAR_CHAR = Pattern.compile("@db\\.VarChar\\((\\d+)\\)");
    private static final Pattern DECIMAL = Pattern.compile("@db\\.Decimal\\((\\d+)\\s*,\\s*(\\d+)\\)");

    private final Map<String, Model> models;
    private final Map<String, List<String>> enums;

    private PrismaSchema(Map<String, Model> models, Map<String, List<String>> enums) {
        this.models = models;
        this.enums = enums;
    }

    /**
     * A field's declared type. {@code length} is set by {@code @db.VarChar(n)};
     * {@code precision} and {@code scale} by {@code @db.Decimal(p, s)}. All three are null
     * when the reference leaves the type at its Prisma default.
     */
    public record Type(String base, boolean list, boolean optional,
                       Integer length, Integer precision, Integer scale) {
    }

    /**
     * A stored field. {@code column} is the {@code @map} name where one is declared, and the
     * field name otherwise.
     */
    public record Field(String name, String column, Type type) {
    }

    /**
     * A model. {@code table} is the {@code @@map} name where one is declared, and the model
     * name otherwise.
     */
    public record Model(String name, String table, List<Field> fields) {
    }

    /** Parses the vendored reference schema. */
    public static PrismaSchema loadReference() {
        List<String> lines = readLines();

        Map<String, List<String>> modelBodies = new LinkedHashMap<>();
        Map<String, List<String>> enumBodies = new LinkedHashMap<>();
        collectBlocks(lines, modelBodies, enumBodies);

        Map<String, List<String>> enums = new LinkedHashMap<>();
        enumBodies.forEach((name, body) -> enums.put(name, parseEnumValues(body)));

        Map<String, Model> models = new LinkedHashMap<>();
        modelBodies.forEach((name, body) -> models.put(name, parseModel(name, body, modelBodies.keySet())));

        return new PrismaSchema(Collections.unmodifiableMap(models), Collections.unmodifiableMap(enums));
    }

    /** Models by their Prisma name. */
    public Map<String, Model> models() {
        return models;
    }

    /** Enum values, in the order the reference declares them, by enum name. */
    public Map<String, List<String>> enums() {
        return enums;
    }

    /** The named model, or a failure if the reference has no such model. */
    public Model model(String name) {
        Model model = models.get(name);
        if (model == null) {
            throw new IllegalArgumentException(
                "The reference schema declares no model '" + name + "'. It declares: " + models.keySet());
        }
        return model;
    }

    /** Whether the given type name is one of the reference's enums. */
    public boolean isEnum(String typeName) {
        return enums.containsKey(typeName);
    }

    private static void collectBlocks(List<String> lines,
                                      Map<String, List<String>> modelBodies,
                                      Map<String, List<String>> enumBodies) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher start = BLOCK_START.matcher(lines.get(i).trim());
            if (!start.find()) {
                continue;
            }
            List<String> body = new ArrayList<>();
            int j = i + 1;
            while (j < lines.size() && !"}".equals(lines.get(j).trim())) {
                body.add(lines.get(j));
                j++;
            }
            if ("model".equals(start.group(1))) {
                modelBodies.put(start.group(2), body);
            } else {
                enumBodies.put(start.group(2), body);
            }
            i = j;
        }
    }

    private static List<String> parseEnumValues(List<String> body) {
        return body.stream()
            .map(PrismaSchema::stripComment)
            .filter(line -> !line.isEmpty())
            .map(line -> line.split("\\s+")[0])
            .toList();
    }

    private static Model parseModel(String name, List<String> body, Set<String> modelNames) {
        String table = name;
        List<Field> fields = new ArrayList<>();

        for (String raw : body) {
            String line = stripComment(raw);
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("@@")) {
                Matcher tableMap = TABLE_MAP.matcher(line);
                if (tableMap.find()) {
                    table = tableMap.group(1);
                }
                continue;
            }

            String[] tokens = line.split("\\s+");
            if (tokens.length < 2) {
                throw new IllegalStateException("Unparseable line in model " + name + ": " + raw);
            }
            Type type = parseType(tokens[1], line);
            if (modelNames.contains(type.base())) {
                // A relation field: no column of its own. The foreign key is a separate
                // scalar field (deedPollId), which is kept.
                continue;
            }
            Matcher fieldMap = FIELD_MAP.matcher(line);
            String column = fieldMap.find() ? fieldMap.group(1) : tokens[0];
            fields.add(new Field(tokens[0], column, type));
        }

        return new Model(name, table, List.copyOf(fields));
    }

    private static Type parseType(String token, String line) {
        boolean optional = token.endsWith("?");
        String base = optional ? token.substring(0, token.length() - 1) : token;
        boolean list = base.endsWith("[]");
        if (list) {
            base = base.substring(0, base.length() - 2);
        }

        Integer length = null;
        Matcher varChar = VAR_CHAR.matcher(line);
        if (varChar.find()) {
            length = Integer.valueOf(varChar.group(1));
        }

        Integer precision = null;
        Integer scale = null;
        Matcher decimal = DECIMAL.matcher(line);
        if (decimal.find()) {
            precision = Integer.valueOf(decimal.group(1));
            scale = Integer.valueOf(decimal.group(2));
        }

        return new Type(base, list, optional, length, precision, scale);
    }

    private static String stripComment(String line) {
        int comment = line.indexOf("//");
        return (comment < 0 ? line : line.substring(0, comment)).trim();
    }

    private static List<String> readLines() {
        try (InputStream in = PrismaSchema.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                    "The vendored reference schema is missing from the test classpath: " + RESOURCE);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.toList());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + RESOURCE, e);
        }
    }
}
