package uk.gov.hmcts.reform.deedpoll.conformance;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.enums.DynamicsValues;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.reference.DataverseArtifacts;
import uk.gov.hmcts.reform.deedpoll.reference.DataverseArtifacts.TableDir;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a rule out of the vendored {@code dataverse_tables/deedpoll/business_rules.md} so a test can
 * assert against the rule the export states rather than against a transcription of it.
 *
 * <p>Not part of the reference harness -- the reference transcribes each branch into its test. Added
 * here because a transcription only agrees with its source on the day it is written, and this
 * particular rule is a 4-branch, 8-field matrix: the largest hand-copied thing in the port.
 *
 * <p>Deliberately strict. Every line inside the rule block must be one this parser recognises, and
 * every condition must be one of the two shapes the rule uses; anything else throws. A parser that
 * skipped what it did not understand would turn a rule the export had changed into a test that
 * quietly asserted less.
 */
final class BusinessRule {

    private static final String REQUIRED_RULE_HEADING =
        "### Make fields required or non-required depending on Type and Migrated Record";

    /**
     * {@code Set In Person? as Business Required} / {@code … as Not Business Required}.
     */
    private static final Pattern SET_REQUIRED =
        Pattern.compile("^Set (.+?) as (Not )?Business Required$");

    /**
     * {@code Migrated Record equals "Yes"}, optionally {@code AND Type equals "Adult,…"}.
     */
    private static final Pattern CONDITION = Pattern.compile(
        "^Migrated Record equals \"(Yes|No)\"(?: AND Type equals \"([^\"]+)\")?$");

    /** Display name to enum member, so the rule's type names resolve from the port's own labels. */
    private static final Map<String, DeedPollType> TYPE_BY_LABEL = typeByLabel();

    private BusinessRule() {
    }

    /**
     * One IF / ELSE-IF branch of the required-set rule: the Migrated Record value it fires on, the
     * types it applies to, and the required-ness it gives each field it names (keyed by the display
     * name the rule uses).
     */
    record RequiredBranch(boolean migrated, List<DeedPollType> types, Map<String, Boolean> required) {
    }

    /**
     * The branches of "Make fields required or non-required depending on Type and Migrated Record",
     * in the order the rule states them.
     *
     * <p>The rule's first branch conditions on Migrated Record alone and so applies to every type.
     * It is given the union of the types the other branches name, rather than every member of
     * {@link DeedPollType}: those are the types the rule is about, and Unknown is a type the port
     * deliberately leaves untouched everywhere.
     */
    static List<RequiredBranch> parseRequiredMatrix() {
        List<RequiredBranch> branches = parseBranches(section());

        Set<DeedPollType> named = new LinkedHashSet<>();
        branches.forEach(branch -> named.addAll(branch.types()));

        return branches.stream()
            .map(branch -> branch.types().isEmpty()
                ? new RequiredBranch(branch.migrated(), List.copyOf(named), branch.required())
                : branch)
            .toList();
    }

    private static List<RequiredBranch> parseBranches(List<String> lines) {
        List<RequiredBranch> branches = new ArrayList<>();

        boolean migrated = false;
        List<DeedPollType> types = List.of();
        Map<String, Boolean> required = new LinkedHashMap<>();
        boolean inBranch = false;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || "THEN".equals(line)) {
                continue;
            }

            if ("IF".equals(line) || "ELSE IF".equals(line)) {
                if (inBranch) {
                    branches.add(new RequiredBranch(migrated, types, Map.copyOf(required)));
                }
                inBranch = true;
                required = new LinkedHashMap<>();

                String condition = lines.get(++i).trim();
                Matcher matcher = CONDITION.matcher(condition);
                if (!matcher.matches()) {
                    throw new IllegalStateException(
                        "Unrecognised condition in the required-set rule: '" + condition
                            + "'. The rule's shape has changed at the source; read it and extend "
                            + "this parser rather than loosening it.");
                }
                migrated = "Yes".equals(matcher.group(1));
                types = matcher.group(2) == null ? List.of() : resolveTypes(matcher.group(2));
                continue;
            }

            Matcher set = SET_REQUIRED.matcher(line);
            if (!set.matches()) {
                throw new IllegalStateException(
                    "Unrecognised line in the required-set rule: '" + line + "'");
            }
            required.put(set.group(1), set.group(2) == null);
        }

        if (inBranch) {
            branches.add(new RequiredBranch(migrated, types, Map.copyOf(required)));
        }
        return List.copyOf(branches);
    }

    private static List<DeedPollType> resolveTypes(String labels) {
        return Arrays.stream(labels.split(","))
            .map(String::trim)
            .map(label -> {
                DeedPollType type = TYPE_BY_LABEL.get(label);
                if (type == null) {
                    throw new IllegalStateException(
                        "The rule names a type this port has no member for: '" + label + "'");
                }
                return type;
            })
            .toList();
    }

    /** The lines of the required-set rule's block, up to the next heading. */
    private static List<String> section() {
        List<String> lines =
            List.of(DataverseArtifacts.businessRules(TableDir.DEEDPOLL).split("\n", -1));

        int start = lines.indexOf(REQUIRED_RULE_HEADING);
        if (start < 0) {
            throw new IllegalStateException(
                "business_rules.md no longer carries the heading '" + REQUIRED_RULE_HEADING
                    + "'. The rule this test asserts has been renamed or removed at the source.");
        }

        List<String> block = new ArrayList<>();
        for (int i = start + 1; i < lines.size() && !lines.get(i).startsWith("### "); i++) {
            block.add(lines.get(i));
        }
        return List.copyOf(block);
    }

    private static Map<String, DeedPollType> typeByLabel() {
        Map<String, DeedPollType> byLabel = new LinkedHashMap<>();
        DynamicsValues.DEED_POLL_TYPE_LABEL.forEach((type, label) -> byLabel.put(label, type));
        return Collections.unmodifiableMap(byLabel);
    }
}
