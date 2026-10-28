package uk.gov.hmcts.reform.deedpoll.domain.masters;

/**
 * One entry on the Delivery Manager's Masters list, as staff may see it.
 *
 * <p>{@link #linked} says whether an IDAM account has been linked, and the account itself is deliberately not a
 * component: an ownership id is never published, so a type that cannot carry it cannot leak it.
 */
public record Master(String id, String name, String email, boolean available, boolean linked, int revision) {
}
