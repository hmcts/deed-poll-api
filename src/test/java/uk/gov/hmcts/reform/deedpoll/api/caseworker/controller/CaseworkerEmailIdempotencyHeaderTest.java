package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ValueConstants;
import uk.gov.hmcts.reform.deedpoll.api.DomainErrorHandler;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.PreviewEmailRequest;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.SendEmailRequest;
import uk.gov.hmcts.reform.deedpoll.auth.IdamAuthentication;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.notify.CaseEmailService;
import uk.gov.hmcts.reform.deedpoll.notify.EmailTemplate;
import uk.gov.hmcts.reform.deedpoll.notify.NotificationOutbox;
import uk.gov.hmcts.reform.deedpoll.notify.SendReceipt;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * That the send route will not accept a request with no usable {@code Idempotency-Key}.
 *
 * <p><strong>Container-free, which is the whole reason it is a class of its own.</strong>
 * {@code CaseworkerEmailControllerTest} proves the same route over real HTTP against real Postgres, and CNP
 * excludes it because it needs Docker -- so the rule that the header is <em>required</em> would be a rule CI does
 * not check. It is worth checking there because the failure mode is silent in the wrong direction: a header
 * quietly made optional, or bound as a {@code String} and parsed later, leaves every client that has not been
 * updated sending nothing and every retry writing a second email, which is the behaviour V19 exists to replace.
 *
 * <p><strong>A standalone {@link MockMvc} rather than the whole context, and it asserts nothing about
 * security.</strong> Every other controller test here boots the context, for the reason they record: a slice does
 * not get {@code SecurityConfiguration}, so a slice asserting a route is protected is asserting Boot's defaults.
 * This class makes no such claim -- it is about argument binding and about the shape of the refusal, both of which
 * are Spring MVC's own behaviour and neither of which the security chain is between. Authentication on this route
 * is asserted in the container-backed class, which is where it belongs.
 *
 * <p>The service is mocked and never expected to be called: every request here is refused before the handler body
 * runs, which is itself part of the claim -- a malformed key must not reach a send.
 */
class CaseworkerEmailIdempotencyHeaderTest {

    private static final String CASE_ID = "case-1";

    private static final String SEND = "/caseworker/cases/" + CASE_ID + "/emails/"
        + EmailTemplate.CHASE_MISSING_DOCUMENTS.key();

    private CaseEmailService emails;

