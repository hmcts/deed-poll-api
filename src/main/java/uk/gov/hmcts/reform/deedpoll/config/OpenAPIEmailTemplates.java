package uk.gov.hmcts.reform.deedpoll.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.reform.deedpoll.notify.EmailTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Names the emails a caseworker may send, on the {@code {template}} token of the two operations that take one.
 *
 * <p>The same problem {@link OpenAPIQueryParameters} solves, one parameter over: springdoc reflects the token as
 * an undescribed required string, because that is what {@code CaseworkerEmailController} declares it as -- so the
 * published document said a path token existed without saying a single value it accepts. A client generated from
 * it compiled and could only discover the keys by reading this service's source or by being 404'd.
 *
 * <p><strong>Generated from {@link EmailTemplate} rather than listed here, which is the point.</strong> A template
 * added to the enum appears in the document without this file being edited, so {@code deed-poll-admin}'s Email tab
 * sees a new email on the next contract refresh without a change of its own -- which is what that plan was written
 * to need. {@code EmailTemplate.keys()} is the same string the 404 names when a token matches nothing, so the
 * document and the refusal cannot disagree about what would have worked.
 *
 * <p><strong>A description and deliberately not an {@code enum} constraint.</strong> An enumerated path parameter
 * would become a closed union in a generated client, so adding a fifth template would stop old clients from
 * calling it and the {@code GET} that publishes availability -- which is where a tab is meant to learn what it may
 * send -- would be contradicted by the client's own types. The service's answer to an unknown token is a 404
 * naming the alternatives, and prose is what describes that honestly. It is the reasoning
 * {@link OpenAPIQueryParameters} gives for leaving the page size's cap out of {@code maximum}.
 */
@Configuration
public class OpenAPIEmailTemplates {

    /** The send and the preview, as they appear in the document's paths. */
    private static final String SEND_PATH = "/caseworker/cases/{id}/emails/{template}";
    private static final String PREVIEW_PATH = SEND_PATH + "/preview";

    /** The token both of them carry. */
    private static final String TEMPLATE_TOKEN = "template";

    @Bean
    public OpenApiCustomizer emailTemplateTokenCustomizer() {
        return openApi -> {
            String description = describedTemplates();
            for (String path : List.of(SEND_PATH, PREVIEW_PATH)) {
                emailOperation(openApi, path).forEach(operation -> describeToken(operation, description));
            }
        };
    }

    /**
     * Every operation on one of the two email paths.
     *
     * <p>All of them rather than the {@code POST} each carries today: a verb added to either path would otherwise
     * publish the token undescribed again, which is the state this class exists to leave behind.
     */
    private List<Operation> emailOperation(OpenAPI openApi, String path) {
        PathItem item = openApi.getPaths() == null ? null : openApi.getPaths().get(path);
        return item == null ? List.of() : item.readOperations();
    }

    /**
     * Describes the token and touches nothing else -- not its schema and not whether it is required, so nothing
     * about how a client sends it changes.
     */
    private void describeToken(Operation operation, String description) {
        if (operation.getParameters() == null) {
            return;
        }
        operation.getParameters().stream()
            .filter(parameter -> TEMPLATE_TOKEN.equals(parameter.getName()))
            .forEach(parameter -> parameter.setDescription(description));
    }

    /**
     * The sentence, with one entry per template: the key a caller sends and the name a caseworker sees for it.
     */
    private String describedTemplates() {
        return "Which email. Matched exactly -- a near miss is a 404 naming what would have been accepted, "
            + "because this token is a value the caller read out of GET /caseworker/cases/{id}/emails. One of: "
            + entries() + ". That GET is also where a client learns which of them this case may have sent and "
            + "whether this environment can send it at all: a template it reports as inapplicable is a 409 here, "
            + "and an unprovisioned one fails at GOV.UK Notify.";
    }

    private String entries() {
        return Arrays.stream(EmailTemplate.values())
            .map(template -> template.key() + " (" + template.displayName() + ")")
            .collect(Collectors.joining(", "));
    }
}
