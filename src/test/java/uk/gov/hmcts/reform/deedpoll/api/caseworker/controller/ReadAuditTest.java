package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditTrail;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.CreateDocumentTypeInput;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentStore;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentTypeService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadedFile;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentTypeRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A case view records {@code CASE_VIEWED} and a file download {@code DOCUMENT_DOWNLOADED}, each in its own
 * transaction (decision 9), over HTTP against a real database.
 *
 * <p>The forced failure is a real one, as in {@code AuditFailureRollbackTest}: a subject longer than
 * {@code actor_id}'s 255 characters, which only the audit table refuses. A case view fails open and still answers the
 * case; a download fails closed, and the store is never opened.
 *
 * <p>Not {@code @Transactional}, because the read row commits on its own. The fixtures are removed afterwards; the
 * audit rows stay, as V36 requires, under case ids no other test uses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReadAuditTest extends AbstractPostgresContainerTest {

    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";
    private static final byte[] PDF_BYTES = "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8);
    private static final String UNRECORDABLE = "s".repeat(256);

    private static final Path STORE_ROOT = temporaryStoreRoot();
    private static final StubIdam IDAM = new StubIdam();

    static {
        IDAM.start();
    }

    @AfterAll
    static void releaseTheStubAndTheStore() throws IOException {
        IDAM.stop();
        try (Stream<Path> entries = Files.walk(STORE_ROOT)) {
            for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("idam.api.issuer-url", IDAM::issuerUrl);
        registry.add("idam.api.audiences", () -> StubIdam.AUDIENCE);
        registry.add("deedpoll.documents.local-store-root", STORE_ROOT::toString);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DeedPollService deedPollService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentTypeService documentTypes;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private DeedPollSupportingDocumentRepository documents;

    @Autowired
    private DeedPollSupportingDocumentTypeRepository types;

    @Autowired
    private ReferenceCounterRepository referenceCounters;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private DocumentStore store;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    private String caseId;

    private static Path temporaryStoreRoot() {
        try {
            return Files.createTempDirectory("deed-poll-api-read-audit");
        } catch (IOException failed) {
            throw new UncheckedIOException("Could not create a temporary document store", failed);
        }
    }

    @BeforeEach
    void setUp() {
        IDAM.stubDefaults();
        logs.start();
        ((Logger) LoggerFactory.getLogger(AuditTrail.class)).addAppender(logs);
        caseId = deedPollService.createDeedPoll(DeedPollPatch.of(Map.of("dateReceived", RECEIVED)))
            .deedPoll().getId();
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(AuditTrail.class)).detachAppender(logs);
        documents.deleteAll();
        types.deleteAll();
        deedPolls.deleteAll();
        referenceCounters.deleteAll();
    }

    @Test
    @DisplayName("a case view records one CASE_VIEWED row as the reader, with no fields or values")
    void caseViewIsRecorded() throws Exception {
        as(StubIdam.SUBJECT, get("/caseworker/cases/" + caseId)).andExpect(status().isOk());

        assertThat(reads("CASE_VIEWED")).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("actor_id", StubIdam.SUBJECT)
                .containsEntry("actor_name", "A Caseworker")
                .containsEntry("entity_type", "DeedPoll")
                .containsEntry("entity_id", caseId);
            assertThat(valueRows()).isZero();
        });
    }

    @Test
    @DisplayName("a case that is not found records nothing")
    void unknownCaseIsNotRecorded() throws Exception {
        as(StubIdam.SUBJECT, get("/caseworker/cases/11111111-1111-1111-1111-111111111111"))
            .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM deedpoll_audit_event WHERE entity_id = '11111111-1111-1111-1111-111111111111'",
            Integer.class)).isZero();
    }

    @Test
    @DisplayName("a case view whose audit fails still answers the case, and logs audit.write.failed")
    void caseViewFailsOpen() throws Exception {
        IDAM.stubUserInfo(UNRECORDABLE, "A Caseworker", List.of("caseworker-civil"));

        as(UNRECORDABLE, get("/caseworker/cases/" + caseId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(caseId));

        assertThat(reads("CASE_VIEWED")).isEmpty();
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
            .anyMatch(line -> line.startsWith("audit.write.failed action=CASE_VIEWED entityId=" + caseId));
    }

    @Test
    @DisplayName("a download records one DOCUMENT_DOWNLOADED row on the document, in the case")
    void downloadIsRecorded() throws Exception {
        String documentId = uploaded();

        as(StubIdam.SUBJECT, get(fileUrl(documentId)))
            .andExpect(status().isOk())
            .andExpect(content().bytes(PDF_BYTES));

        assertThat(reads("DOCUMENT_DOWNLOADED")).singleElement().satisfies(row ->
            assertThat(row).containsEntry("actor_id", StubIdam.SUBJECT)
                .containsEntry("entity_type", "Document")
                .containsEntry("entity_id", documentId));
    }

    @Test
    @DisplayName("a download whose audit fails is refused, and the file is never opened")
    void downloadFailsClosed() {
        String documentId = uploaded();
        IDAM.stubUserInfo(UNRECORDABLE, "A Caseworker", List.of("caseworker-civil"));

        // Nothing maps a database failure, so MockMvc surfaces it rather than a rendered 500.
        assertThatThrownBy(() -> as(UNRECORDABLE, get(fileUrl(documentId))))
            .hasCauseInstanceOf(DataAccessException.class);

        verify(store, never()).open(any());
        assertThat(reads("DOCUMENT_DOWNLOADED")).isEmpty();
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
            .anyMatch(line -> line.startsWith(
                "audit.write.failed action=DOCUMENT_DOWNLOADED entityId=" + documentId));
    }

    private String uploaded() {
        String typeId = documentTypes.createDocumentType(CreateDocumentTypeInput.named("Birth certificate")).getId();
        return documentService
            .uploadDocument(caseId, typeId, UploadedFile.of(PDF_BYTES, "birth.pdf", "application/pdf"),
                Optional.empty(), Optional.empty())
            .getId();
    }

    private String fileUrl(String documentId) {
        return "/caseworker/cases/" + caseId + "/documents/" + documentId + "/file";
    }

    private List<Map<String, Object>> reads(String action) {
        return jdbc.queryForList(
            "SELECT actor_id, actor_name, entity_type, entity_id, fields FROM deedpoll_audit_event "
                + "WHERE case_id = ? AND action = ?", caseId, action);
    }

    private int valueRows() {
        return jdbc.queryForObject(
            "SELECT count(*) FROM deedpoll_audit_event_value v JOIN deedpoll_audit_event e ON e.id = v.event_id "
                + "WHERE e.case_id = ? AND e.action = 'CASE_VIEWED'", Integer.class, caseId);
    }

    private ResultActions as(String subject, MockHttpServletRequestBuilder request) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken(claims -> claims.subject(subject)));
        return mockMvc.perform(request.headers(headers));
    }
}
