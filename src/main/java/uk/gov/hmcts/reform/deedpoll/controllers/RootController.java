package uk.gov.hmcts.reform.deedpoll.controllers;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.http.ResponseEntity.ok;

/**
 * Default endpoints per application.
 */
@RestController
public class RootController {

    /**
     * Root GET endpoint.
     *
     * <p>Azure application service has a hidden feature of making requests to root endpoint when
     * "Always On" is turned on.
     * This is the endpoint to deal with that and therefore silence the unnecessary 404s as a response code.
     *
     * <p>The one route the global bearer-token requirement in {@code OpenAPIConfiguration} does not apply to:
     * Azure's probe has no token to present, and a spec that claimed otherwise would describe a service whose
     * Always-On check could never pass.
     *
     * @return Welcome message from the service.
     */
    @SecurityRequirements
    @GetMapping("/")
    public ResponseEntity<String> welcome() {
        return ok("Welcome to deed-poll-api");
    }
}
