package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.GazetteNoticeHistoryResponse;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteNoticeHistory;

/** Publication audit history. The domain enforces the admin role on every read. */
@RestController
@RequestMapping("/caseworker/gazette-notices")
public class CaseworkerGazetteController {

    private final GazetteNoticeHistory history;

    public CaseworkerGazetteController(GazetteNoticeHistory history) {
        this.history = history;
    }

    @Operation(summary = "Read Gazette notice history",
        description = "Requires caseworker-deedpoll-admin. Batches are newest first, with frozen case references "
            + "and display-name attribution. At most 100 references per batch, in lexical display order, with "
            + "referencesTruncated indicating omitted members. This is not send order. No custom filtering or sorting.")
    @GetMapping
    public GazetteNoticeHistoryResponse history(
        @Parameter(description = "Page number, starting at 1; defaults to 1.")
        @RequestParam(name = "page", required = false) String page,
        @Parameter(description = "Rows per page; defaults to 25 and is capped at 100.")
        @RequestParam(name = "size", required = false) String size) {
        return GazetteNoticeHistoryResponse.from(
            history.history(CurrentUsers.required(), CasePaging.parse(page, size)));
    }
}
