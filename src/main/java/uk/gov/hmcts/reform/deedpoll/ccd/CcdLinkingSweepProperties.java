package uk.gov.hmcts.reform.deedpoll.ccd;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Whether the case list links records, and how much of it one list request may do:
 * {@code deedpoll.ccd.linking.sweep} (plan 02b, decisions 3 and 4).
 *
 * <p><strong>Off by default, and switching it on is part of onboarding.</strong> Until the case type, the system
 * user and the S2S secret exist, every attempt fails before submission, and an enabled sweep would spend each
 * record's {@code max-attempts} on list requests and leave the whole table {@code NeedsRepair}.
 *
 * @param enabled             whether {@link CcdLinkingSweep#sweep} does anything at all
 * @param maxRecords          how many records one sweep may link: each is a conversation with IDAM, S2S and CCD
 *                            that the list request waits for
 * @param maxReconciliations  how many outstanding attempts one sweep may read, after it has linked
 */
@ConfigurationProperties(prefix = "deedpoll.ccd.linking.sweep")
public record CcdLinkingSweepProperties(boolean enabled, int maxRecords, int maxReconciliations) {

    public CcdLinkingSweepProperties {
        if (maxRecords < 1) {
            throw new IllegalArgumentException("deedpoll.ccd.linking.sweep.max-records must be at least 1");
        }
        if (maxReconciliations < 1) {
            throw new IllegalArgumentException("deedpoll.ccd.linking.sweep.max-reconciliations must be at least 1");
        }
    }
}
