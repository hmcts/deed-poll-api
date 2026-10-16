package uk.gov.hmcts.reform.deedpoll.domain.documents;

import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.openxml4j.util.ZipSecureFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Bounded package inspection; never follows external relationships or extracts files onto disk. */
final class DocxContentPolicy {
    private static final String MAIN_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
    private static final long MAX_ENTRY_BYTES = UploadPolicy.MAX_UPLOAD_BYTES;
    private static final long MAX_EXPANDED_BYTES = 2 * MAX_ENTRY_BYTES;
    private static final int MAX_ENTRIES = 1000;
    private static final double MIN_RATIO = 0.01;

    static {
        // POI bounds are process-wide. Set once, never weaken them around an individual request.
        ZipSecureFile.setMinInflateRatio(MIN_RATIO);
        ZipSecureFile.setMaxEntrySize(MAX_ENTRY_BYTES);
        ZipSecureFile.setMaxFileCount(MAX_ENTRIES);
    }

    private DocxContentPolicy() {
    }

    static void verify(byte[] bytes) throws IOException, InvalidFormatException {
        checkArchiveBudget(bytes);
        try (OPCPackage document = OPCPackage.open(new ByteArrayInputStream(bytes))) {
            int mainParts = 0;
            for (PackageRelationship relationship : document.getRelationships()) {
                checkRelationship(relationship);
                if (relationship.getRelationshipType().endsWith("/officeDocument")) {
                    require(relationship.getTargetMode() == TargetMode.INTERNAL);
                    PackagePart main = document.getPart(relationship);
                    require(main != null && MAIN_TYPE.equals(main.getContentType()));
                    mainParts++;
                }
            }
            require(mainParts == 1);
            for (PackagePart part : document.getParts()) {
                checkPart(part);
            }
        }
    }

    private static void checkPart(PackagePart part) throws InvalidFormatException {
        String name = part.getPartName().getName().toLowerCase(Locale.ROOT);
        String type = part.getContentType().toLowerCase(Locale.ROOT);
        require(!name.endsWith("/vbaproject.bin") && !name.contains("/activex/")
            && !name.startsWith("/word/embeddings/") && !type.contains("vbaproject")
            && !type.contains("macroenabled") && !type.contains("activex"));
        if (!part.isRelationshipPart()) {
            for (PackageRelationship relationship : part.getRelationships()) {
                checkRelationship(relationship);
            }
        }
    }

    private static void checkRelationship(PackageRelationship relationship) {
        String type = relationship.getRelationshipType();
        require(relationship.getTargetMode() != TargetMode.EXTERNAL
            || !(type.endsWith("/attachedTemplate") || type.endsWith("/oleObject") || type.endsWith("/frame")));
    }

    private static void checkArchiveBudget(byte[] bytes) throws IOException {
        // OPCPackage buffers entries. Count expanded bytes BEFORE handing it the archive, including parts
        // it might otherwise ignore. Per-entry bounds alone do not bound the sum of many smaller entries.
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            Set<String> names = new HashSet<>();
            byte[] buffer = new byte[8192];
            long total = 0;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                require(names.add(entry.getName()) && names.size() <= MAX_ENTRIES);
                long size = 0;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    size += read;
                    total += read;
                    require(size <= MAX_ENTRY_BYTES && total <= MAX_EXPANDED_BYTES);
                }
                require(size <= 100_000 || (double) entry.getCompressedSize() / size >= MIN_RATIO);
                zip.closeEntry();
            }
            require(!names.isEmpty());
        }
    }

    private static void require(boolean accepted) {
        if (!accepted) {
            throw new IllegalArgumentException("Unsupported document package");
        }
    }
}
