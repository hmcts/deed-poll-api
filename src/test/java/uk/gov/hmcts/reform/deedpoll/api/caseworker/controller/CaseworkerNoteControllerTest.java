package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.WriteContext;
import uk.gov.hmcts.reform.deedpoll.domain.notes.CreateNoteInput;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteAuthor;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollNote;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollNoteRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The five caseworker note endpoints, over HTTP, against a real database and a stub IDAM.
 *
 * <p>The whole context rather than a {@code @WebMvcTest} slice, and not {@code @Transactional}, for the reasons
 * {@code CaseworkerReadControllerTest} and {@code CaseworkerCaseControllerTest} record: a slice asserts Spring
 * Boot's default security rather than this service's, and the writes have to really commit for an assertion to
 * read the stored row back.
 *
 * <p><strong>Two case fixtures throughout, not one.</strong> Every operation here is scoped to a parent, and a
 * test with one case cannot tell a correctly scoped lookup from an unscoped one -- both answer the same thing.
 * So the second case exists to be the wrong parent, and the scoping tests assert both halves: the note is not
 * reachable through it, and its own note did not move.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseworkerNoteControllerTest extends AbstractPostgresContainerTest {

    /** createDeedPoll cannot allocate a reference number without a Date Received to derive the year from. */
    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";

    /** An id of the right shape that nothing was ever stored under. */
    private static final String UNKNOWN_ID = "11111111-1111-1111-1111-111111111111";

    private static final StubIdam IDAM = new StubIdam();

    static {
        // Started here rather than in @BeforeAll: @DynamicPropertySource is read while the context is being
        // built, which is before any @BeforeAll callback could have started the stub.
        IDAM.start();
    }

    @AfterAll
    static void stopIdam() {
        // A static start needs an explicit stop, or the class leaves a Jetty server, its thread pool and a
        // port behind for the life of the test JVM.
        IDAM.stop();
    }

    @DynamicPropertySource
    static void idamProperties(DynamicPropertyRegistry registry) {
        registry.add("idam.api.issuer-url", IDAM::issuerUrl);
        registry.add("idam.api.audiences", () -> StubIdam.AUDIENCE);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DeedPollService deedPollService;

    @Autowired
    private NoteService noteService;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private DeedPollNoteRepository notes;

    @Autowired
    private ReferenceCounterRepository referenceCounters;

    @BeforeEach
    void setUp() {
        clearTables();
        resetIdam();
    }

    @AfterEach
    void tearDown() {
        clearTables();
    }

    private void clearTables() {
        notes.deleteAll();
        deedPolls.deleteAll();
        referenceCounters.deleteAll();
    }

    private void resetIdam() {
        IDAM.stubDefaults();
    }

    @Nested
    @DisplayName("without a token")
    class Unauthenticated {

        @Test
        @DisplayName("refuses to list a case's notes")
        void refusesAList() throws Exception {
            String caseId = newCase();

            mockMvc.perform(get("/caseworker/cases/" + caseId + "/notes"))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("refuses to add a note, and writes nothing")
        void refusesACreate() throws Exception {
            String caseId = newCase();

            mockMvc.perform(post("/caseworker/cases/" + caseId + "/notes")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"note\":\"Called the applicant\"}"))
                .andExpect(status().isUnauthorized());

            // The refusal has to happen before the handler, not inside it: a 401 that still wrote the row would
            // be the worst of both answers.
            assertThat(notes.count()).isZero();
        }

        @Test
        @DisplayName("refuses to amend a note")
        void refusesAPatch() throws Exception {
            String caseId = newCase();
            String noteId = addNote(caseId, "Called the applicant");

            mockMvc.perform(patch("/caseworker/cases/" + caseId + "/notes/" + noteId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"note\":\"Called twice\"}"))
                .andExpect(status().isUnauthorized());

            assertThat(stored(noteId).getNote()).isEqualTo("Called the applicant");
        }

        @Test
        @DisplayName("refuses to delete a note, and the note survives")
        void refusesADelete() throws Exception {
            String caseId = newCase();
            String noteId = addNote(caseId, "Called the applicant");

            mockMvc.perform(delete("/caseworker/cases/" + caseId + "/notes/" + noteId))
                .andExpect(status().isUnauthorized());

            // The same argument as the create above, and stronger: a 401 that still deleted the row would be
            // unrecoverable rather than merely wrong.
            assertThat(notes.findById(noteId)).isPresent();
        }
    }

    @Nested
    @DisplayName("GET /caseworker/cases/{id}/notes")
    class ListNotes {

        @Test
        @DisplayName("lists the case's notes, newest first")
        void listsTheCasesNotes() throws Exception {
            String caseId = newCase();
            String older = addNote(caseId, "Called the applicant");
            String newer = addNote(caseId, "Called twice");
            // Backdated rather than trusting two inserts to land in different microseconds, which is what
            // NoteServiceTest does and for the same reason.
            backdate(older, LocalDateTime.of(2026, 7, 1, 9, 0));
            backdate(newer, LocalDateTime.of(2026, 7, 2, 9, 0));

            authorised(get("/caseworker/cases/" + caseId + "/notes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(newer))
                .andExpect(jsonPath("$[0].note").value("Called twice"))
                .andExpect(jsonPath("$[1].id").value(older));
        }

        @Test
        @DisplayName("404s for a case that does not exist, rather than answering an empty list")
        void notFoundForAMissingCase() throws Exception {
            // The plan's own requirement, and the reason is that the alternative is a wrong answer rather than
            // an unhelpful one: filtering by the parent id alone gives [] both for a mistyped case id and for a
            // real case with no notes, so a caseworker who mistyped would be told the case has no notes.
            authorised(get("/caseworker/cases/" + UNKNOWN_ID + "/notes"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("DeedPoll " + UNKNOWN_ID + " not found"));
        }

        @Test
        @DisplayName("answers an empty list for a real case with no notes")
        void emptyListForACaseWithNoNotes() throws Exception {
            // The other half of the pair above. Without this the 404 could be coming from something that
            // refuses every list, and "the case exists and has no notes" would be unrepresentable.
            authorised(get("/caseworker/cases/" + newCase() + "/notes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("lists only the notes of the case in the path")
        void listsOnlyThatCasesNotes() throws Exception {
            String caseId = newCase();
            String otherCaseId = newCase();
            addNote(caseId, "Ours");
            addNote(otherCaseId, "Theirs");

            authorised(get("/caseworker/cases/" + caseId + "/notes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].note").value("Ours"));
        }

        @Test
        @DisplayName("publishes the audit pair and not the parent case")
        void publishesTheAuditPairAndNotTheParent() throws Exception {
            String caseId = newCase();
            addNote(caseId, "Called the applicant");

            authorised(get("/caseworker/cases/" + caseId + "/notes"))
                .andExpect(status().isOk())
                // Each timestamp with its author, which is what the notes grid pairs and what a note history is
                // read for.
                .andExpect(jsonPath("$[0].createdBy").value(WriteContext.SERVICE_ACCOUNT_NAME))
                .andExpect(jsonPath("$[0].modifiedBy").value(WriteContext.SERVICE_ACCOUNT_NAME))
                .andExpect(jsonPath("$[0].createdOn").exists())
                .andExpect(jsonPath("$[0].modifiedOn").exists())
                // A note carrying its case, which carries its notes, is a cycle a serialiser has to be told
                // about -- and the caller already knows which case it asked for.
                .andExpect(jsonPath("$[0].deedPoll").doesNotExist())
                .andExpect(jsonPath("$[0].deedPollId").doesNotExist());
        }
    }

    @Nested
    @DisplayName("POST /caseworker/cases/{id}/notes")
    class CreateNote {

        @Test
        @DisplayName("adds the note, attributes it to the caller and answers 201")
        void addsTheNote() throws Exception {
            String caseId = newCase();

            authorised(post("/caseworker/cases/" + caseId + "/notes"), "{\"note\":\"Called the applicant\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.note").value("Called the applicant"))
                // From the token, never from the body. Both stamped on create: a create is the first
                // modification, which is why the grid derives "unmodified" from the timestamps instead.
                .andExpect(jsonPath("$.createdBy").value("A Caseworker"))
                .andExpect(jsonPath("$.modifiedBy").value("A Caseworker"));

            assertThat(notes.findAll()).singleElement().satisfies(note -> {
                assertThat(note.getNote()).isEqualTo("Called the applicant");
                assertThat(note.getCreatedBy()).isEqualTo("A Caseworker");
                assertThat(note.getDeedPoll().getId()).isEqualTo(caseId);
            });
        }

        @Test
        @DisplayName("stores the trimmed text, so surrounding whitespace never reaches the column")
        void storesTheTrimmedText() throws Exception {
            String caseId = newCase();

            authorised(post("/caseworker/cases/" + caseId + "/notes"),
                "{\"note\":\"  Called the applicant  \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.note").value("Called the applicant"));

            assertThat(notes.findAll()).singleElement()
                .extracting(DeedPollNote::getNote)
                .isEqualTo("Called the applicant");
        }

        @Test
        @DisplayName("400s a whitespace-only note, which trimming would otherwise smuggle through")
        void refusesAWhitespaceOnlyNote() throws Exception {
            authorised(post("/caseworker/cases/" + newCase() + "/notes"), "{\"note\":\"   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("note"))
                .andExpect(jsonPath("$.errors[0].message").exists());

            assertThat(notes.count()).isZero();
        }

        @Test
        @DisplayName("400s a body with no note at all, attributed to the field a form renders")
        void refusesABodyWithNoNote() throws Exception {
            authorised(post("/caseworker/cases/" + newCase() + "/notes"), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("note"));

            assertThat(notes.count()).isZero();
        }

        @Test
        @DisplayName("400s an explicit null rather than storing one in a NOT NULL column")
        void refusesAnExplicitNull() throws Exception {
            authorised(post("/caseworker/cases/" + newCase() + "/notes"), "{\"note\":null}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("note"));

            assertThat(notes.count()).isZero();
        }

        @Test
        @DisplayName("strips a createdBy a caller sent instead of honouring it")
        void stripsACallerSuppliedAuthor() throws Exception {
            // The reference's "z.object() strips unknown keys", and here it is what stops a caller writing a
            // note in somebody else's name. Structural rather than checked: CreateNoteInput has one component.
            authorised(post("/caseworker/cases/" + newCase() + "/notes"),
                "{\"note\":\"Called\",\"createdBy\":\"Somebody Else\",\"id\":\"an-id-of-my-choosing\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdBy").value("A Caseworker"))
                .andExpect(jsonPath("$.id").exists());

            assertThat(notes.findAll()).singleElement().satisfies(note -> {
                assertThat(note.getCreatedBy()).isEqualTo("A Caseworker");
                assertThat(note.getId()).isNotEqualTo("an-id-of-my-choosing");
            });
        }

        @Test
        @DisplayName("404s for a case that does not exist, and writes nothing")
        void notFoundForAMissingCase() throws Exception {
            authorised(post("/caseworker/cases/" + UNKNOWN_ID + "/notes"), "{\"note\":\"Called\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("DeedPoll " + UNKNOWN_ID + " not found"));

            // The foreign key would refuse it anyway, but as a constraint violation naming a column rather
            // than a 404 naming the case.
            assertThat(notes.count()).isZero();
        }

        @Test
        @DisplayName("400s in the service's own error shape for a body that is not JSON")
        void refusesAnUnreadableBody() throws Exception {
            authorised(post("/caseworker/cases/" + newCase() + "/notes"), "{ not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Request body is missing or is not valid JSON"))
                .andExpect(jsonPath("$.errors").doesNotExist());
        }
    }

    @Nested
    @DisplayName("GET /caseworker/cases/{id}/notes/{noteId}")
    class ReadNote {

        @Test
        @DisplayName("returns the note")
        void returnsTheNote() throws Exception {
            String caseId = newCase();
            String noteId = addNote(caseId, "Called the applicant");

            authorised(get("/caseworker/cases/" + caseId + "/notes/" + noteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(noteId))
                .andExpect(jsonPath("$.note").value("Called the applicant"));
        }

        @Test
        @DisplayName("404s for a note read through another case, so the scope is both ids")
        void notFoundThroughAnotherCase() throws Exception {
            String caseId = newCase();
            String otherCaseId = newCase();
            String noteId = addNote(caseId, "Ours");

            authorised(get("/caseworker/cases/" + otherCaseId + "/notes/" + noteId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Note " + noteId + " not found"));
        }

        @Test
        @DisplayName("404s for a note that does not exist")
        void notFoundForAMissingNote() throws Exception {
            authorised(get("/caseworker/cases/" + newCase() + "/notes/" + UNKNOWN_ID))
                .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PATCH /caseworker/cases/{id}/notes/{noteId}")
    class UpdateNote {

        @Test
        @DisplayName("amends the text and re-stamps the modifier, leaving the author alone")
        void amendsTheText() throws Exception {
            String caseId = newCase();
            String noteId = addNote(caseId, "Called the applicant");
            IDAM.stubUserInfo(StubIdam.SUBJECT, "Another Caseworker", List.of("caseworker-civil"));

            authorised(patch("/caseworker/cases/" + caseId + "/notes/" + noteId), "{\"note\":\"Called twice\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("Called twice"))
                .andExpect(jsonPath("$.modifiedBy").value("Another Caseworker"))
                // An amendment cannot rewrite who wrote a note: UpdateNoteInput has nowhere to carry it. The
                // reference deletes the key from its patch; here there is nothing to delete.
                .andExpect(jsonPath("$.createdBy").value(WriteContext.SERVICE_ACCOUNT_NAME));

            DeedPollNote amended = stored(noteId);
            assertThat(amended.getNote()).isEqualTo("Called twice");
            assertThat(amended.getModifiedBy()).isEqualTo("Another Caseworker");
            assertThat(amended.getCreatedBy()).isEqualTo(WriteContext.SERVICE_ACCOUNT_NAME);
        }

        @Test
        @DisplayName("trims a patched note, applying the same rule as create")
        void trimsAPatchedNote() throws Exception {
            String caseId = newCase();
            String noteId = addNote(caseId, "Called the applicant");

            authorised(patch("/caseworker/cases/" + caseId + "/notes/" + noteId), "{\"note\":\"  Called twice  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("Called twice"));

            assertThat(stored(noteId).getNote()).isEqualTo("Called twice");
        }

        @Test
        @DisplayName("400s a whitespace-only patch rather than blanking the note")
        void refusesAWhitespaceOnlyPatch() throws Exception {
            String caseId = newCase();
            String noteId = addNote(caseId, "Called the applicant");

            authorised(patch("/caseworker/cases/" + caseId + "/notes/" + noteId), "{\"note\":\"  \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("note"));

            assertThat(stored(noteId).getNote()).isEqualTo("Called the applicant");
        }

        @Test
        @DisplayName("400s an explicit null instead of treating it as an empty patch")
        void refusesAnExplicitNull() throws Exception {
            // The reason UpdateNoteRequest is keyed rather than a record of one nullable component: a record
            // makes {"note": null} and {} the same body, so a client clearing a note would get a success and
            // an unchanged row. The column is NOT NULL, so there is no value for a null to mean.
            String caseId = newCase();
            String noteId = addNote(caseId, "Called the applicant");

            authorised(patch("/caseworker/cases/" + caseId + "/notes/" + noteId), "{\"note\":null}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("note"));

            assertThat(stored(noteId).getNote()).isEqualTo("Called the applicant");
        }

        @Test
        @DisplayName("accepts an empty patch, which re-stamps the modifier rather than doing nothing")
        void acceptsAnEmptyPatch() throws Exception {
            String caseId = newCase();
            String noteId = addNote(caseId, "Called the applicant");
            IDAM.stubUserInfo(StubIdam.SUBJECT, "Another Caseworker", List.of("caseworker-civil"));

            authorised(patch("/caseworker/cases/" + caseId + "/notes/" + noteId), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("Called the applicant"))
                .andExpect(jsonPath("$.modifiedBy").value("Another Caseworker"));

            assertThat(stored(noteId).getModifiedBy()).isEqualTo("Another Caseworker");
        }

        @Test
        @DisplayName("strips a createdBy from the patch instead of honouring it")
        void stripsACallerSuppliedAuthor() throws Exception {
            String caseId = newCase();
            String noteId = addNote(caseId, "Called the applicant");

            authorised(patch("/caseworker/cases/" + caseId + "/notes/" + noteId),
                "{\"note\":\"Called twice\",\"createdBy\":\"Somebody Else\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdBy").value(WriteContext.SERVICE_ACCOUNT_NAME));

            assertThat(stored(noteId).getCreatedBy()).isEqualTo(WriteContext.SERVICE_ACCOUNT_NAME);
        }

        @Test
        @DisplayName("404s amending a note through another case, and leaves it alone")
        void refusesAnAmendmentThroughAnotherCase() throws Exception {
            String caseId = newCase();
            String otherCaseId = newCase();
            String noteId = addNote(caseId, "Ours");

            authorised(patch("/caseworker/cases/" + otherCaseId + "/notes/" + noteId), "{\"note\":\"Theirs\"}")
                .andExpect(status().isNotFound());

            // Both halves: refused, and the row that was addressed did not move. A 404 that had already
            // written would be the worse of the two failures and the harder to notice.
            assertThat(stored(noteId).getNote()).isEqualTo("Ours");
        }

        @Test
        @DisplayName("404s for a note that does not exist")
        void notFoundForAMissingNote() throws Exception {
            authorised(patch("/caseworker/cases/" + newCase() + "/notes/" + UNKNOWN_ID), "{\"note\":\"Called\"}")
                .andExpect(status().isNotFound());
        }
    }

    /**
     * The one privileged operation on this surface.
     *
     * <p>The other four are open to both roles -- a Basic User may create, view and edit notes -- so this is the
     * only group that has to say who the caller is. Every test here is an Administrator unless it restubs, which
     * puts the requirement in one place: a delete test added later is authorised the way the route expects, and a
     * refusal has to ask for it in so many words.
     */
    @Nested
    @DisplayName("DELETE /caseworker/cases/{id}/notes/{noteId}")
    class DeleteNote {

        @BeforeEach
        void administrator() {
            // After the outer setUp(), which restores the non-admin default -- JUnit runs the enclosing
            // class's callbacks first, so this overrides it rather than racing it.
            IDAM.stubUserInfo(StubIdam.SUBJECT, "An Admin", List.of(Role.DEEDPOLL_ADMIN.claimValue()));
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"TEAM_LEADER", "DELIVERY_MANAGER", "DEEDPOLL_ADMIN"})
        @DisplayName("deletes the note and answers 204 with no body")
        void deletesTheNote(Role role) throws Exception {
            IDAM.stubUserInfo(StubIdam.SUBJECT, "An Admin", List.of(role.claimValue()));
            String caseId = newCase();
            String noteId = addNote(caseId, "Called the applicant");

            authorised(delete("/caseworker/cases/" + caseId + "/notes/" + noteId))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

            assertThat(notes.findById(noteId)).isEmpty();
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"TEAM_LEADER", "DELIVERY_MANAGER", "DEEDPOLL_ADMIN"})
        @DisplayName("leaves the case and its other notes alone")
        void deletesOnlyThatNote(Role role) throws Exception {
            IDAM.stubUserInfo(StubIdam.SUBJECT, "An Admin", List.of(role.claimValue()));
            String caseId = newCase();
            String keptId = addNote(caseId, "Kept");
            String deletedId = addNote(caseId, "Deleted");

            authorised(delete("/caseworker/cases/" + caseId + "/notes/" + deletedId))
                .andExpect(status().isNoContent());

            assertThat(notes.findById(deletedId)).isEmpty();
            assertThat(stored(keptId).getNote()).isEqualTo("Kept");
            // The parent survives a child delete. Worth asserting rather than assuming: the association is
            // where a stray cascade would show up, and it would show up as data loss.
            assertThat(deedPolls.findById(caseId)).isPresent();
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"TEAM_LEADER", "DELIVERY_MANAGER", "DEEDPOLL_ADMIN"})
        @DisplayName("404s deleting a note through another case, and does not delete it")
        void refusesADeleteThroughAnotherCase(Role role) throws Exception {
            IDAM.stubUserInfo(StubIdam.SUBJECT, "An Admin", List.of(role.claimValue()));
            String caseId = newCase();
            String otherCaseId = newCase();
            String noteId = addNote(caseId, "Ours");

            authorised(delete("/caseworker/cases/" + otherCaseId + "/notes/" + noteId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Note " + noteId + " not found"));

            // The scoping assertion that matters most on this verb: an unscoped delete would answer 204 and
            // destroy another case's note, and nothing downstream would report it.
            assertThat(notes.findById(noteId)).isPresent();
        }

        @Test
        @DisplayName("404s an Administrator addressing a note that does not exist")
        void notFoundForAMissingNote() throws Exception {
            // Also the proof that the role check did not absorb the not-found: the caller here holds the role,
            // so a 403 would mean the gate was refusing everyone and a 204 would mean it had swallowed the
            // absence. Compare the 403 the same request gets without the role, below.
            authorised(delete("/caseworker/cases/" + newCase() + "/notes/" + UNKNOWN_ID))
                .andExpect(status().isNotFound());
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"CASEWORKER", "JUDICIAL"})
        @DisplayName("403s a caseworker without the Administrator role, and the note survives")
        void refusesACaseworkerWithoutTheRole(Role role) throws Exception {
            String caseId = newCase();
            String noteId = addNote(caseId, "Called the applicant");
            IDAM.stubUserInfo(StubIdam.SUBJECT, "A Caseworker", List.of(role.claimValue()));

            authorised(delete("/caseworker/cases/" + caseId + "/notes/" + noteId))
                .andExpect(status().isForbidden())
                // 403 and not 404: a caseworker is entitled to know the note is there and that they may not
                // remove it. Disguising the refusal as an absence would send them to look for a note they can
                // see on the page.
                .andExpect(jsonPath("$.error")
                    .value("Requires capability: DELETE_NOTE"));

            // The half that matters: the 403 alone would also pass against a handler that refused after
            // deleting, and that is the failure nothing downstream could report or undo.
            assertThat(notes.findById(noteId)).isPresent();
        }

        @Test
        @DisplayName("403s a caller whose token names no roles at all, rather than falling open")
        void refusesACallerWithNoRoles() throws Exception {
            // The fail-closed case, and the one a bug in authentication would produce: userinfo that names no
            // roles still authenticates -- IdamAuthentication is authenticated whatever it carries -- so the
            // request reaches the handler and the gate is the only thing standing between it and the row.
            String caseId = newCase();
            String noteId = addNote(caseId, "Called the applicant");
            IDAM.stubUserInfo(StubIdam.SUBJECT, "A Caseworker", List.of());

            authorised(delete("/caseworker/cases/" + caseId + "/notes/" + noteId))
                .andExpect(status().isForbidden());

            assertThat(notes.findById(noteId)).isPresent();
        }

        @Test
        @DisplayName("403s a refused caller addressing a note that does not exist, so the check runs first")
        void refusesBeforeResolving() throws Exception {
            // Paired with notFoundForAMissingNote: the same URL is a 404 for an Administrator and a 403 for
            // everybody else. Refusing second would make the status code an existence oracle over notes the
            // caller may not touch.
            IDAM.stubUserInfo(StubIdam.SUBJECT, "A Caseworker", List.of("caseworker-civil"));

            authorised(delete("/caseworker/cases/" + newCase() + "/notes/" + UNKNOWN_ID))
                .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("still admits a caseworker's PATCH, so only the delete is gated")
        void doesNotGateTheAmend() throws Exception {
            // The business splits edit from delete, and the tidy-minded mistake would be to gate both --
            // removing a capability caseworkers are meant to have. Asserted here, next to the refusals, rather
            // than trusted to the PATCH group where nothing says the caller is not an admin.
            String caseId = newCase();
            String noteId = addNote(caseId, "Called the applicant");
            IDAM.stubUserInfo(StubIdam.SUBJECT, "A Caseworker", List.of("caseworker-civil"));

            authorised(patch("/caseworker/cases/" + caseId + "/notes/" + noteId), "{\"note\":\"Called twice\"}")
                .andExpect(status().isOk());

            assertThat(stored(noteId).getNote()).isEqualTo("Called twice");
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"TEAM_LEADER", "DELIVERY_MANAGER", "DEEDPOLL_ADMIN"})
        @DisplayName("404s deleting the same note twice, rather than answering 204 again")
        void notIdempotent(Role role) throws Exception {
            IDAM.stubUserInfo(StubIdam.SUBJECT, "An Admin", List.of(role.claimValue()));
            String caseId = newCase();
            String noteId = addNote(caseId, "Called the applicant");

            authorised(delete("/caseworker/cases/" + caseId + "/notes/" + noteId))
                .andExpect(status().isNoContent());

            // Deliberate, and consistent with the reads: the domain reports absence by throwing, so the second
            // call cannot tell "already deleted" from "never existed" and should not pretend to.
            authorised(delete("/caseworker/cases/" + caseId + "/notes/" + noteId))
                .andExpect(status().isNotFound());
        }
    }

    /** A stored case with a Date Received, which is all reference-number allocation needs. */
    private String newCase() {
        return deedPollService.createDeedPoll(DeedPollPatch.of(Map.of("dateReceived", RECEIVED)))
            .deedPoll()
            .getId();
    }

    /**
     * A note stored through the domain, attributed to nobody in particular.
     *
     * <p>Through the service rather than the repository, so a fixture cannot be a shape the API could not have
     * produced -- and so {@code createdBy} is the service account, which is what the "an amendment does not
     * rewrite the author" assertions distinguish from the caller's name.
     */
    private String addNote(String caseId, String text) {
        return noteService.createNote(caseId, new CreateNoteInput(text), NoteAuthor.NONE).getId();
    }

    private DeedPollNote stored(String noteId) {
        return notes.findById(noteId).orElseThrow();
    }

    /** Backdates a stored note, so an ordering assertion does not depend on the clock's resolution. */
    private void backdate(String noteId, LocalDateTime createdOn) {
        DeedPollNote note = stored(noteId);
        note.setCreatedOn(createdOn);
        notes.saveAll(List.of(note));
        notes.flush();
    }

    /** The request, carrying a valid forwarded access token. */
    private ResultActions authorised(MockHttpServletRequestBuilder request) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken());
        return mockMvc.perform(request.headers(headers));
    }

    /** The request, carrying a valid forwarded access token and a JSON body. */
    private ResultActions authorised(MockHttpServletRequestBuilder request, String body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken());
        return mockMvc.perform(request.headers(headers).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
