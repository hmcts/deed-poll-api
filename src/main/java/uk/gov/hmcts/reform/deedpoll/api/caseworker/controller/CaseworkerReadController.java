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
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingSweep;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.audit.ReadAudit;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseQuery;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseResults;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseSearch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseSort;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.ListFilters;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentTypeService;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.notify.NotificationOutbox;

import java.util.List;

/**
 * The caseworker read surface: the case list, one case, the field rules and the document types.
 *
 * <p>Four of the eleven operations plan 01 builds, and the four whose answer is a read. They are one controller
 * because
 * they are one thing to a consumer -- everything a caseworker screen needs before it can render -- and because
 * grouping the reads keeps each write resource's controller about that resource. Writes arrive with tasks 14
 * and 15.
 *
 * <p><strong>One of them writes, and it is the case list.</strong> It drains the notification outbox after it has
 * answered, which is how an owed email gets sent in a service with no scheduler -- {@link #cases} has the argument
 * and {@code NotificationOutbox} has the rest of it. When it is switched on, it then links a few records to CCD
 * cases, for the same reason. Nothing about the response depends on either, and the read itself is still read-only.
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

    /**
     * Injected so the case list can empty the notification outbox -- see {@link #cases} for why a read does
     * that, and {@link NotificationOutbox} for why there is no scheduler to do it instead.
     */
    private final NotificationOutbox outbox;

    /**
     * Injected so the case list can link records to CCD cases -- see {@link #cases} for why a read does that.
     */
    private final CcdLinkingSweep linkingSweep;

    private final ReadAudit readAudit;

    public CaseworkerReadController(DeedPollService deedPolls,
                                    DocumentTypeService documentTypes,
                                    NotificationOutbox outbox,
                                    CcdLinkingSweep linkingSweep,
                                    ReadAudit readAudit) {

        this.deedPolls = deedPolls;
        this.documentTypes = documentTypes;
        this.outbox = outbox;
        this.linkingSweep = linkingSweep;
        this.readAudit = readAudit;
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
     * <p><strong>The order a request does not name comes from the filter it asked for.</strong> Newest first for
     * the ported tabs: "awaiting confirmation" is asked for longest wait first, because a waiting list in
     * creation order buries the case at risk of being forgotten. {@code readyForPublication} selects
     * publication requested, no notice sent and state Enroled, excluding Deed of Relinquishment,
     * defaulting to {@code dateEnroled:asc}.
     * A {@code sort} the caller does name still wins,
     * for the reason {@link CaseSort#parse(String, CaseSort)} records -- a tab that forced its order would make
     * its own column headers unclickable.
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
     *
     * <p><strong>This read empties the notification outbox, and that is a decision rather than a stray call.</strong>
     * It is the same decision {@code JudicialReferralService.pool} makes about the lapsed-claim sweep, for the same
     * reason: this service has no scheduler -- no {@code @EnableScheduling}, no Quartz, no db-scheduler -- so
     * nothing notices an email that is owed and due on a timer, and rather than introduce one the drain hangs off
     * the traffic that is already there. The case list is the most frequent caseworker request, which is what makes
     * it the analogue of the judicial pool: an email left backed off by a temporary Notify failure is picked up by
     * whoever next opens the list, rather than waiting for the caseworker who sent it to come back. That is safe
     * only because the whole feature is the admin system -- every send is a caseworker action, so the queue is
     * emptied by the people who fill it. {@link NotificationOutbox} has the full argument.
     *
     * <p><strong>Drained here rather than inside {@link DeedPollService#findDeedPolls}, and after it has
     * returned.</strong> The list is a {@code readOnly = true} transaction and stays one -- a read-only list is
     * worth keeping. The drain rejects an outer transaction, commits its local steps separately, and releases
     * their locks before remote I/O. A failed drain commit can still fail this HTTP request; it cannot undo
     * an earlier committed notification.
     * {@code CaseworkerEmailController} drains after its send for the related but stronger reason recorded there:
     * inside the enqueueing transaction the drain would not see the row it was meant to send.
     *
     * <p><strong>It also links records to CCD cases, when that is switched on, and that is the same decision
     * again.</strong> Every record needs a CCD case, and with no scheduler nothing would notice one that has none,
     * so {@code CcdLinkingSweep} hangs off the most frequent caseworker request, as the drain does: it links the
     * oldest few unlinked records and reads the oldest few attempts whose outcome is outstanding. That is how a
     * record gets its case at all -- {@code POST /caseworker/cases} deliberately does not link, so that CCD's
     * latency and CCD's failures stay out of the create, and a new record is linked by the next list request,
     * which in practice is the caseworker who created it returning to the list. The same holds for a record that
     * predates linking and for an imported one. It runs after the drain and, like it, outside any transaction and
     * after the response is computed: the sweep commits each claim before calling CCD, so a transaction around it
     * would hide that claim from data-store's callback, and a sweep that fails is logged and cannot spoil the
     * list. It is bounded and stops at the first sign CCD is unwell, so an outage costs this request one failed
     * call, and the Feign timeouts in {@code application.yaml} cap what that call can cost; {@code max-duration}
     * bounds a CCD that answers every call slowly but in time.
     */
    @io.swagger.v3.oas.annotations.Operation(
        description = "The named readyForPublication worklist and count require "
            + "caseworker-deedpoll-delivery-manager, including saved-query links. Other authenticated roles "
            + "receive 403 before reads or side effects. Ordinary filters and field conditions are unchanged.")
    @GetMapping("/cases")
    public CaseListResponse cases(
        @RequestParam(name = "filter", required = false) String filter,
        @RequestParam MultiValueMap<String, String> parameters) {

        ListFilters.ListFilter resolved = ListFilters.filterFor(filter);
        CaseResults results = deedPolls.findDeedPolls(
            CurrentUsers.required(), resolved,
            CaseQuery.parse(parameters.get("where")),
            CaseSearch.parse(single("q", parameters.get("q"))),
            CaseSort.parse(single("sort", parameters.get("sort")),
                           resolved.defaultSort().orElse(CaseSort.NEWEST_FIRST)),
            CasePaging.parse(single("page", parameters.get("page")), single("size", parameters.get("size"))));
        CaseListResponse response = CaseListResponse.of(resolved, results);
        outbox.drain();
        linkingSweep.sweep();
        return response;
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
     *
     * <p>Recorded as {@code CASE_VIEWED} once the case is found, so an unknown id records nothing. The record fails
     * open: a case that could not be recorded is still answered -- see {@link ReadAudit}.
     */
    @GetMapping("/cases/{id}")
    public CaseDetailResponse caseDetail(@PathVariable("id") String id) {
        CaseDetailResponse detail = CaseDetailResponse.from(deedPolls.findDeedPoll(id));
        readAudit.caseViewed(detail.id(), CurrentUsers.required());
        return detail;
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
