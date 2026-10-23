package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadContentPolicy;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadedFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.plugins.tiff.BaselineTIFFTagSet;
import javax.imageio.plugins.tiff.TIFFDirectory;
import javax.imageio.plugins.tiff.TIFFField;
import javax.imageio.stream.ImageInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The committed fixtures are what {@link DocumentPreviewFixtures} says they are, and can be made again from it.
 */
class DocumentPreviewFixturesTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "REGENERATE_DOCUMENT_PREVIEWS", matches = "true")
    @DisplayName("regenerates every committed fixture (REGENERATE_DOCUMENT_PREVIEWS=true only)")
    void regenerate() throws IOException {
        Path directory = Path.of("src/test/resources/document-previews");
        for (String name : DocumentPreviewFixtures.NAMES) {
            Files.write(directory.resolve(name), DocumentPreviewFixtures.generate(name));
        }
    }

    @Test
    @DisplayName("the PDF has three pages")
    void pdf() throws IOException {
        try (PDDocument document = Loader.loadPDF(DocumentPreviewFixtures.read(DocumentPreviewFixtures.PDF))) {
            assertThat(document.getNumberOfPages()).isEqualTo(DocumentPreviewFixtures.PDF_PAGES);
        }
    }

    @Test
    @DisplayName("each image fixture has its frames' dimensions, compression and orientation, in order")
    void images() throws IOException {
        for (DocumentPreviewFixtures.Fixture fixture : java.util.List.of(
            DocumentPreviewFixtures.PHOTO, DocumentPreviewFixtures.PHOTO_ON_ITS_SIDE, DocumentPreviewFixtures.SCAN,
            DocumentPreviewFixtures.TWO_FRAMES, DocumentPreviewFixtures.THREE_FRAMES)) {
            byte[] bytes = DocumentPreviewFixtures.read(fixture.name());
            try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                ImageReader reader = readers.next();
                try {
                    reader.setInput(input);
                    assertThat(reader.getNumImages(true)).as(fixture.name()).isEqualTo(fixture.frames().size());
                    for (int index = 0; index < fixture.frames().size(); index++) {
                        DocumentPreviewFixtures.Frame frame = fixture.frames().get(index);
                        assertThat(reader.getWidth(index)).as(fixture.name()).isEqualTo(frame.width());
                        assertThat(reader.getHeight(index)).as(fixture.name()).isEqualTo(frame.height());
                        if (fixture.name().endsWith(".tiff")) {
                            assertTiffFrame(reader.getImageMetadata(index), frame, fixture.name());
                        }
                    }
                } finally {
                    reader.dispose();
                }
            }
        }
    }

    @Test
    @DisplayName("the sideways photo carries EXIF orientation 6 and the upright one carries none")
    void exif() {
        assertThat(jpegOrientation(DocumentPreviewFixtures.PHOTO_ON_ITS_SIDE.name())).isEqualTo(Orientation.RIGHT_TOP);
        assertThat(jpegOrientation(DocumentPreviewFixtures.PHOTO.name())).isEqualTo(Orientation.TOP_LEFT);
    }

    @Test
    @DisplayName("the multi-page DOCX has a page break after each of its first two pages")
    void multiPageDocx() throws IOException {
        try (XWPFDocument document = docx(DocumentPreviewFixtures.MULTI_PAGE_DOCX)) {
            assertThat(document.getParagraphs()).hasSize(DocumentPreviewFixtures.MULTI_PAGE_DOCX_PAGES);
            long breaks = document.getParagraphs().stream()
                .filter(paragraph -> paragraph.getCTP().xmlText().contains("w:type=\"page\""))
                .count();
            assertThat(breaks).isEqualTo(DocumentPreviewFixtures.MULTI_PAGE_DOCX_PAGES - 1);
            assertThat(document.getParagraphs().getLast().getText()).isEqualTo("Synthetic Word fixture, page 3");
        }
    }

    @Test
    @DisplayName("the table-and-image DOCX has its table, then its picture on a new page")
    void tableAndImageDocx() throws IOException {
        try (XWPFDocument document = docx(DocumentPreviewFixtures.TABLE_AND_IMAGE_DOCX)) {
            XWPFTable table = document.getTables().getFirst();
            assertThat(document.getTables()).hasSize(1);
            assertThat(table.getNumberOfRows()).isEqualTo(DocumentPreviewFixtures.TABLE_ROWS);
            assertThat(table.getRow(0).getTableCells()).hasSize(DocumentPreviewFixtures.TABLE_COLUMNS);
            assertThat(table.getRow(3).getCell(2).getText()).isEqualTo("r3c2");
            assertThat(document.getAllPictures()).hasSize(1);
            XWPFParagraph pictured = document.getParagraphs().getLast();
            assertThat(pictured.isPageBreak()).isTrue();
            assertThat(pictured.getRuns().getFirst().getEmbeddedPictures()).hasSize(1);
        }
    }

    @Test
    @DisplayName("both DOCX fixtures are uploads the service accepts; their macro and encrypted copies are not")
    void docxAcceptance() {
        for (String name : java.util.List.of(DocumentPreviewFixtures.MULTI_PAGE_DOCX,
                                             DocumentPreviewFixtures.TABLE_AND_IMAGE_DOCX)) {
            byte[] bytes = DocumentPreviewFixtures.read(name);
            assertThatCode(() -> UploadContentPolicy.verify(docxUpload(bytes))).as(name).doesNotThrowAnyException();
            assertThatThrownBy(() -> UploadContentPolicy.verify(docxUpload(DocumentPreviewFixtures.withMacros(bytes))))
                .as(name).isInstanceOf(InvalidInputError.class);
            assertThatThrownBy(() -> UploadContentPolicy.verify(docxUpload(DocumentPreviewFixtures.encrypted(bytes))))
                .as(name).isInstanceOf(InvalidInputError.class);
        }
    }

    private static XWPFDocument docx(String name) throws IOException {
        return new XWPFDocument(new ByteArrayInputStream(DocumentPreviewFixtures.read(name)));
    }

    private static UploadedFile docxUpload(byte[] bytes) {
        return UploadedFile.of(bytes, "fixture.docx", PreviewFormat.DOCX.mimeType());
    }

    private static Orientation jpegOrientation(String name) {
        return ExifOrientation.of(DocumentPreviewFixtures.read(name));
    }

    private static void assertTiffFrame(IIOMetadata metadata, DocumentPreviewFixtures.Frame frame, String name)
        throws IOException {
        TIFFDirectory directory = TIFFDirectory.createFromMetadata(metadata);
        TIFFField compression = directory.getTIFFField(BaselineTIFFTagSet.TAG_COMPRESSION);
        int expected = switch (frame.compression() == null ? "None" : frame.compression()) {
            case "None" -> BaselineTIFFTagSet.COMPRESSION_NONE;
            case "LZW" -> BaselineTIFFTagSet.COMPRESSION_LZW;
            case "Deflate" -> BaselineTIFFTagSet.COMPRESSION_DEFLATE;
            case "PackBits" -> BaselineTIFFTagSet.COMPRESSION_PACKBITS;
            case "CCITT T.6" -> BaselineTIFFTagSet.COMPRESSION_CCITT_T_6;
            default -> throw new IllegalArgumentException(frame.compression());
        };
        assertThat(compression.getAsInt(0)).as(name).isEqualTo(expected);
        TIFFField orientation = directory.getTIFFField(BaselineTIFFTagSet.TAG_ORIENTATION);
        assertThat(orientation == null ? 1 : orientation.getAsInt(0)).as(name).isEqualTo(frame.orientation());
    }
}
