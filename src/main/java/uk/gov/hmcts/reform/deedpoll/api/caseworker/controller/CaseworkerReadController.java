package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.deedpoll.api.ApiError;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CaseDetailResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CaseListResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.DocumentTypeResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.FieldRulesResponse;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.ListFilters;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentTypeService;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;

import java.util.List;

/**
 * The caseworker read surface: the case list, one case, the field rules and the document types.
 *
 * <p>Four of the eleven operations plan 01 builds, and the four that only read. They are one controller because
 * they are one thing to a consumer -- everything a caseworker screen needs before it can render -- and because
 * grouping the reads keeps each write resource's controller about that resource. Writes arrive with tasks 14
 * and 15.
 *
 * <p>Every method here resolves parameters, calls a domain service and maps the result. There is no
 * {@code if (found == null)} branch anywhere: the services report absence by throwing {@code NotFoundError},
 * which {@code DomainErrorHandler} maps to a 404, so a check here would be unreachable. That is the reference's
 * arrangement and its reasoning.
 *
 * <p>None of these routes is annotated as requiring authentication, and all four do.
 * {@code SecurityConfiguration} authenticates {@code anyRequest()} that is not on a short infrastructure
 * allowlist, so a route is protected by having been written rather than by having been remembered.
 */
@RestController
@RequestMapping("/caseworker")
public class CaseworkerReadController {

    private final DeedPollService deedPolls;
    private final DocumentTypeService documentTypes;

    public CaseworkerReadController(DeedPollService deedPolls, DocumentTypeService documentTypes) {
        this.deedPolls = deedPolls;
        this.documentTypes = documentTypes;
    }

    /**
     * The case list, filtered by one of the saved-query keys.
     *
     * <p>An unrecognised or absent {@code filter} resolves to the first filter -- "all" -- rather than being
     * refused, because the key arrives from a URL a caseworker may have bookmarked and the list is the
     * service's front page. The response says which filter was applied, so the fallback is visible; see
     * {@link CaseListResponse}.
     *
     * <p>Not paginated. The reference is not either, and the case volume this service replaces is a few
     * thousand rows against which a page parameter with no consumer would be a guess about how it should be
     * consumed. It is the one thing here worth revisiting before go-live rather than after.
     */
    @GetMapping("/cases")
    public CaseListResponse cases(@RequestParam(name = "filter", required = false) String filter) {
        ListFilters.ListFilter resolved = ListFilters.filterFor(filter);
        return CaseListResponse.of(resolved, deedPolls.findDeedPolls(resolved));
    }

    /**
     * The field show / hide / mandate rules for a case type.
     *
     * <p><strong>This has to resolve before {@link #caseDetail}</strong>, or {@code /caseworker/cases/field-rules}
     * would match {@code /caseworker/cases/&#123;id&#125;} and be read as a case id -- answering 404 for a route
     * that exists. Spring orders a literal path pattern ahead of a templated one, so it does, and a test pins
     * that rather than leaving it to be rediscovered. The reference records the same trap about its own router.
     *
     * <p>All three inputs are exposed, unlike the sibling TypeScript API which exposes only {@code type}. Its
     * one consumer of the other two is a page that calls the domain function directly; over HTTP that page has
     * to send them, and the detail page does send all three. The defaults are the domain function's own.
     *
     * <p>An unrecognised {@code type} is refused rather than cast: the prototype accepted anything and fell
     * through the matrix to the rules for no type at all, which is a wrong answer presented as a right one. An
     * absent type is a different thing and is legitimate -- it is what a case with no type yet has.
     */
    @GetMapping("/cases/field-rules")
    public FieldRulesResponse fieldRules(
        @RequestParam(name = "type", required = false) List<String> type,
        @RequestParam(name = "migrated", defaultValue = "false") boolean migrated,
        @RequestParam(name = "publishToGazette", defaultValue = "true") boolean publishToGazette) {

        return FieldRulesResponse.from(deedPolls.getCaseFieldRules(caseType(type), migrated, publishToGazette));
    }

    /**
     * The one case type a request named, or null for a request that named none.
     *
     * <p><strong>Taken as a list of strings and resolved here, rather than bound straight to the enum.</strong>
     * Spring converts a repeated parameter by silently taking the first value, so
     * {@code ?type=Adult&type=Minor} would answer the Adult rules -- the same "wrong answer presented as a
     * right one" the reference records about the prototype casting an unvalidated {@code type}, arrived at by a
     * different route. A caller who sent the parameter twice does not know which case they are looking at, and
     * a service that picks for them makes that unknowable rather than obvious.
     *
     * <p>Both refusals are addressed to {@code type}, because the caller's fix is to that parameter.
     */
    private static DeedPollType caseType(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() > 1) {
            throw new InvalidInputError(ApiError.INVALID_PARAMETER,
                List.of(new FieldError("type", "must be given at most once")));
        }
        try {
            return DeedPollType.valueOf(values.get(0));
        } catch (IllegalArgumentException notAMember) {
            throw new InvalidInputError(ApiError.INVALID_PARAMETER,
                List.of(new FieldError("type", "is not a valid case type")));
        }
    }

    /**
     * One case with its notes, witnesses and documents.
     *
     * <p>One call and one response, not four. The reference fetches the children with the case and the detail
     * page depends on having them: it renders the notes and documents subgrids and the witness block from the
     * same record it renders the tabs from.
     */
    @GetMapping("/cases/{id}")
    public CaseDetailResponse caseDetail(@PathVariable("id") String id) {
        return CaseDetailResponse.from(deedPolls.findDeedPoll(id));
    }

    /**
     * Every supporting-document type, alphabetically.
     *
     * <p>Global reference data rather than something nested under a case -- the list of things a supporting
     * document can be -- which is why it is the one read here outside {@code /cases}. It populates the upload
     * form's options, and the applicability arrays are what let that form offer only the types a given case
     * can use.
     *
     * <p>A bare array, where the case list is an object. The list has a resolved filter to report and this has
     * nothing: an envelope with one member and no second member in prospect would be ceremony.
     */
    @GetMapping("/document-types")
    public List<DocumentTypeResponse> documentTypes() {
        return DocumentTypeResponse.from(documentTypes.findDocumentTypes());
    }
}
