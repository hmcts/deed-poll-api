package uk.gov.hmcts.reform.deedpoll.api;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;

/**
 * A controller-shaped class with one write mapping nobody classified, for {@link WriteEndpointAuditCoverageTest} to
 * prove it catches.
 *
 * <p>Deliberately not a {@code @RestController}: a stereotype here would put it in every booted test context, as a
 * live route, and the request mapping alone makes it neither a bean nor a handler.
 */
@RequestMapping("/caseworker/fixture")
class UnclassifiedWriteFixture {

    @GetMapping
    String read() {
        return "a read is never a write";
    }

    @AuditedAs(AuditAction.CASE_UPDATED)
    @PostMapping("/classified")
    void classified() {
        // Says what it records.
    }

    @DeleteMapping("/{id}")
    void unclassified() {
        // Says nothing, which is the point.
    }
}
