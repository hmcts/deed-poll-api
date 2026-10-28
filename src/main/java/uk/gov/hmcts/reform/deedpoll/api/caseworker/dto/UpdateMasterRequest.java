package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.api.KeyedRequestBody;

/**
 * A keyed patch: at least one of {@code name}, {@code email} and {@code available}, with the revision read.
 */
public final class UpdateMasterRequest extends KeyedRequestBody {
}
