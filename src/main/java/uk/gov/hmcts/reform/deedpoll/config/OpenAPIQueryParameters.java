package uk.gov.hmcts.reform.deedpoll.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseSearch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseSort;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.ListFilters;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.QueryOperator;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Describes the five query parameters of the case list, which springdoc cannot describe by reflection.
 *
 * <p>The same problem as {@link OpenAPIWriteBodies} and for the same underlying reason, one level out: the
 * binding is a keyed map rather than declared arguments. {@code CaseworkerReadController#cases} takes a
 * {@code @RequestParam MultiValueMap<String, String>} because a {@code @RequestParam List<String>} cannot carry
 * a repeated {@code where} -- Spring converts a single {@code String} to a {@code List} by splitting it on
 * commas, so {@code ?where=type:isOneOf:Adult,Minor} arrived as two conditions and every list-valued operator
 * was a 400. The controller's Javadoc has the full argument.
 *
 * <p><strong>What springdoc publishes without this is worse than incomplete, it is wrong.</strong> It reflects
 * the map into a single query parameter literally named {@code parameters}, typed as a
 * {@code MultiValueMapStringString} component, and marks it {@code required: true} -- so the document says a
 * bare {@code GET /caseworker/cases} is a 400 when it is the service's front page, says nothing about
 * {@code where}, {@code q}, {@code sort}, {@code page} or {@code size}, and points a generated client at a
 * parameter that
 * does not exist. {@code deed-poll-admin} is the consumer that has to build a query builder against this
 * document, and a client generated from the reflected form could not call the operation at all.
 *
 * <p><strong>Generated from the domain's own tables, not restated here</strong> -- the convention
 * {@link OpenAPIWriteBodies} sets. The operator names and their arities come from {@link QueryOperator}, the
 * searched name columns from {@link CaseSearch}, the sortable fields and direction tokens from
 * {@link CaseSort}, the filter keys and labels from {@link ListFilters}, and the page defaults and cap from
 * {@link CasePaging}. An operator added to the enum therefore appears in the document without anyone editing
 * this file.
 *
 * <p>The sixth parameter, {@code filter}, is reflected correctly and described in place rather than replaced --
 * see {@link #describeFilter}, which is what publishes the tab keys a consumer builds its tab bar from.
 */
@Configuration
public class OpenAPIQueryParameters {

    /** The list operation, as it appears in the document's paths. */
    private static final String CASES_PATH = "/caseworker/cases";

    /**
     * The parameter springdoc invents for the {@code MultiValueMap} argument, and the component it types it
     * with. Both are removed: the map is the binding mechanism, not part of the surface.
     */
    private static final String REFLECTED_PARAMETER = "parameters";
    private static final String REFLECTED_SCHEMA = "MultiValueMapStringString";

    /**
     * The one parameter springdoc reflects correctly, and the one this class describes in place rather than
     * replacing: adding a second {@code filter} would publish the same parameter twice.
     */
    private static final String FILTER_PARAMETER = "filter";

    @Bean
    public OpenApiCustomizer queryParameterCustomizer() {
        return openApi -> {
            Operation list = listOperation(openApi);
            if (list == null) {
                return;
            }
            replaceReflectedParameter(list);
            dropReflectedSchema(openApi);
        };
    }

    private Operation listOperation(OpenAPI openApi) {
        return openApi.getPaths() == null || openApi.getPaths().get(CASES_PATH) == null
            ? null
            : openApi.getPaths().get(CASES_PATH).getGet();
    }

    /**
     * Drops the invented parameter, adds the five real ones, and describes the {@code filter} springdoc already
     * found -- that one binds as a declared {@code String}, so it is reflected correctly and says nothing.
     */
    private void replaceReflectedParameter(Operation list) {
        if (list.getParameters() != null) {
            list.getParameters().removeIf(parameter -> REFLECTED_PARAMETER.equals(parameter.getName()));
        }
        describeFilter(list);
        list.addParametersItem(where());
        list.addParametersItem(search());
        list.addParametersItem(sort());
        list.addParametersItem(page());
        list.addParametersItem(size());
    }

    /**
     * {@code filter}, the tab bar: the keys a caller may send, read out of {@link ListFilters}.
     *
     * <p><strong>Described rather than added</strong>, unlike the five below: springdoc reflects this one from a
     * declared {@code @RequestParam} and a second parameter of the same name would publish it twice. What
     * reflection cannot supply is the vocabulary -- the keys are a list in the domain, not an enum -- so a
     * consumer generating a client got a bare optional string and had to learn the tabs from somewhere else.
     * {@code deed-poll-admin} renders the tab bar from this, which is the whole reason it matters that
     * {@code awaitingConfirmation} is discoverable here rather than only in this repository's source.
     *
     * <p>Read out of {@link ListFilters#filterTabs()} rather than restated, so a sixth tab appears in the
     * document without anyone editing this file -- and a {@code description} rather than an {@code enum} for the
     * reason the {@code template} path token is left open: an unrecognised key falls back to the first tab
     * instead of being refused, so a validating client that rejected one would refuse a request the service
     * answers.
     */
    private void describeFilter(Operation list) {
        if (list.getParameters() == null) {
            return;
        }
        list.getParameters().stream()
            .filter(parameter -> FILTER_PARAMETER.equals(parameter.getName()))
            .forEach(parameter -> parameter.setDescription(
                "Which tab of the case list to return: " + tabs() + ". An unrecognised or repeated value falls "
                    + "back to `" + ListFilters.LIST_FILTERS.get(0).key() + "` rather than being refused, "
                    + "because the key comes from a URL a caseworker may have bookmarked -- the response echoes "
                    + "the key that was actually applied, so the fallback is observable. ANDs with `q` and every "
                    + "`where` condition. `awaitingConfirmation` is the cases still waiting for an applicant's "
                    + "reply to the Request confirmation to proceed email, and each row on it carries "
                    + "`daysAwaitingConfirmation`."));
    }

    /** The tab keys and their labels, in render order, so the document lists them as the tab bar does. */
    private String tabs() {
        return ListFilters.filterTabs().stream()
            .map(tab -> "`" + tab.key() + "` (" + tab.label() + ")")
            .collect(Collectors.joining(", "));
    }

    /**
     * {@code q}, the search box: one term, matched against the reference or the names.
     *
     * <p>The name columns are read out of {@link CaseSearch} rather than restated, for the reason the
     * operator table is read out of {@link QueryOperator}: a column added to the matcher appears in the
     * document without anyone editing this file. The description says the matching is substring and
     * case-insensitive because a consumer building a search box has to know it is not fuzzy -- "Smyth" does
     * not find "Smith", and a UI that promised it would be promising for the service.
     */
    private Parameter search() {
        return queryParameter("q", new StringSchema(),
            "A search term. Matched against the case reference exactly -- tolerant of case, hyphens and "
                + "spaces, so `kbdp2026-10100` and `KBDP202610100` find the same case -- or as a "
                + "case-insensitive substring of " + String.join(", ", CaseSearch.NAME_FIELDS) + ". Those two "
                + "are ORed, and the result is ANDed with `filter` and every `where` condition. Matching is "
                + "substring and not fuzzy: a misspelling does not match. Addresses and narrative fields are "
                + "deliberately not searched. Longer than " + CaseSearch.MAX_TERM_LENGTH + " characters is "
                + "truncated rather than refused. Where the term matched one case's reference exactly, the "
                + "response's `referenceMatch` carries that case's id.");
    }

    /**
     * {@code where}, the one genuinely repeated parameter: one condition per occurrence, ANDed together.
     *
     * <p>{@code explode: true} with an array schema is what says "repeat the parameter" rather than "send one
     * comma-separated value", and the difference is load-bearing here -- a generated client that joined the
     * conditions with commas would send a single unparseable entry, and a comma is already meaningful inside a
     * {@code between} or {@code isOneOf} value.
     */
    private Parameter where() {
        return queryParameter("where", new ArraySchema().items(new StringSchema()),
            "A condition, as `field:operator:value`. Repeat the parameter for more than one; they are ANDed, "
                + "and there is no OR. Split on the first two colons only, so a colon inside a value survives. "
                + "The field must be a queryable case field and the operator must apply to its kind, or the "
                + "request is a 400 naming every condition it could not read rather than only the first. "
                + "Operators: " + operators() + ".")
            .explode(true);
    }

    /** The operator table, read out of the enum so it cannot drift from the one that validates. */
    private String operators() {
        return Arrays.stream(QueryOperator.values())
            .map(operator -> operator.wire() + " (" + operator.arity().requirement() + ")")
            .collect(Collectors.joining(", "));
    }

    /**
     * {@code sort}, whose default depends on the {@code filter} and is described as doing so.
     *
     * <p>Newest first for every tab but {@code awaitingConfirmation}, which is asked for longest wait first
     * because a waiting list in creation order buries the case at risk of being forgotten. Worth stating rather
     * than leaving a consumer to discover: a client that assumed one default and rendered an unsorted-looking
     * column header would be describing the wrong order to a caseworker.
     */
    private Parameter sort() {
        return queryParameter("sort", new StringSchema(),
            "An order, as `field:" + CaseSort.Direction.wireTokens().replace(", ", "|") + "`. The direction may "
                + "be omitted and defaults to ascending. Sortable fields are the columns the list shows: "
                + String.join(", ", CaseSort.sortableFields()) + ". Defaults to newest first, except on the "
                + "`awaitingConfirmation` filter, which defaults to `"
                + ListFilters.LONGEST_WAIT_FIRST.field() + ":"
                + ListFilters.LONGEST_WAIT_FIRST.direction().wire() + "` -- longest wait first.");
    }

    private Parameter page() {
        return queryParameter("page",
            new IntegerSchema()._default(CasePaging.FIRST_PAGE).minimum(BigDecimal.ONE),
            "The page to return, 1-based. Defaults to " + CasePaging.FIRST_PAGE + ".");
    }

    /**
     * {@code size}, whose cap is stated in the description and deliberately <strong>not</strong> published as a
     * {@code maximum}.
     *
     * <p>{@code CasePaging.parse} clamps an over-cap size and accepts the request; only {@code size=0} and an
     * unreadable size are refused. A {@code maximum: 100} would therefore describe a stricter API than exists
     * and make a validating generated client reject {@code size=1000} that the service answers -- the same
     * reasoning {@link OpenAPIWriteBodies} gives for leaving {@code additionalProperties} true. The cap is a
     * property of the service rather than a constraint on the caller, so it belongs in prose.
     */
    private Parameter size() {
        return queryParameter("size",
            new IntegerSchema()._default(CasePaging.DEFAULT_SIZE).minimum(BigDecimal.ONE),
            "How many rows on the page. Defaults to " + CasePaging.DEFAULT_SIZE + " and is capped at "
                + CasePaging.MAX_SIZE + ": a larger value is clamped to the cap rather than refused, because an "
                + "unbounded list is a denial of service against the service's own database.");
    }

    private Parameter queryParameter(String name, Schema<?> schema, String description) {
        return new Parameter()
            .in("query")
            .name(name)
            .required(false)
            .schema(schema)
            .description(description);
    }

    /**
     * Removes the component springdoc generated for the map. Left behind it would be an unreferenced schema
     * describing the service's parameter binding as though it were part of the contract.
     */
    private void dropReflectedSchema(OpenAPI openApi) {
        Map<String, Schema> schemas = openApi.getComponents() == null
            ? null
            : openApi.getComponents().getSchemas();
        if (schemas != null) {
            schemas.remove(REFLECTED_SCHEMA);
        }
    }
}
