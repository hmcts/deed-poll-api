package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

/**
 * The Master a referral is assigned to, as a Masters-list entry: its id and the name the referral was assigned
 * under. A type with no component for the IDAM subject or the email, so neither can be published through it.
 */
public record AssignedMasterResponse(String id, String name) {
}
