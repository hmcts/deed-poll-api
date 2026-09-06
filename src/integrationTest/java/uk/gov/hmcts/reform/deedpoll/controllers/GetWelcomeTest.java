package uk.gov.hmcts.reform.deedpoll.controllers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Always-On root endpoint answers without a credential.
 *
 * <p>The template shipped this as a {@code @WebMvcTest} slice. It cannot stay one now that the service
 * authenticates by default: the slice's include list covers {@code SecurityFilterChain} beans, and a
 * {@code @Configuration} class that <em>declares</em> one is not itself one, so
 * {@code SecurityConfiguration} is filtered out and the slice gets Spring Boot's default chain instead
 * -- which authenticates every path and answers 401 for this one. The slice would have gone on passing
 * had the root endpoint been protected, and failing while it is public, so it was asserting the opposite
 * of what it looked like it asserted.
 *
 * <p>Booting the whole context costs a throwaway Postgres, which is why it extends
 * {@link AbstractPostgresContainerTest}, and buys the assertion the probe actually needs: Azure's
 * Always-On check has no token to present, so an unauthenticated 200 here is the requirement.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GetWelcomeTest extends AbstractPostgresContainerTest {

    @Autowired
    private transient MockMvc mockMvc;

    @DisplayName("Should welcome upon root request with 200 response code")
    @Test
    void welcomeRootEndpoint() throws Exception {
        MvcResult response = mockMvc.perform(get("/")).andExpect(status().isOk()).andReturn();

        assertThat(response.getResponse().getContentAsString()).startsWith("Welcome");
    }
}
