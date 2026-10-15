package uk.gov.hmcts.reform.deedpoll.api;

import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The audit action a write operation records, stated on the handler method beside its mapping.
 *
 * <p>A statement, not a mechanism: the service the method calls records the event, in the write's own transaction,
 * and this annotation records nothing. What it buys is that {@code WriteEndpointAuditCoverageTest} can hold every
 * {@code POST}, {@code PUT}, {@code PATCH} and {@code DELETE} mapping to either this or a short, named exemption list,
 * so a new write route cannot arrive without its author saying what it records.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AuditedAs {

    /** The action the operation records when it changes something. */
    AuditAction value();
}
