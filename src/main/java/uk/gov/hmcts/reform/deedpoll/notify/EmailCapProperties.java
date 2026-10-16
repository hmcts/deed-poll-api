package uk.gov.hmcts.reform.deedpoll.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How many times one template may be owed to one case in a day: {@code deedpoll.email}.
 *
 * <p>The abuse-limits plan's decision 8. Each compose journey mints a new {@code Idempotency-Key}, so without a cap a
 * repeatable email such as the chase can be sent to one applicant as often as a caseworker presses send. It enforces
 * whatever {@code deedpoll.rate-limit.mode} says: it is a business rule about one applicant, not a throughput budget.
 *
 * @param maxPerCasePerTemplatePerDay the rows of one template one case may hold from the last 24 hours, not counting
 *     permanent refusals; unset is {@value #MAX_PER_CASE_PER_TEMPLATE_PER_DAY}
 */
@ConfigurationProperties(prefix = "deedpoll.email")
public record EmailCapProperties(Integer maxPerCasePerTemplatePerDay) {

    /** Decision 8's default. */
    public static final int MAX_PER_CASE_PER_TEMPLATE_PER_DAY = 3;

    public EmailCapProperties {
        maxPerCasePerTemplatePerDay = maxPerCasePerTemplatePerDay == null
            ? MAX_PER_CASE_PER_TEMPLATE_PER_DAY : maxPerCasePerTemplatePerDay;
        if (maxPerCasePerTemplatePerDay < 1) {
            // Zero would refuse every send of every template, which is switching email off by another name.
            throw new IllegalArgumentException("the per-case, per-template email cap must be at least 1");
        }
    }

    /**
     * The 409's sentence, in fixed api wording: it names the cap and nothing from the request or the case.
     */
    public String refusal() {
        return "This email has already been sent " + maxPerCasePerTemplatePerDay
            + (maxPerCasePerTemplatePerDay == 1 ? " time" : " times") + " for this case today";
    }
}
