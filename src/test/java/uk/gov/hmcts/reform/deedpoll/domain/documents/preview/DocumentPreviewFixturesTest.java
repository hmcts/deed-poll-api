package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

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
