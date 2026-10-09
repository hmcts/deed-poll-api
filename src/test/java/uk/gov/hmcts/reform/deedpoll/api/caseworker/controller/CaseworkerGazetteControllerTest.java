package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.GazetteNoticeBatch;
import uk.gov.hmcts.reform.deedpoll.entity.GazetteNoticeBatchCase;
import uk.gov.hmcts.reform.deedpoll.entity.GazetteNoticeBatchCaseId;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.GazetteNoticeBatchCaseRepository;
import uk.gov.hmcts.reform.deedpoll.repository.GazetteNoticeBatchRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteNoticeHistory.MAX_REFERENCES;

/** Real PostgreSQL and the whole security context, with only IDAM stubbed. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CaseworkerGazetteControllerTest extends AbstractPostgresContainerTest {

    private static final String HISTORY = "/caseworker/gazette-notices";
    private static final LocalDateTime SENT_AT = LocalDateTime.of(2026, 10, 6, 10, 30);
    private static final String SENDER_SUBJECT = "private-sender-subject";
    private static final StubIdam IDAM = new StubIdam();

    static {
        IDAM.start();
    }

    @AfterAll
    static void stopIdam() {
        IDAM.stop();
    }

    @DynamicPropertySource
    static void idamProperties(DynamicPropertyRegistry registry) {
        registry.add("idam.api.issuer-url", IDAM::issuerUrl);
        registry.add("idam.api.audiences", () -> StubIdam.AUDIENCE);
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private GazetteNoticeBatchRepository batches;
    @Autowired
    private GazetteNoticeBatchCaseRepository members;
    @Autowired
    private DeedPollRepository deedPolls;
    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        IDAM.stubDefaults();
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Current Delivery Manager", List.of(Role.DELIVERY_MANAGER.claimValue()));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get(HISTORY)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"caseworker-civil", "caseworker-deedpoll", "caseworker-deedpoll-judicial"})
    void refusesAuthenticatedNonDeliveryManagers(String role) throws Exception {
        batch(SENT_AT, "Historical Sender", "2026/PRIVATE");
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Historical Sender", List.of(role));
        authorised(get(HISTORY))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value(containsString("ACCESS_GAZETTE")));
    }

    @Test
    void returnsEmptyHistoryWithDefaultPaging() throws Exception {
        authorised(get(HISTORY)).andExpect(status().isOk())
            .andExpect(jsonPath("$.batches").isEmpty())
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.size").value(25))
            .andExpect(jsonPath("$.total").value(0))
            .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void returnsFrozenReferencesAndAttributionWithoutPrivateIdentities() throws Exception {
        GazetteNoticeBatch batch = batch(SENT_AT, "Historical Sender", "2026/B", "2026/A");
        String body = authorised(get(HISTORY)).andExpect(status().isOk())
            .andExpect(jsonPath("$.batches[0].sentAt").value("2026-10-06T10:30:00"))
            .andExpect(jsonPath("$.batches[0].sentBy").value("Historical Sender"))
            .andExpect(jsonPath("$.batches[0].caseCount").value(2))
            .andExpect(jsonPath("$.batches[0].referencesTruncated").value(false))
            .andExpect(jsonPath("$.batches[0].referenceNumbers[0]").value("2026/A"))
            .andExpect(jsonPath("$.batches[0].referenceNumbers[1]").value("2026/B"))
            .andReturn().getResponse().getContentAsString();
        JsonNode response = json.readTree(body);
        assertThat(response.fieldNames()).toIterable()
            .containsExactlyInAnyOrder("batches", "page", "size", "total", "totalPages");
        assertThat(response.at("/batches/0").fieldNames()).toIterable()
            .containsExactlyInAnyOrder(
                "batchId", "sentAt", "sentBy", "caseCount", "referenceNumbers", "referencesTruncated");
        assertThat(body).doesNotContain(SENDER_SUBJECT, StubIdam.SUBJECT, batch.getRequestId().toString(),
            "sentById", "requestId", "2026/AMENDED", "Current Delivery Manager");
    }

    @ParameterizedTest
    @ValueSource(ints = {MAX_REFERENCES, MAX_REFERENCES + 1, MAX_REFERENCES + 50})
    void boundsReferencesPerBatchAndReportsOmittedMembers(int count) throws Exception {
        String[] references = java.util.stream.IntStream.range(0, count)
            .mapToObj(i -> String.format("2026/%03d", i)).toArray(String[]::new);
        GazetteNoticeBatch large = batch(SENT_AT, "Large", references);
        GazetteNoticeBatch small = batch(SENT_AT.minusDays(1), "Small", "2026/2", "2026/10");
        assertThat(members.referencePreview(List.of(large.getId(), small.getId()), MAX_REFERENCES + 1))
            .hasSize(Math.min(count, MAX_REFERENCES + 1) + 2);
        authorised(get(HISTORY)).andExpect(status().isOk())
            .andExpect(jsonPath("$.batches[0].caseCount").value(count))
            .andExpect(jsonPath("$.batches[0].referenceNumbers.length()").value(MAX_REFERENCES))
            .andExpect(jsonPath("$.batches[0].referenceNumbers[" + (MAX_REFERENCES - 1) + "]")
                .value(references[MAX_REFERENCES - 1]))
            .andExpect(jsonPath("$.batches[0].referencesTruncated").value(count > MAX_REFERENCES))
            .andExpect(jsonPath("$.batches[1].referenceNumbers[0]").value("2026/10"))
            .andExpect(jsonPath("$.batches[1].referenceNumbers[1]").value("2026/2"))
            .andExpect(jsonPath("$.batches[1].referencesTruncated").value(false));
    }

    @Test
    void pagesNewestFirstAndKeepsReferencesScopedToEachBatch() throws Exception {
        batch(SENT_AT.minusDays(1), "Oldest", "2026/OLD");
        batch(SENT_AT.plusDays(1), "Newest", "2026/NEW");
        batch(SENT_AT, "Middle", "2026/MID");
        List<String> names = List.of("Newest", "Middle", "Oldest");
        List<String> references = List.of("2026/NEW", "2026/MID", "2026/OLD");
        for (int page = 1; page <= 3; page++) {
            authorised(get(HISTORY).param("page", Integer.toString(page)).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(page))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.batches.length()").value(1))
                .andExpect(jsonPath("$.batches[0].sentBy").value(names.get(page - 1)))
                .andExpect(jsonPath("$.batches[0].referenceNumbers[0]").value(references.get(page - 1)));
        }
        authorised(get(HISTORY).param("page", "4").param("size", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.batches").isEmpty())
            .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    void equalTimestampsHaveStablePaging() throws Exception {
        GazetteNoticeBatch first = batch(SENT_AT, "One", "2026/ONE");
        GazetteNoticeBatch second = batch(SENT_AT, "Two", "2026/TWO");
        boolean firstHigher = first.getId().toString().compareTo(second.getId().toString()) > 0;
        authorised(get(HISTORY).param("size", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.batches[0].sentBy").value(firstHigher ? "One" : "Two"));
        authorised(get(HISTORY).param("size", "1").param("page", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.batches[0].sentBy").value(firstHigher ? "Two" : "One"));
    }

    @Test
    void capsPageSize() throws Exception {
        authorised(get(HISTORY).param("size", "1000"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(100));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "lots"})
    void refusesInvalidPaging(String value) throws Exception {
        authorised(get(HISTORY).param("page", value))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("page"));
        authorised(get(HISTORY).param("size", value))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    private ResultActions authorised(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + IDAM.accessToken()));
    }

    private GazetteNoticeBatch batch(LocalDateTime sentAt, String sender, String... references) {
        GazetteNoticeBatch batch = new GazetteNoticeBatch();
        batch.setSentAt(sentAt);
        batch.setSentById(SENDER_SUBJECT);
        batch.setSentByName(sender);
        batch.setRequestId(UUID.randomUUID());
        batch.setCaseCount(references.length);
        batch = batches.saveAndFlush(batch);
        for (String reference : references) {
            DeedPoll deedPoll = new DeedPoll();
            deedPoll.setDateReceived(SENT_AT.minusDays(30));
            deedPoll.setDisplayReference(reference);
            deedPoll = deedPolls.saveAndFlush(deedPoll);
            GazetteNoticeBatchCase member = new GazetteNoticeBatchCase();
            member.setId(new GazetteNoticeBatchCaseId(batch.getId(), deedPoll.getId()));
            member.setReferenceNumber(reference);
            members.saveAndFlush(member);
            deedPoll.setDisplayReference("2026/AMENDED-" + deedPoll.getId());
            deedPolls.saveAndFlush(deedPoll);
        }
        return batch;
    }
}
