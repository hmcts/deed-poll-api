package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.api.KeyedRequestBody;

/** Both fields are required; the domain rejects unknown keys, incorrect JSON types and out-of-range minutes. */
public final class UpdateProcessSettingsRequest extends KeyedRequestBody {
}
