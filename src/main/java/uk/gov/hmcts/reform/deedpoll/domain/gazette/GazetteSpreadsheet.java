package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Produces the adult/minor files for one selection, without persistence or remote calls.
 * The future send service must supply a frozen selection and revalidate readiness under its concurrency control.
 * No file is returned if any selected case cannot be classified; cases are never silently dropped.
 */
@Component
public class GazetteSpreadsheet {

    /** Files are returned adult first, then minor, omitting absent groups. Input order survives within each file. */
    public List<GeneratedGazetteSpreadsheet> generate(List<DeedPoll> cases) {
        if (cases == null || cases.isEmpty()) {
            throw new InvalidInputError("Select at least one case for Gazette publication.");
        }
        Set<String> ids = new HashSet<>();
        List<DeedPoll> adults = new ArrayList<>();
        List<DeedPoll> minors = new ArrayList<>();
        for (DeedPoll record : cases) {
            if (record == null || record.getId() == null || record.getId().isBlank() || !ids.add(record.getId())) {
                throw new InvalidInputError("Gazette selection must contain distinct cases with IDs.");
            }
            if (record.getType() == null) {
                throw unclassified();
            }
            switch (record.getType()) {
                case Adult, AdultTransgender -> adults.add(record);
                case Minor, MinorTransgender -> minors.add(record);
                case DeedOfRelinquishment -> throw new ConflictError(
                    "Deeds of Relinquishment cannot be included in Gazette publication.");
                case Unknown -> throw unclassified();
            }
        }
        List<GazetteInput> inputs = new ArrayList<>();
        adults.forEach(row -> inputs.add(GazetteInput.from(row)));
        minors.forEach(row -> inputs.add(GazetteInput.from(row)));
        return generateInputs(inputs);
    }

    /** Generates from admission's detached values; no later read of live case entities is required. */
    public List<GeneratedGazetteSpreadsheet> generateInputs(List<GazetteInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new InvalidInputError("Select at least one case for Gazette publication.");
        }
        List<GeneratedGazetteSpreadsheet> files = new ArrayList<>();
        for (String kind : List.of("adult", "minor")) {
            List<GazetteInput> group = inputs.stream().filter(input -> kind.equals(input.kind())).toList();
            if (!group.isEmpty()) {
                files.add(workbook(group, kind.equals("adult")));
            }
        }
        return List.copyOf(files);
    }

    private static ConflictError unclassified() {
        return new ConflictError("Correct unknown or missing case types before Gazette publication.");
    }

    private GeneratedGazetteSpreadsheet workbook(List<GazetteInput> cases, boolean adult) {
        if (cases.size() >= SpreadsheetVersion.EXCEL2007.getMaxRows()) {
            throw new InvalidInputError("Gazette selection exceeds the Excel row limit.");
        }
        String kind = adult ? "adult" : "minor";
        List<GazetteColumn> columns = GazetteColumn.columns(adult);
        try (InputStream source = template(kind);
             XSSFWorkbook workbook = new XSSFWorkbook(source);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            checkHeaders(sheet, columns);
            if (sheet.getCTWorksheet().isSetDataValidations()) {
                sheet.getCTWorksheet().unsetDataValidations();
            }
            sheet.createFreezePane(0, 1);
            CellStyle textStyle = workbook.createCellStyle();
            textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy"));
            for (int index = 0; index < cases.size(); index++) {
                Row row = sheet.createRow(index + 1);
                for (int column = 0; column < columns.size(); column++) {
                    write(row.createCell(column), cases.get(index).values().get(column), textStyle, dateStyle);
                }
            }
            AreaReference area = new AreaReference(new CellReference(0, 0),
                new CellReference(cases.size(), columns.size() - 1), SpreadsheetVersion.EXCEL2007);
            sheet.getTables().getFirst().setArea(area);
            sheet.getTables().getFirst().getCTTable().getAutoFilter().setRef(area.formatAsString());
            workbook.write(bytes);
            return new GeneratedGazetteSpreadsheet("gazette-" + kind + ".xlsx", cases.size(), bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not generate Gazette spreadsheet.", exception);
        }
    }

    private static InputStream template(String kind) {
        InputStream source = GazetteSpreadsheet.class.getResourceAsStream("/gazette-templates/" + kind + ".xlsx");
        if (source == null) {
            throw new IllegalStateException("Missing Gazette spreadsheet template.");
        }
        return source;
    }

    private static void checkHeaders(XSSFSheet sheet, List<GazetteColumn> columns) {
        Row header = sheet.getRow(0);
        if (header == null || header.getLastCellNum() != columns.size()) {
            throw new IllegalStateException("Gazette template columns do not match the mapping.");
        }
        for (int index = 0; index < columns.size(); index++) {
            if (header.getCell(index) == null
                || !columns.get(index).header().equals(header.getCell(index).getStringCellValue())) {
                throw new IllegalStateException("Gazette template header does not match the mapping.");
            }
        }
    }

    private static void write(Cell cell, Object value, CellStyle textStyle, CellStyle dateStyle) {
        cell.setCellStyle(textStyle);
        if (value instanceof LocalDateTime date) {
            if (date.getYear() < 1900 || date.getYear() > 9999) {
                throw new ConflictError("Correct dates outside the Excel date range before Gazette publication.");
            }
            // Stored date-times are UTC. Drop time without conversion through the server's default timezone.
            cell.setCellValue(date.toLocalDate());
            cell.setCellStyle(dateStyle);
        } else if (value instanceof String text) {
            // Explicit string cells: leading =, +, - and @ remain literal text, never formulas.
            if (!text.isEmpty()) {
                cell.setCellValue(GazetteText.encode(text));
            }
        } else if (value != null) {
            throw new IllegalStateException("Unsupported Gazette spreadsheet value type.");
        }
    }
}
