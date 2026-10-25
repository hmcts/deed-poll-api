package uk.gov.hmcts.reform.deedpoll.cftlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.matching.MultipartValuePattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdCreationAttempts;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingSweep;
import uk.gov.hmcts.reform.deedpoll.ccd.SystemUserTokens;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocumentType;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentTypeRepository;
import uk.gov.hmcts.reform.deedpoll.storage.CorrespondenceDocumentReader;
import uk.gov.hmcts.reform.deedpoll.support.StubDmStore;
import uk.gov.hmcts.reform.idam.client.IdamClient;
import uk.gov.hmcts.rse.ccd.lib.ControlPlane;
import uk.gov.hmcts.rse.ccd.lib.test.CftlibTest;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aMultipart;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Document previews against real CCD and CDAM, with only dm-store and the DOCX converter stubbed.
 *
 * <p>Every preview's original is read through CDAM under the caller's own token, which dm-store's {@code user-id}
 * shows, and CCD's read grant decides it: revoking that grant refuses the preview with CDAM's status before anything
 * reaches the converter. The first test runs with the converter unconfigured and then replaces the API context with
 * one where it is configured, so the same documents prove both the fixed 503 and the converted pages.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles({"cftlib", "development"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentPreviewCftlibTest extends CftlibTest {

    private static final StubDmStore DM = new StubDmStore(4506);
    private static final WireMockServer CONVERTER = new WireMockServer(options().dynamicPort());
    private static final String CONVERT = "/rs/convert";
    private static final String ACCESS_KEY = "synthetic-cftlib-preview-key";
    private static final Color[] FRAMES = {new Color(200, 30, 30), new Color(30, 160, 30), new Color(30, 30, 200)};
    private static final byte[] CONVERTED = pdf(3);

    /** Read by the property supplier whenever a context starts, so the second context sees the converter. */
    private static volatile boolean converterConfigured;

    static {
        CONVERTER.start();
    }

    @DynamicPropertySource
    static void converter(DynamicPropertyRegistry registry) {
        registry.add("deedpoll.documents.preview.converter.url",
            () -> converterConfigured ? CONVERTER.baseUrl() : "");
        registry.add("deedpoll.documents.preview.converter.access-key", () -> ACCESS_KEY);
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, Original> originals = new LinkedHashMap<>();

    @Autowired private IdamClient idam;
    @Autowired private ObjectMapper json;
    @Autowired private DeedPollSupportingDocumentTypeRepository types;
    @Autowired private CcdCreationAttempts attempts;
    @Autowired private CcdLinkingSweep sweep;
    @Autowired private SystemUserTokens systemUser;
    @Autowired private CorrespondenceDocumentReader correspondence;
    @Autowired private ApplicationContext context;
    @Autowired private JdbcTemplate jdbc;

    private ApplicationContext firstContext;
    private String recordId;
    private String otherRecordId;

    private record Original(String id, String filename, String mimeType, byte[] bytes, int pages) { }

    @AfterAll
    void stopStubs() {
        CONVERTER.stop();
        DM.close();
    }

    @Test
    @Order(1)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void rendersEveryLocalFormatThroughCdamAndAnUnconfiguredConverterIsTheFixed503() throws Exception {
        for (int run = 0; run < 20 && !attempts.dueForLinking(1).isEmpty(); run++) {
            sweep.sweep();
        }
        assertThat(attempts.dueForLinking(1)).isEmpty();
        recordId = createRecord();
        otherRecordId = createRecord();
        assertThat(send("/caseworker/cases", CftlibConfig.DOCUMENT_READER).statusCode()).isEqualTo(200);
        String typeId = documentType();
        upload(typeId, "two-pages.pdf", "application/pdf", pdf(2), 2);
        upload(typeId, "photo.jpg", "image/jpeg", image("jpg", FRAMES[0]), 1);
        upload(typeId, "scan.png", "image/png", image("png", FRAMES[1]), 1);
        upload(typeId, "three-frames.tiff", "image/tiff", tiff(), 3);
        upload(typeId, "letter.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx(), 3);

        for (Original original : originals.values()) {
            DM.server().resetRequests();
            HttpResponse<byte[]> preview = preview(caseworkerPreview(recordId, original), CftlibConfig.DOCUMENT_READER);
            if (original.mimeType().endsWith("document")) {
                assertThat(preview.statusCode()).isEqualTo(503);
                assertThat(new String(preview.body(), StandardCharsets.UTF_8)).contains("PREVIEW_UNAVAILABLE")
                    .doesNotContain(ACCESS_KEY, "letter.docx", "localhost");
                // Unconfigured is decided after CDAM has served the caller, so only a reader hears it.
                assertReadAsCaller(original, CftlibConfig.DOCUMENT_READER);
                assertThat(previewsRecorded(original)).isEqualTo(1);
            } else {
                assertPreview(preview, original.pages());
                assertReadAsCaller(original, CftlibConfig.DOCUMENT_READER);
                assertThat(previewsRecorded(original)).isEqualTo(1);
            }
            assertOriginalUnchanged(original);
        }
        assertTiffKeepsEveryFrameInOrder(preview(caseworkerPreview(recordId, originals.get("three-frames.tiff")),
            CftlibConfig.DOCUMENT_READER).body());
        assertThat(CONVERTER.getAllServeEvents()).isEmpty();
        firstContext = context;
        converterConfigured = true;
    }

    @Test
    @Order(2)
    void convertsTheDocxCdamReturnsAndEveryPreviewIsTheWholePdf() throws Exception {
        assertThat(context).isNotSameAs(firstContext);
        stubConverter(aResponse().withStatus(200).withHeader("Content-Type", "application/pdf").withBody(CONVERTED));
        Original docx = originals.get("letter.docx");
        DM.server().resetRequests();
        assertPreview(preview(caseworkerPreview(recordId, docx), CftlibConfig.DOCUMENT_READER), docx.pages());
        assertReadAsCaller(docx, CftlibConfig.DOCUMENT_READER);
        CONVERTER.verify(1, postRequestedFor(urlPathEqualTo(CONVERT))
            .withRequestBodyPart(part("accessKey", ACCESS_KEY.getBytes(StandardCharsets.UTF_8)))
            .withRequestBodyPart(part("file", docx.bytes())));
        assertThat(CONVERTER.getAllServeEvents().getFirst().getRequest().getBodyAsString())
            .doesNotContain("letter.docx");
        assertThat(previewsRecorded(docx)).as("the unconfigured attempt's read, then this one").isEqualTo(2);
        assertOriginalUnchanged(docx);

        CONVERTER.resetRequests();
        for (Original original : originals.values()) {
            HttpResponse<byte[]> ranged = http.send(request(caseworkerPreview(recordId, original),
                CftlibConfig.DOCUMENT_READER).header("Range", "bytes=0-99").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
            assertPreview(ranged, original.pages());
            assertThat(ranged.headers().firstValue("Content-Range")).isEmpty();
        }
        CONVERTER.verify(1, postRequestedFor(urlPathEqualTo(CONVERT)));
    }

    @Test
    @Order(3)
    void everyStaffRolePreviewsUnderItsOwnTokenAndRefusalsReachNeitherStoreNorConverter() throws Exception {
        Original pdf = originals.get("two-pages.pdf");
        for (String user : List.of(CftlibConfig.DOCUMENT_READER, CftlibConfig.LEGACY_ADMIN_USER,
            CftlibConfig.TEAM_LEADER_USER, CftlibConfig.DELIVERY_MANAGER_USER)) {
            DM.server().resetRequests();
            assertPreview(preview(caseworkerPreview(recordId, pdf), user), pdf.pages());
            assertReadAsCaller(pdf, user);
        }
        Original docx = originals.get("letter.docx");
        CONVERTER.resetRequests();
        DM.server().resetRequests();
        int recorded = previewsRecorded(docx);

        HttpResponse<byte[]> anonymous = http.send(HttpRequest.newBuilder(
            URI.create("http://localhost:4550" + caseworkerPreview(recordId, docx))).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(preview(caseworkerPreview(otherRecordId, docx), CftlibConfig.DOCUMENT_READER).statusCode())
            .isEqualTo(404);
        assertThat(DM.server().getAllServeEvents()).isEmpty();

        // As for the download: our persistence gate's 403 reaches the caller as CDAM's 502, never a preview error.
        HttpResponse<byte[]> unmapped = preview(caseworkerPreview(recordId, docx), CftlibConfig.UNMAPPED_USER);
        assertThat(unmapped.statusCode()).isEqualTo(502);
        assertThat(new String(unmapped.body(), StandardCharsets.UTF_8)).doesNotContain("PREVIEW_");
        DM.server().verify(0, getRequestedFor(urlPathEqualTo(binaryPath(docx))));
        assertThat(CONVERTER.getAllServeEvents()).isEmpty();
        assertThat(previewsRecorded(docx)).isEqualTo(recorded + 1);
    }

    @Test
    @Order(4)
    void aJudgePreviewsThroughAVisibleReferralAndAClaimHidesItFromAnotherJudge() throws Exception {
        HttpResponse<String> raised = sendJson("/caseworker/cases/" + recordId + "/referrals", "POST",
            "{\"reasons\":[\"Minor\"],\"notes\":\"Synthetic referral\"}", CftlibConfig.DOCUMENT_READER);
        assertThat(raised.statusCode()).as(raised.body()).isEqualTo(201);
        String referralId = json.readTree(raised.body()).path("id").asText();
        Original tiff = originals.get("three-frames.tiff");
        Original docx = originals.get("letter.docx");

        for (String judge : List.of(CftlibConfig.JUDICIAL_USER, CftlibConfig.OTHER_JUDICIAL_USER)) {
            DM.server().resetRequests();
            assertPreview(preview(judicialPreview(referralId, tiff), judge), tiff.pages());
            assertReadAsCaller(tiff, judge);
        }
        assertThat(preview(judicialPreview(referralId, tiff), CftlibConfig.DOCUMENT_READER).statusCode())
            .isEqualTo(403);

        HttpResponse<String> claimed = sendJson("/judicial/referrals/" + referralId + "/claim", "POST", "",
            CftlibConfig.JUDICIAL_USER);
        assertThat(claimed.statusCode()).as(claimed.body()).isEqualTo(200);
        CONVERTER.resetRequests();
        DM.server().resetRequests();
        assertThat(preview(judicialPreview(referralId, docx), CftlibConfig.OTHER_JUDICIAL_USER).statusCode())
            .isEqualTo(404);
        assertThat(DM.server().getAllServeEvents()).isEmpty();
        assertThat(CONVERTER.getAllServeEvents()).isEmpty();
        assertPreview(preview(judicialPreview(referralId, docx), CftlibConfig.JUDICIAL_USER), docx.pages());
        assertReadAsCaller(docx, CftlibConfig.JUDICIAL_USER);
        CONVERTER.verify(1, postRequestedFor(urlPathEqualTo(CONVERT)));

        HttpResponse<String> withdrawn = sendJson("/caseworker/cases/" + recordId + "/referrals/" + referralId
            + "/withdrawal", "POST", "", CftlibConfig.DOCUMENT_READER);
        assertThat(withdrawn.statusCode()).as(withdrawn.body()).isEqualTo(200);
        DM.server().resetRequests();
        assertThat(preview(judicialPreview(referralId, tiff), CftlibConfig.JUDICIAL_USER).statusCode())
            .isEqualTo(404);
        assertThat(DM.server().getAllServeEvents()).isEmpty();
    }

    @Test
    @Order(5)
    void cdamRefusesAPreviewWithoutCcdReadAndNothingReachesTheConverter() throws Exception {
        Path definition = Path.of("build/cftlib/definitions/DEED_POLL_PLACEHOLDER");
        Path grants = definition.resolve("AuthorisationCaseType.json");
        String original = Files.readString(grants);
        JsonNode withoutRead = json.readTree(original);
        for (JsonNode grant : withoutRead) {
            if (grant.path("UserRole").asText().equals("DEED_POLL_READER")) {
                ((ObjectNode) grant).put("CRUD", "U");
            }
        }
        try {
            Files.writeString(grants, withoutRead.toString());
            ControlPlane.getApi().importJsonDefinition(definition.toFile());
            Thread.sleep(1200);
            CONVERTER.resetRequests();
            for (Original document : List.of(originals.get("scan.png"), originals.get("letter.docx"))) {
                DM.server().resetRequests();
                int recorded = previewsRecorded(document);
                HttpResponse<byte[]> denied = preview(caseworkerPreview(recordId, document),
                    CftlibConfig.DOCUMENT_READER);
                assertThat(denied.statusCode()).isIn(403, 404);
                assertThat(new String(denied.body(), StandardCharsets.UTF_8)).doesNotContain("PREVIEW_");
                DM.server().verify(0, getRequestedFor(urlPathEqualTo(binaryPath(document))));
                // As a download records DOCUMENT_DOWNLOADED before CDAM refuses it: the attempt is the audited read.
                assertThat(previewsRecorded(document)).isEqualTo(recorded + 1);
            }
            assertThat(CONVERTER.getAllServeEvents()).isEmpty();
        } finally {
            Files.writeString(grants, original);
            ControlPlane.getApi().importJsonDefinition(definition.toFile());
            Thread.sleep(1200);
        }
        Original png = originals.get("scan.png");
        assertPreview(preview(caseworkerPreview(recordId, png), CftlibConfig.DOCUMENT_READER), png.pages());
    }

    @Test
    @Order(6)
    void aConverterOutageIsTheFixed503OnceAndTheOriginalStillDownloads() throws Exception {
        stubConverter(aResponse().withStatus(503).withBody("Tornado is down at " + CONVERTER.baseUrl()));
        Original docx = originals.get("letter.docx");
        HttpResponse<byte[]> unavailable = preview(caseworkerPreview(recordId, docx), CftlibConfig.DOCUMENT_READER);
        assertThat(unavailable.statusCode()).isEqualTo(503);
        assertThat(new String(unavailable.body(), StandardCharsets.UTF_8)).contains("PREVIEW_UNAVAILABLE")
            .doesNotContain("Tornado", "localhost", ACCESS_KEY);
        CONVERTER.verify(1, postRequestedFor(urlPathEqualTo(CONVERT)));
        assertOriginalUnchanged(docx);
    }

    @Test
    @Order(7)
    void systemCorrespondenceStillReadsAsTheSystemUserAfterPreviews() throws Exception {
        Original pdf = originals.get("two-pages.pdf");
        DM.server().resetRequests();
        try (var content = correspondence.downloadDocument(recordId, pdf.id()).stream()) {
            assertThat(content.readAllBytes()).isEqualTo(pdf.bytes());
        }
        DM.server().verify(1, getRequestedFor(urlPathEqualTo(binaryPath(pdf)))
            .withHeader("user-id", equalTo(idam.getUserInfo(systemUser.accessToken()).getUid())));
        assertThat(DM.server().findAllUnmatchedRequests()).isEmpty();
    }

    private void assertPreview(HttpResponse<byte[]> preview, int pages) throws IOException {
        assertThat(preview.statusCode()).as(new String(preview.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        assertThat(preview.headers().firstValue("Content-Type")).hasValue("application/pdf");
        assertThat(preview.headers().firstValue("Content-Disposition"))
            .hasValue("inline; filename=\"document-preview.pdf\"");
        assertThat(preview.headers().firstValue("Cache-Control")).hasValueSatisfying(
            value -> assertThat(value).contains("no-store"));
        assertThat(preview.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(preview.headers().firstValue("Content-Security-Policy")).hasValue("default-src 'none'; sandbox");
        try (PDDocument document = Loader.loadPDF(preview.body())) {
            assertThat(document.getNumberOfPages()).isEqualTo(pages);
        }
    }

    /** dm-store's {@code user-id} is the IDAM id CDAM read for: the caller's own, never the system user's. */
    private void assertReadAsCaller(Original original, String user) {
        String caller = idam.getUserInfo(idam.getAccessToken(user, "password")).getUid();
        assertThat(caller).isNotEqualTo(idam.getUserInfo(systemUser.accessToken()).getUid());
        DM.server().verify(1, getRequestedFor(urlPathEqualTo(binaryPath(original)))
            .withHeader("user-id", equalTo(caller)));
    }

    private void assertOriginalUnchanged(Original original) throws Exception {
        HttpResponse<byte[]> download = http.send(request("/caseworker/cases/" + recordId + "/documents/"
            + original.id() + "/file", CftlibConfig.DOCUMENT_READER).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.headers().firstValue("Content-Type")).hasValueSatisfying(
            value -> assertThat(value).startsWith(original.mimeType()));
        assertThat(sha256(download.body())).isEqualTo(sha256(original.bytes()));
    }

    private void assertTiffKeepsEveryFrameInOrder(byte[] preview) throws IOException {
        try (PDDocument document = Loader.loadPDF(preview)) {
            PDFRenderer renderer = new PDFRenderer(document);
            for (int page = 0; page < FRAMES.length; page++) {
                BufferedImage rendered = renderer.renderImageWithDPI(page, 18);
                Color centre = new Color(rendered.getRGB(rendered.getWidth() / 2, rendered.getHeight() / 2));
                assertThat(distance(centre, FRAMES[page])).as("page %d", page + 1).isLessThan(40);
            }
        }
    }

    private int previewsRecorded(Original original) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM deedpoll_audit_event WHERE action = 'DOCUMENT_PREVIEWED' AND entity_id = ?
            """, Integer.class, original.id());
    }

    private void stubConverter(com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response) {
        CONVERTER.resetAll();
        CONVERTER.stubFor(post(urlPathEqualTo(CONVERT)).willReturn(response));
    }

    private static MultipartValuePattern part(String name, byte[] body) {
        return aMultipart().withName(name).withBody(binaryEqualTo(body)).build();
    }

    private String createRecord() throws Exception {
        HttpResponse<String> created = sendJson("/caseworker/cases", "POST",
            "{\"dateReceived\":\"2026-07-01T00:00:00.000Z\"}", CftlibConfig.DOCUMENT_READER);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return json.readTree(created.body()).path("id").asText();
    }

    private String documentType() {
        DeedPollSupportingDocumentType type = new DeedPollSupportingDocumentType();
        type.setName("cftlib preview document");
        return types.save(type).getId();
    }

    private void upload(String typeId, String filename, String mimeType, byte[] bytes, int pages) throws Exception {
        String boundary = "deed-poll-preview-cftlib";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"documentTypeId\"\r\n\r\n"
            + typeId + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
            + filename + "\"\r\nContent-Type: " + mimeType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(bytes);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> uploaded = http.send(request("/caseworker/cases/" + recordId + "/documents",
            CftlibConfig.DOCUMENT_READER).header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(uploaded.statusCode()).as(filename + ": " + uploaded.body()).isEqualTo(201);
        String id = json.readTree(uploaded.body()).path("id").asText();
        originals.put(filename, new Original(id, filename, mimeType, bytes, pages));
    }

    private String binaryPath(Original original) {
        String remote = jdbc.queryForObject(
            "SELECT \"cdamDocumentId\"::text FROM deedpoll_supportingdocument WHERE id = ?",
            String.class, original.id());
        return "/documents/" + remote + "/binary";
    }

    private static String caseworkerPreview(String caseId, Original original) {
        return "/caseworker/cases/" + caseId + "/documents/" + original.id() + "/preview";
    }

    private static String judicialPreview(String referralId, Original original) {
        return "/judicial/referrals/" + referralId + "/documents/" + original.id() + "/preview";
    }

    private HttpResponse<byte[]> preview(String path, String user) throws Exception {
        return http.send(request(path, user).header("Accept", "application/pdf").GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> send(String path, String user) throws Exception {
        return http.send(request(path, user).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendJson(String path, String method, String body, String user) throws Exception {
        return http.send(request(path, user).header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path, String user) {
        return HttpRequest.newBuilder(URI.create("http://localhost:4550" + path))
            .header("Authorization", idam.getAccessToken(user, "password"));
    }

    private static byte[] pdf(int pages) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int page = 0; page < pages; page++) {
                document.addPage(new PDPage());
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException failed) {
            throw new IllegalStateException(failed);
        }
    }

    private static BufferedImage frame(Color colour, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(colour);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return image;
    }

    private static byte[] image(String format, Color colour) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(frame(colour, 160, 120), format, out)).isTrue();
        return out.toByteArray();
    }

    /** Three frames of distinct colour, the second one portrait, so a first-frame-only preview cannot pass. */
    private static byte[] tiff() throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("tiff").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.prepareWriteSequence(null);
            for (int index = 0; index < FRAMES.length; index++) {
                BufferedImage frame = index == 1 ? frame(FRAMES[index], 90, 140) : frame(FRAMES[index], 140, 90);
                writer.writeToSequence(new IIOImage(frame, null, null), writer.getDefaultWriteParam());
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static byte[] docx() throws IOException {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int page = 1; page <= 3; page++) {
                var run = document.createParagraph().createRun();
                run.setText("Synthetic preview page " + page);
                if (page < 3) {
                    run.addBreak(BreakType.PAGE);
                }
            }
            document.write(out);
            return out.toByteArray();
        }
    }

    private static int distance(Color one, Color other) {
        return Math.abs(one.getRed() - other.getRed()) + Math.abs(one.getGreen() - other.getGreen())
            + Math.abs(one.getBlue() - other.getBlue());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
