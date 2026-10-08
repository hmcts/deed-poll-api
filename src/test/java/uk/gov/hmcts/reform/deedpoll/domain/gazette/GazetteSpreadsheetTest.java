package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Nationality;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reopens the produced files against independent synthetic examples and the supplied visible header order. */
class GazetteSpreadsheetTest {

    private final GazetteSpreadsheet generator = new GazetteSpreadsheet();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void mixedBatchMatchesEverySampleCellAndTheExactVisibleColumnOrder() throws IOException {
        List<GeneratedGazetteSpreadsheet> files = generator.generate(samples());
        assertThat(files).extracting(GeneratedGazetteSpreadsheet::filename)
            .containsExactly("gazette-adult.xlsx", "gazette-minor.xlsx");
        for (int index = 0; index < files.size(); index++) {
            String kind = index == 0 ? "adult" : "minor";
            List<List<String>> expected;
            try (InputStream source = resource(kind + "-expected.json")) {
                expected = mapper.readValue(source, new TypeReference<>() {});
            }
            GeneratedGazetteSpreadsheet file = files.get(index);
            assertThat(file.caseCount()).isEqualTo(2);
            assertThat(file.mimeType()).isEqualTo(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            try (XSSFWorkbook workbook = open(file)) {
                assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
                assertThat(workbook.getAllNames()).isEmpty();
                assertThat(workbook.getExternalLinksTable()).isEmpty();
                assertThat(workbook.isSheetHidden(0)).isFalse();
                var sheet = workbook.getSheetAt(0);
                assertThat(sheet.getLastRowNum()).isEqualTo(2);
                assertThat(sheet.getDataValidations()).isEmpty();
                assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
                assertThat(sheet.getTables()).hasSize(1);
                String area = index == 0 ? "A1:AA3" : "A1:U3";
                assertThat(sheet.getTables().getFirst().getCTTable().getRef()).isEqualTo(area);
                assertThat(sheet.getTables().getFirst().getCTTable().getAutoFilter().getRef()).isEqualTo(area);
                DataFormatter formatter = new DataFormatter(Locale.UK);
                for (int row = 0; row < expected.size(); row++) {
                    assertThat(sheet.getRow(row).getLastCellNum()).isEqualTo((short) expected.get(row).size());
                    for (int column = 0; column < expected.get(row).size(); column++) {
                        assertThat(sheet.isColumnHidden(column)).isFalse();
                        var cell = sheet.getRow(row).getCell(column);
                        assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA);
                        assertThat(formatter.formatCellValue(cell)).as("%s row %s column %s", kind, row, column)
                            .isEqualTo(expected.get(row).get(column));
                        if (row > 0 && expected.get(row).get(column).isEmpty()) {
                            assertThat(cell.getCellType()).isEqualTo(CellType.BLANK);
                        }
                    }
                }
                assertThat(sheet.getRow(1).getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
                assertThat(sheet.getRow(1).getCell(1).getCellStyle().getDataFormatString()).isEqualTo("dd/mm/yyyy");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = DeedPollType.class, names = {"Adult", "AdultTransgender", "Minor", "MinorTransgender"})
    void singleGroupProducesOnlyItsOwnFile(DeedPollType type) throws IOException {
        DeedPoll record = samples().getFirst();
        record.setType(type);
        var files = generator.generate(List.of(record));
        boolean adult = type == DeedPollType.Adult || type == DeedPollType.AdultTransgender;
        assertThat(files).hasSize(1);
        assertThat(files.getFirst().filename()).isEqualTo(adult ? "gazette-adult.xlsx" : "gazette-minor.xlsx");
        try (XSSFWorkbook workbook = open(files.getFirst())) {
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(1);
        }
    }

    @Test
    void preservesSelectionOrderWithinEachGroup() throws IOException {
        List<DeedPoll> cases = samples();
        var files = generator.generate(List.of(cases.get(3), cases.get(1), cases.get(2), cases.get(0)));
        try (XSSFWorkbook adults = open(files.get(0)); XSSFWorkbook minors = open(files.get(1))) {
            assertThat(adults.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo("KBDP2026-00002");
            assertThat(adults.getSheetAt(0).getRow(2).getCell(0).getStringCellValue()).isEqualTo("KBDP2026-00001");
            assertThat(minors.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo("KBDP2026-00004");
            assertThat(minors.getSheetAt(0).getRow(2).getCell(0).getStringCellValue()).isEqualTo("KBDP2026-00003");
        }
    }

    @ParameterizedTest
    @NullSource
    @EnumSource(value = DeedPollType.class, names = {"DeedOfRelinquishment", "Unknown"})
    void refusesTheWholeSelectionForAnExcludedOrUnclassifiedCase(DeedPollType type) throws IOException {
        List<DeedPoll> cases = samples();
        cases.getLast().setType(type);
        assertThatThrownBy(() -> generator.generate(cases)).isInstanceOf(ConflictError.class);
    }

    @Test
    void refusesEmptyDuplicateAndMissingCases() throws IOException {
        DeedPoll record = samples().getFirst();
        assertThatThrownBy(() -> generator.generate(List.of())).isInstanceOf(InvalidInputError.class);
        assertThatThrownBy(() -> generator.generate(null)).isInstanceOf(InvalidInputError.class);
        assertThatThrownBy(() -> generator.generate(List.of(record, record))).isInstanceOf(InvalidInputError.class);
        assertThatThrownBy(() -> generator.generate(Arrays.asList(record, null))).isInstanceOf(InvalidInputError.class);
        record.setId(null);
        assertThatThrownBy(() -> generator.generate(List.of(record))).isInstanceOf(InvalidInputError.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"=HYPERLINK(\"https://example.com\")", "+SUM(1,2)", "-1+2", "@SUM(A1)",
        "  A & <B> 'C' \"D\"\r\n雪 😀  ", "_x0041_", "_x005F_x0041_", "Flat 4\u000B12 Road", "A\u0000B"})
    void preservesLiteralTextWithoutFormulasOrLinks(String value) throws IOException {
        DeedPoll record = samples().getFirst();
        record.setNewForename(value);
        try (XSSFWorkbook workbook = open(generator.generate(List.of(record)).getFirst())) {
            var cell = workbook.getSheetAt(0).getRow(1).getCell(4);
            assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
            assertThat(cell.getStringCellValue()).isEqualTo(value);
            assertThat(cell.getHyperlink()).isNull();
        }
    }

    @Test
    void datesUseTheStoredCalendarDayAndReferencesNeverFallBackToTheSerial() throws IOException {
        DeedPoll record = samples().getFirst();
        record.setDisplayReference(null);
        record.setDateOfApplication(null);
        record.setDateReceived(LocalDateTime.of(2026, 1, 1, 12, 0));
        record.setDateEnroled(LocalDateTime.of(2026, 10, 8, 23, 59));
        record.setNationality(Nationality.Unknown);
        try (XSSFWorkbook workbook = open(generator.generate(List.of(record)).getFirst())) {
            var row = workbook.getSheetAt(0).getRow(1);
            assertThat(row.getCell(0).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(row.getCell(1).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(row.getCell(2).getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2026, 10, 8, 0, 0));
            assertThat(row.getCell(11).getStringCellValue()).isEqualTo("Unknown");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1899, 10000})
    void refusesUnrepresentableDatesInsteadOfChangingThem(int year) throws IOException {
        DeedPoll record = samples().getFirst();
        record.setDateOfApplication(LocalDateTime.of(year, 1, 1, 0, 0));
        assertThatThrownBy(() -> generator.generate(List.of(record))).isInstanceOf(ConflictError.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0xD800, 0xDC00})
    void refusesMalformedUnicodeInsteadOfCorruptingTheWorkbook(int value) throws IOException {
        DeedPoll record = samples().getFirst();
        record.setNewForename("bad" + (char) value);
        assertThatThrownBy(() -> generator.generate(List.of(record))).isInstanceOf(ConflictError.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void absentTextStaysBlank(String value) throws IOException {
        DeedPoll record = samples().getFirst();
        record.setNewMiddleName(value);
        try (XSSFWorkbook workbook = open(generator.generate(List.of(record)).getFirst())) {
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(5).getCellType()).isEqualTo(CellType.BLANK);
        }
    }

    @ParameterizedTest
    @EnumSource(value = DeedPollType.class, names = {"Adult", "Minor"})
    void missingDataLeavesEveryColumnPresentAndBlank(DeedPollType type) throws IOException {
        DeedPoll record = new DeedPoll();
        record.setId("sparse-case");
        record.setType(type);
        record.setRepresentativeEmail("not-exported@example.com");
        record.setRepresentativeTelephone("not-exported");
        try (XSSFWorkbook workbook = open(generator.generate(List.of(record)).getFirst())) {
            var row = workbook.getSheetAt(0).getRow(1);
            int count = type == DeedPollType.Adult ? 27 : 21;
            assertThat(row.getLastCellNum()).isEqualTo((short) count);
            for (int index = 0; index < count; index++) {
                assertThat(row.getCell(index).getCellType())
                    .isEqualTo(index == 3 ? CellType.STRING : CellType.BLANK);
            }
        }
    }

    @Test
    void templatesContainOnlyTheCleanWorkbookParts() throws IOException {
        for (String kind : List.of("adult", "minor")) {
            try (ZipInputStream zip = new ZipInputStream(GazetteSpreadsheet.class.getResourceAsStream(
                "/gazette-templates/" + kind + ".xlsx"))) {
                List<String> entries = new java.util.ArrayList<>();
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    entries.add(entry.getName());
                }
                assertThat(entries).containsExactlyInAnyOrder("[Content_Types].xml", "_rels/.rels",
                    "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml",
                    "xl/worksheets/_rels/sheet1.xml.rels", "xl/tables/table1.xml");
            }
        }
    }

    private List<DeedPoll> samples() throws IOException {
        try (InputStream source = resource("cases.json")) {
            return mapper.readValue(source, new TypeReference<>() {});
        }
    }

    private static InputStream resource(String name) {
        return GazetteSpreadsheetTest.class.getResourceAsStream("/gazette/" + name);
    }

    private static XSSFWorkbook open(GeneratedGazetteSpreadsheet file) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(file.content()));
    }
}
