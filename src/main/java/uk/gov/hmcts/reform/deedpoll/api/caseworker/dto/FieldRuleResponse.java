package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.FieldRule;

/**
 * One field's show / hide / mandate rule for a given case type.
 *
 * <p>A wire copy of the domain's {@link FieldRule} rather than the type itself, so the published shape is
 * declared in the package a reviewer reads the contract from. The two happen to be identical today, and that
 * is the point of copying rather than exposing: the domain record is free to gain a third answer for a
 * consumer that needs one without every HTTP client discovering it.
 *
 * @param visible whether the field appears on the form for this type at all
 * @param required whether the field must be populated before the case can be submitted
 */
public record FieldRuleResponse(boolean visible, boolean required) {

    /** One domain rule as the response carries it. */
    public static FieldRuleResponse from(FieldRule rule) {
        return new FieldRuleResponse(rule.visible(), rule.required());
    }
}
