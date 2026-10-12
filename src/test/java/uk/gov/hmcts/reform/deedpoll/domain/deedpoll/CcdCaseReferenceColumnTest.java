package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V21's {@code ccdCaseReference} against real Postgres: bound once and never overwritten, and invisible to the
 * case list (plan 02, decision 12).
 *
 * <p>Not {@code @Transactional}, so that a bind is a committed statement and a stale entity's save is a real
 * second transaction, which is the shape of the overwrite the read-only mapping exists to prevent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CcdCaseReferenceColumnTest extends AbstractPostgresContainerTest {
    private static final CurrentUser LIST_READER =
        CurrentUser.currentUser(List.of(Role.DELIVERY_MANAGER), "Manager", "subject");

    private static final String REFERENCE = "1234567890123456";

    private static final String OTHER_REFERENCE = "6543210987654321";

    @Autowired
    private DeedPollService deedPollService;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private ReferenceCounterRepository referenceCounters;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    @AfterEach
    void clearTables() {
        deedPolls.deleteAll();
        referenceCounters.deleteAll();
    }

    private static DeedPollPatch patch(Object... keysAndValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return DeedPollPatch.of(values);
    }

    private String createdCase(Object... keysAndValues) {
        return deedPollService.createDeedPoll(patch(keysAndValues)).deedPoll().getId();
    }

    private String storedReference(String id) {
        return jdbc.queryForObject("SELECT \"ccdCaseReference\" FROM \"deedpoll_deedpoll\" WHERE \"id\" = ?",
                                   String.class, id);
    }

    private int bind(String id, String reference) {
        return new TransactionTemplate(transactionManager)
            .execute(status -> deedPolls.bindCcdCaseReference(id, reference));
    }

    @Test
    @DisplayName("a new case has no CCD reference")
    void newCaseHasNone() {
        String id = createdCase("type", DeedPollType.Adult, "dateReceived", "2026-07-01T00:00:00.000Z");

        assertThat(storedReference(id)).isNull();
    }

    @Test
    @DisplayName("the reference binds once, and a second bind -- different or the same -- changes nothing")
    void theReferenceBindsOnce() {
        String id = createdCase("type", DeedPollType.Adult, "dateReceived", "2026-07-01T00:00:00.000Z");

        assertThat(bind(id, REFERENCE)).isEqualTo(1);
        assertThat(bind(id, OTHER_REFERENCE)).isZero();
        assertThat(bind(id, REFERENCE)).isZero();

        assertThat(storedReference(id)).isEqualTo(REFERENCE);
        assertThat(deedPolls.findById(id).orElseThrow().getCcdCaseReference()).isEqualTo(REFERENCE);
    }

    @Test
    @DisplayName("a patch through the service after the bind leaves the reference alone")
    void patchLeavesTheReferenceAlone() {
        String id = createdCase("type", DeedPollType.Adult, "dateReceived", "2026-07-01T00:00:00.000Z");
        bind(id, REFERENCE);

        deedPollService.updateDeedPoll(id, patch("newSurname", "Smith"));

        assertThat(storedReference(id)).isEqualTo(REFERENCE);
    }

    @Test
    @DisplayName("saving an entity read before the bind does not write its stale null over the reference")
    void staleSaveDoesNotClearTheReference() {
        String id = createdCase("type", DeedPollType.Adult, "dateReceived", "2026-07-01T00:00:00.000Z");
        DeedPoll readBeforeTheBind = deedPolls.findById(id).orElseThrow();
        assertThat(readBeforeTheBind.getCcdCaseReference()).isNull();

        bind(id, REFERENCE);
        readBeforeTheBind.setNewSurname("Jones");
        readBeforeTheBind.setCcdCaseReference(OTHER_REFERENCE);
        deedPolls.save(readBeforeTheBind);

        assertThat(storedReference(id)).isEqualTo(REFERENCE);
        assertThat(deedPolls.findById(id).orElseThrow().getNewSurname()).isEqualTo("Jones");
    }

    @Test
    @DisplayName("one CCD case cannot be linked to two records")
    void oneReferenceCannotLinkTwoRecords() {
        String first = createdCase("type", DeedPollType.Adult, "dateReceived", "2026-07-01T00:00:00.000Z");
        String second = createdCase("type", DeedPollType.Adult, "dateReceived", "2026-07-01T00:00:00.000Z");
        bind(first, REFERENCE);

        assertThatThrownBy(() -> bind(second, REFERENCE))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("deedpoll_deedpoll_ccdCaseReference_key");
        assertThat(storedReference(second)).isNull();
    }

    @Test
    @DisplayName("the database refuses anything but sixteen digits")
    void theDatabaseRefusesAMalformedReference() {
        String id = createdCase("type", DeedPollType.Adult, "dateReceived", "2026-07-01T00:00:00.000Z");

        for (String malformed : List.of("123456789012345", "123456789012345a", "1234-56789012345")) {
            assertThatThrownBy(() -> bind(id, malformed))
                .as(malformed)
                .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(storedReference(id)).isNull();
    }

    @Test
    @DisplayName("every list filter answers identically with the reference null and present (decision 12)")
    void theCaseListIsUnchangedByTheReference() {
        createdCase("type", DeedPollType.Adult, "dateReceived", "2026-07-01T00:00:00.000Z");
        createdCase("type", DeedPollType.Adult, "dateReceived", "2026-07-02T00:00:00.000Z",
                    "dateEnroled", "2026-08-01T00:00:00.000Z");
        createdCase("type", DeedPollType.Minor, "dateReceived", "2026-07-03T00:00:00.000Z",
                    "dateReturned", "2026-08-02T00:00:00.000Z");
        createdCase("type", DeedPollType.Adult, "dateReceived", "2026-07-04T00:00:00.000Z");
        // The unpaged finders order by createdOn alone, and four saves in a loaded build can tie on it -- which
        // compared two arbitrary orders, not the reference's effect. Apart by a minute each, the order is fixed.
        jdbc.update("""
            UPDATE "deedpoll_deedpoll" d SET "createdOn" = now() - make_interval(mins => n.rank::int)
              FROM (SELECT "id", row_number() OVER (ORDER BY "id") AS rank FROM "deedpoll_deedpoll") n
             WHERE d."id" = n."id"
            """);

        Map<String, List<String>> before = snapshot();

        List<String> ids = deedPolls.findAll().stream().map(DeedPoll::getId).sorted().toList();
        bind(ids.get(0), REFERENCE);
        bind(ids.get(2), OTHER_REFERENCE);

        assertThat(snapshot()).isEqualTo(before);
        // Nor does searching for the reference's digits find the case it is bound to.
        assertThat(deedPollService.findDeedPolls(LIST_READER, ListFilters.LIST_FILTERS.get(0), CaseQuery.NONE,
                                                 new CaseSearch(REFERENCE), CaseSort.NEWEST_FIRST,
                                                 CasePaging.FIRST).total())
            .isZero();
    }

    /** What every filter lists, both through the named finders and through the paged list, with its total. */
    private Map<String, List<String>> snapshot() {
        Map<String, List<String>> answers = new LinkedHashMap<>();
        for (ListFilters.ListFilter filter : ListFilters.LIST_FILTERS) {
            answers.put(filter.key() + "/finder", deedPollService.findDeedPolls(LIST_READER, filter).stream()
                .map(found -> found.deedPoll().getId()).toList());

            CaseResults paged = deedPollService.findDeedPolls(LIST_READER, filter, CaseQuery.NONE, CaseSearch.NONE,
                                                              filter.defaultSort().orElse(CaseSort.NEWEST_FIRST),
                                                              CasePaging.FIRST);
            answers.put(filter.key() + "/paged", paged.cases().stream()
                .map(found -> found.deedPoll().getId()).toList());
            answers.put(filter.key() + "/total", List.of(paged.total() + "/" + paged.totalPages()));
        }
        return answers;
    }
}
