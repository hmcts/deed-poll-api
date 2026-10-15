package uk.gov.hmcts.reform.deedpoll.api.caller.controller;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.deedpoll.api.caller.dto.CallerAccessResponse;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;

@RestController
public class CallerAccessController {

    /**
     * {@code no-store}, because the answer is one user's authorisation state and changes when their roles do.
     */
    @Operation(operationId = "getCallerAccess", summary = "Read what the caller may do",
        description = "Any authenticated caller may call this. staff and judicial say whether the caseworker and "
            + "judicial areas admit the caller's roles, and capabilities lists what those roles grant. "
            + "A caller with no deed-poll role gets staff false, judicial false and no capabilities, not 403: "
            + "signed in, but no access. No identity is returned.")
    @GetMapping("/me")
    public ResponseEntity<CallerAccessResponse> get() {
        // CurrentUsers.required() first: it refuses an unauthenticated request with its own explanation.
        var caller = CurrentUsers.required();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(CallerAccessResponse.of(authentication.getAuthorities(), caller));
    }
}
