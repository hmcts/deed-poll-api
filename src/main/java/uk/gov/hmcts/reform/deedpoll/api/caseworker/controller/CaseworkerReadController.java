package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import org.springframework.util.MultiValueMap;
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
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseQuery;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseResults;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseSearch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseSort;
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
     * One page of the case list: a saved-query filter, field-level conditions, an order and a page.
     *
     * <p>An unrecognised or absent {@code filter} resolves to the first filter -- "all" -- rather than being
     * refused, because the key arrives from a URL a caseworker may have bookmarked and the list is the
     * service's front page. The response says which filter was applied, so the fallback is visible; see
     * {@link CaseListResponse}.
     *
     * <p><strong>Every other parameter is refused rather than resolved when it is not understood</strong>,
     * which is the opposite of {@code filter} and deliberately so. A filter key is one of five tabs and the
     * fallback shows a caseworker something; a condition, an order or a page number that cannot be read has
     * no defensible fallback -- answering an unfiltered list to {@code where=stat:is:Received} would present
     * every case as the ones matching a query nobody can see was dropped.
     *
     * <p><strong>Each parameter is read from a list and resolved</strong>, for the reason {@link #single}
     * sets out: Spring binds a repeated parameter by silently taking the first value, and each of these
     * changes the answer. {@code where} is the exception and is genuinely repeated -- that is the wire
     * format, one condition per occurrence.
     *
     * <p><strong>Those lists come from the request's own parameter map and not from
     * {@code @RequestParam List<String>}</strong>, which cannot carry them. Spring hands a collection-typed
     * parameter the single {@code String} when a request sent one value, and converting a {@code String} to a
     * {@code List<String>} splits it on commas -- so {@code ?where=type:isOneOf:Adult,Minor} arrived as two
     * conditions, the second of them the bare word {@code Minor}, and every list-valued operator and every
     * value with a comma in it was a 400. It only bit a request that sent {@code where} exactly once, because
     * two occurrences arrive as an array and are not split, which is why it survived the wiring tests.
     * {@code MultiValueMap} is populated from {@code getParameterMap} and does no conversion, so a comma
     * inside a value stays inside the value and {@code CaseQuery} does the only splitting there is.
     *
     * <p>{@code filter} keeps binding as a single string, untouched: a repeated {@code filter} arrives as a
     * value that is not a key and falls back to "all", which is exactly what the paragraph above describes
     * and is pinned behaviour.
     *
     * <p><strong>{@code q} is a parameter on this operation and not a search endpoint of its own.</strong> A
     * search has to be bookmarkable and survive the back button, which is the same reason the filter and the
     * conditions are parameters. It ANDs with both -- {@code ?filter=open&where=type:is:Adult&q=Smith} means
     * all three -- and ORs internally across the reference and name columns; {@code CaseSpecifications} holds
     * the bracketing, which is the part of this that can go wrong invisibly.
     *
     * <p>Paginated, where the list this ports was not. A default page size means a bare request no longer
     * answers every row in the table, which is the point: an unbounded list endpoint is a denial of service
     * against its own database, and {@code CasePaging} records why the cap is not the client's to raise.
     */
    @GetMapping("/cases")
    public CaseListResponse cases(
        @RequestParam(name = "filter", required = false) String filter,
        @RequestParam MultiValueMap<String, String> parameters) {

        ListFilters.ListFilter resolved = ListFilters.filterFor(filter);
        CaseResults results = deedPolls.findDeedPolls(
            resolved,
            CaseQuery.parse(parameters.get("where")),
            CaseSearch.parse(single("q", parameters.get("q"))),
            CaseSort.parse(single("sort", parameters.get("sort"))),
            CasePaging.parse(single("page", parameters.get("page")), single("size", parameters.get("size"))));
        return CaseListResponse.of(resolved, results);
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
     *
     * <p><strong>All three parameters are taken as lists</strong>, for the reason {@link #single} sets out: each
     * one of them changes the answer, so a caller who sent one twice must be told rather than picked for.
     */
    @GetMapping("/cases/field-rules")
    public FieldRulesResponse fieldRules(
        @RequestParam(name = "type", required = false) List<String> type,
        @RequestParam(name = "migrated", required = false) List<String> migrated,
        @RequestParam(name = "publishToGazette", required = false) List<String> publishToGazette) {

        return FieldRulesResponse.from(deedPolls.getCaseFieldRules(
            caseType(type),
            flag("migrated", migrated, false),
            flag("publishToGazette", publishToGazette, true)));
    }

    /**
     * The one value a request gave for a parameter, or null for a request that gave none.
     *
     * <p><strong>Every parameter here is taken as a list of strings and resolved rather than bound straight to
     * its type.</strong> Spring converts a repeated parameter by silently taking the first value, so
     * {@code ?type=Adult&type=Minor} would answer the Adult rules -- the same "wrong answer presented as a
     * right one" the reference records about the prototype casting an unvalidated {@code type}, arrived at by a
     * different route. A caller who sent the parameter twice does not know which case they are looking at, and
     * a service that picks for them makes that unknowable rather than obvious. The two flags need the identical
     * treatment for the identical reason: {@code publishToGazette} is what decides whether
     * {@code dateNoticeSentToLondonGazette} appears at all.
     *
     * <p>The refusal is addressed to the parameter it is about, because that is where the caller's fix goes.
     */
    private static String single(String name, List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() > 1) {
            throw new InvalidInputError(ApiError.INVALID_PARAMETER,
                List.of(new FieldError(name, "must be given at most once")));
        }
        return values.get(0);
    }

    /** The one case type a request named, or null for a request that named none. */
    private static DeedPollType caseType(List<String> values) {
        String value = single("type", values);
        if (value == null) {
            return null;
        }
        try {
            return DeedPollType.valueOf(value);
        } catch (IllegalArgumentException notAMember) {
            throw new InvalidInputError(ApiError.INVALID_PARAMETER,
                List.of(new FieldError("type", "is not a valid case type")));
        }
    }

    /**
     * One boolean flag, defaulted when absent and refused when it is not a boolean.
     *
     * <p>Refused rather than read as false, which is what {@code Boolean.parseBoolean} would do with
     * {@code ?migrated=maybe}: the two flags select between different rule sets, so answering the
     * {@code false} set for an unintelligible value is the same wrong-answer-as-right-answer failure as
     * accepting an unrecognised type.
     */
    private static boolean flag(String name, List<String> values, boolean whenAbsent) {
        String value = single(name, values);
        if (value == null) {
            return whenAbsent;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new InvalidInputError(ApiError.INVALID_PARAMETER,
            List.of(new FieldError(name, "must be true or false")));
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
