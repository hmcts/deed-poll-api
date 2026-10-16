package uk.gov.hmcts.reform.deedpoll.domain.documents;

import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentParserConfigurationTest {
    @Test
    @DisplayName("startup sets all POI limits before creating reader beans, without any DOCX upload")
    void setsLimitsBeforeReaderBeans() {
        double originalRatio = ZipSecureFile.getMinInflateRatio();
        long originalSize = ZipSecureFile.getMaxEntrySize();
        long originalCount = ZipSecureFile.getMaxFileCount();
        try {
            // Distinct sentinels prevent an earlier test's class loading from making this pass accidentally.
            ZipSecureFile.setMinInflateRatio(0.02);
            ZipSecureFile.setMaxEntrySize(1234);
            ZipSecureFile.setMaxFileCount(12);
            new ApplicationContextRunner().withUserConfiguration(DocumentParserConfiguration.class)
                .withBean("readerProbe", Object.class, () -> {
                    assertThat(ZipSecureFile.getMinInflateRatio()).isEqualTo(0.01);
                    assertThat(ZipSecureFile.getMaxEntrySize()).isEqualTo(25L * 1024 * 1024);
                    assertThat(ZipSecureFile.getMaxFileCount()).isEqualTo(1000);
                    return new Object();
                }).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("readerProbe");
                });
        } finally {
            ZipSecureFile.setMinInflateRatio(originalRatio);
            ZipSecureFile.setMaxEntrySize(originalSize);
            ZipSecureFile.setMaxFileCount(originalCount);
        }
    }
}
