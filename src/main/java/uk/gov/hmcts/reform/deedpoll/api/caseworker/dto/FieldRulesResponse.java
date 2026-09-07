package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.FieldRule;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The per-type field rules, keyed by field name.
 *
 * <p>Wrapped in an object rather than published as a bare JSON map, which is what the sibling TypeScript API
 * returns. A map at the top level makes every field name a top-level key, so anything added beside it later --
 * the type the rules were resolved for, a schema version -- collides with a field called the same thing. One
 * named member costs a caller {@code .rules} and keeps the document extensible.
 *
 * <p>The map preserves the domain's iteration order, which is the order the rules matrix declares its fields
 * in, so a diff between two responses reads sensibly.
 *
 * @param rules each field's rule, keyed by the field name the case schema uses
 */
public record FieldRulesResponse(Map<String, FieldRuleResponse> rules) {

    /** The domain's rule matrix as the response carries it. */
    public static FieldRulesResponse from(Map<String, FieldRule> rules) {
        Map<String, FieldRuleResponse> published = new LinkedHashMap<>();
        rules.forEach((field, rule) -> published.put(field, FieldRuleResponse.from(rule)));
        return new FieldRulesResponse(published);
    }
}
