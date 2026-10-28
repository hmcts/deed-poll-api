package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.api.KeyedRequestBody;

/**
 * The processing window and revision are required; the other settings are optional for one release, and an absent
 * one is left unchanged. The domain rejects unknown keys, incorrect JSON types and out-of-range values.
 */
public final class UpdateProcessSettingsRequest extends KeyedRequestBody {
}
