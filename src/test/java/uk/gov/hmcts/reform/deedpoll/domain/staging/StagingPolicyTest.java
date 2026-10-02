package uk.gov.hmcts.reform.deedpoll.domain.staging;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadedFile;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StagingPolicyTest {
    static StagingPolicy policy(int files, long ownerBytes, int cleanupRows) {
        return new StagingPolicy(true, 10485760, files, ownerBytes, ownerBytes, Duration.ofHours(1),
            Duration.ofHours(2), 60000, cleanupRows, 26214400, Duration.ofSeconds(2));
    }

    @Test
    void disabledByDefaultAndMissingSettingsPreventStartup() {
        new ApplicationContextRunner().withUserConfiguration(StagingConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(StagingPolicy.class).enabled()).isFalse();
            assertThat(context).doesNotHaveBean(StagingConfiguration.Cleanup.class);
        });
        new ApplicationContextRunner().withUserConfiguration(StagingConfiguration.class)
            .withPropertyValues("deedpoll.documents.staging.enabled=true")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void configuredActivationWiresAnIndependentCleanupSchedule() {
        var staging = org.mockito.Mockito.mock(OwnedStaging.class);
        new ApplicationContextRunner().withUserConfiguration(StagingConfiguration.class)
            .withBean(OwnedStaging.class, () -> staging)
            .withPropertyValues("deedpoll.documents.staging.enabled=true",
                "deedpoll.documents.staging.max-total-bytes=10485760",
                "deedpoll.documents.staging.record-max-files=2",
                "deedpoll.documents.staging.record-max-bytes=1000",
                "deedpoll.documents.staging.draft-max-bytes=1000",
                "deedpoll.documents.staging.record-expiry=1h", "deedpoll.documents.staging.draft-expiry=2h",
                "deedpoll.documents.staging.cleanup-interval-ms=60000",
                "deedpoll.documents.staging.cleanup-rows=2", "deedpoll.documents.staging.cleanup-bytes=26214400",
                "deedpoll.documents.staging.cleanup-time=2s")
            .run(context -> {
                assertThat(context).hasNotFailed();
                context.getBean(StagingConfiguration.Cleanup.class).run();
                org.mockito.Mockito.verify(staging).expire();
            });
    }

    @Test
    void everyActivationLimitIsRequired() {
        for (int missing = 0; missing < 10; missing++) {
            int field = missing;
            assertThatThrownBy(() -> new StagingPolicy(true, field == 0 ? 0 : 100, field == 1 ? 0 : 2,
                field == 2 ? 0 : 100, field == 3 ? 0 : 100, field == 4 ? null : Duration.ofHours(1),
                field == 5 ? Duration.ZERO : Duration.ofHours(1), field == 6 ? 0 : 100,
                field == 7 ? 0 : 2, field == 8 ? 0 : 26214400, field == 9 ? null : Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void ownerSpecificCeilingsAndMetadataValidation() {
        var draft = new StageOwner(StageOwner.Kind.DRAFT, "draft", "subject");
        var record = new StageOwner(StageOwner.Kind.RECORD, "case", "subject");
        var policy = policy(3, 30000000, 2);
        byte[] bytes = new byte[7340033];
        assertThatThrownBy(() -> policy.validate(draft, UploadedFile.of(bytes, "file.pdf", "application/pdf")))
            .isInstanceOf(ConflictError.class);
        policy.validate(record, UploadedFile.of(bytes, "file.pdf", "application/pdf"));
        assertThat(policy.maxFiles(draft)).isEqualTo(10);
        assertThat(policy.maxFiles(record)).isEqualTo(3);
        assertThat(policy.expiry(draft)).isEqualTo(Duration.ofHours(2));
        for (UploadedFile invalid : new UploadedFile[]{UploadedFile.of(new byte[0], "a.pdf", "application/pdf"),
            new UploadedFile(new byte[1], "a.pdf", "application/pdf", 2),
            UploadedFile.of(new byte[1], "../a.pdf", "application/pdf"),
            UploadedFile.of(new byte[1], "a.pdf", "application/x-executable")}) {
            assertThatThrownBy(() -> policy.validate(record, invalid)).isInstanceOf(ConflictError.class);
        }
        assertThatThrownBy(() -> new StageOwner(StageOwner.Kind.DRAFT, "draft", ""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(draft.toString()).doesNotContain("draft", "subject");
    }
}