    private NotificationOutbox outbox;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        emails = mock(CaseEmailService.class);
        outbox = mock(NotificationOutbox.class);
        mockMvc = MockMvcBuilders
            .standaloneSetup(new CaseworkerEmailController(emails, outbox))
            // The same advice the running service has, because half of what is asserted here is that the refusal
            // arrives in this service's own error shape rather than as Boot's default attributes.
            .setControllerAdvice(new DomainErrorHandler())
            .build();
        // A caller on the context, because the handler reads one -- CurrentUsers.required() throws without one,
        // and a standalone setup has no filter chain to put it there. Not a security assertion: which routes are
        // authenticated is the container-backed class's claim, and this one only needs the handler to run.
        SecurityContextHolder.getContext().setAuthentication(new IdamAuthentication(
            Jwt.withTokenValue("stub").header("alg", "none").claim("sub", "caseworker-1").build(),
            CurrentUser.currentUser(List.of(), "A Caseworker", "caseworker-1")));
    }

    @AfterEach
    void clearTheCaller() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("400s a send with no Idempotency-Key, naming the header, and sends nothing")
    void refusesASendWithNoKey() throws Exception {
        mockMvc.perform(post(SEND).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            // Named, because a client that did not send it has no other way to find out which header is meant --
            // and a header name is the contract rather than caller-supplied data, so echoing it is safe.
            .andExpect(jsonPath("$.errors[0].field").value(CaseworkerEmailController.IDEMPOTENCY_KEY))
            .andExpect(jsonPath("$.errors[0].message").value("is a required header for this operation"));

        // Not defaulted to a generated one, which is the tempting fix and the wrong one: every retry would then be
        // a new send and an applicant would get one email per click.
        verifyNoInteractions(emails);
        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("400s a send whose Idempotency-Key is not a UUID, naming the header, and sends nothing")
    void refusesASendWithAMalformedKey() throws Exception {
        mockMvc.perform(post(SEND)
                            .header(CaseworkerEmailController.IDEMPOTENCY_KEY, "not-a-uuid")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0].field").value(CaseworkerEmailController.IDEMPOTENCY_KEY))
            .andExpect(jsonPath("$.errors[0].message").value("is not a valid value for this parameter"));

        verifyNoInteractions(emails);
    }

    @Test
    @DisplayName("does not echo the rejected key, which is a value somebody else's page renders")
    void doesNotEchoTheRejectedKey() throws Exception {
        String rejected = "<script>alert(1)</script>";

        String body = mockMvc.perform(post(SEND)
                                          .header(CaseworkerEmailController.IDEMPOTENCY_KEY, rejected)
                                          .contentType(MediaType.APPLICATION_JSON)
                                          .content("{}"))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(body).doesNotContain("script").doesNotContain("alert");
    }

    @Test
    @DisplayName("an empty Idempotency-Key is refused too, rather than binding as a blank")
    void refusesAnEmptyKey() throws Exception {
        // A client that sends the header with nothing in it has not identified a journey, and a blank is exactly
        // what a half-finished adapter produces -- so it must not be the one spelling that gets through.
        mockMvc.perform(post(SEND)
                            .header(CaseworkerEmailController.IDEMPOTENCY_KEY, "")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(emails);
    }

    @Test
    @DisplayName("the key the route binds is the header's own value, passed to the send as it stands")
    void passesTheKeyStraightToTheSend() throws Exception {
        // The happy path of the binding. What is asserted is that the UUID reaching the service is the one the
        // header carried: a re-generated id, or the row id, would make every retry a new send and nothing else
        // here would fail. The receipt it answers with is stubbed, because what happens after the binding is
        // CaseEmailServiceTest's.
        UUID requestId = UUID.fromString("5f2c7a90-0000-4000-8000-00000000004d");
        UUID rowId = UUID.fromString("5f2c7a90-0000-4000-8000-00000000004e");
        when(emails.send(anyString(), any(), any(), any(), any()))
            .thenReturn(SendReceipt.enqueued(rowId, "applicant@example.com"));

        mockMvc.perform(post(SEND)
                            .header(CaseworkerEmailController.IDEMPOTENCY_KEY, requestId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
            .andExpect(status().isOk())
            // The row's id and not the request's: the row is this service's identifier for the email, and echoing
            // the caller's key would invite a client to treat it as the email's identity.
            .andExpect(jsonPath("$.id").value(rowId.toString()))
            .andExpect(jsonPath("$.requestId").doesNotExist());

        verify(emails).send(anyString(), any(), any(), eq(requestId), any());
        // And the drain still runs after the send, which is the ordering CaseworkerEmailController's javadoc
        // argues for -- the header must not have moved it.
        verify(outbox).drain();
    }

    @Test
    @DisplayName("the header is declared required and typed, so the refusals above are the route's own rule")
    void theHeaderIsDeclaredRequiredAndTyped() throws Exception {
        // Reflection as well as the requests, because the requests above prove today's behaviour and this names the
        // declaration that produces it -- which is what a reader needs in order to change the route without
        // rediscovering why the type and the requiredness are load-bearing. It is not the only guard: relaxing
        // either also fails the requests above, `required = false` by letting a keyless send reach the handler and
        // a String parameter by letting "not-a-uuid" reach it, both of which break `verifyNoInteractions(emails)`.
        // What this adds is the reason, stated once, next to the four attributes it is a reason about.
        Method send = CaseworkerEmailController.class.getMethod(
            "send", String.class, String.class, UUID.class, SendEmailRequest.class);

        Parameter key = Arrays.stream(send.getParameters())
            .filter(parameter -> parameter.isAnnotationPresent(RequestHeader.class))
            .findFirst()
            .orElseThrow(() -> new AssertionError("The send route binds no header at all"));

        assertThat(key.getType())
            .as("a UUID, so Spring refuses an unparseable one before the handler runs")
            .isEqualTo(UUID.class);
        assertThat(key.getAnnotation(RequestHeader.class).value())
            .isEqualTo(CaseworkerEmailController.IDEMPOTENCY_KEY);
        assertThat(key.getAnnotation(RequestHeader.class).required())
            .as("required, so a client that has not been updated is told rather than silently losing the rule")
            .isTrue();
        assertThat(key.getAnnotation(RequestHeader.class).defaultValue())
            .as("no default, because a generated one would make every retry a new send")
            .isEqualTo(ValueConstants.DEFAULT_NONE);
    }

    @Test
    @DisplayName("the read and the preview take no such header, because neither writes anything")
    void onlyTheSendTakesTheHeader() throws Exception {
        // Scoped to the one operation that owes an applicant an email. A preview writes no row, so there is
        // nothing for a second one to duplicate, and requiring a key there would be a refusal a client could not
        // act on.
        assertThat(Arrays.stream(CaseworkerEmailController.class.getMethod("emails", String.class).getParameters()))
            .noneMatch(parameter -> parameter.isAnnotationPresent(RequestHeader.class));
        assertThat(Arrays.stream(CaseworkerEmailController.class
                                     .getMethod("preview", String.class, String.class, PreviewEmailRequest.class)
                                     .getParameters()))
            .noneMatch(parameter -> parameter.isAnnotationPresent(RequestHeader.class));
    }
}
