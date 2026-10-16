package uk.gov.hmcts.reform.deedpoll.domain.documents;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.support.DocumentFixtures;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocxArchiveBudgetTest {
    private static final int MIB = 1024 * 1024;

    @Test
    @DisplayName("a valid DOCX expanded to exactly 50 MiB passes the aggregate budget")
    void acceptsTotalAtLimit() throws IOException {
        byte[] bytes = docxExpandedTo(50 * MIB);
        assertOtherLimitsPass(bytes, 50 * MIB);
        assertThatCode(() -> verify(bytes)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a valid DOCX one byte over 50 MiB fails even though every other budget passes")
    void refusesTotalOverLimit() throws IOException {
        byte[] bytes = docxExpandedTo(50 * MIB + 1);
        assertOtherLimitsPass(bytes, 50 * MIB + 1);
        assertThatThrownBy(() -> verify(bytes)).isInstanceOfSatisfying(InvalidInputError.class,
            error -> assertThat(error.errors()).containsExactly(new FieldError("file", error.getMessage())));
    }

    private static void verify(byte[] bytes) {
        UploadContentPolicy.verify(UploadedFile.of(bytes, "budget.docx", DocumentFixtures.DOCX));
    }

    private static byte[] docxExpandedTo(int total) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output);
             ZipInputStream base = new ZipInputStream(
                 new ByteArrayInputStream(DocumentFixtures.docx(Map.of())))) {
            int remaining = total;
            ZipEntry entry;
            while ((entry = base.getNextEntry()) != null) {
                zip.putNextEntry(new ZipEntry(entry.getName()));
                remaining -= Math.toIntExact(base.transferTo(zip));
                zip.closeEntry();
            }
            Random random = new Random(112);
            byte[] block = new byte[4096];
            for (int part = 0; remaining > 0; part++) {
                int size = Math.min(20 * MIB, remaining);
                remaining -= size;
                zip.putNextEntry(new ZipEntry("word/padding" + part + ".bin"));
                while (size > 0) {
                    // Fresh noise in each block keeps the ratio above 1%, but the upload below 25 MiB.
                    random.nextBytes(block);
                    Arrays.fill(block, 512, block.length, (byte) 0);
                    int length = Math.min(size, block.length);
                    zip.write(block, 0, length);
                    size -= length;
                }
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        }
    }

    private static void assertOtherLimitsPass(byte[] bytes, long expectedTotal) throws IOException {
        assertThat(bytes.length).isLessThanOrEqualTo(25 * MIB);
        long total = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                long size = zip.transferTo(OutputStream.nullOutputStream());
                assertThat(size).isLessThanOrEqualTo(25L * MIB);
                if (size > 100_000) {
                    assertThat((double) entry.getCompressedSize() / size).isGreaterThanOrEqualTo(0.01);
                }
                total += size;
                entries++;
            }
        }
        assertThat(entries).isLessThanOrEqualTo(1000);
        assertThat(total).isEqualTo(expectedTotal);
    }
}
