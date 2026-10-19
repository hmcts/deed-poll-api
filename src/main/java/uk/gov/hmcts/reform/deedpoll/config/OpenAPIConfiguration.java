package uk.gov.hmcts.reform.deedpoll.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.servers.ServerVariable;
import io.swagger.v3.oas.models.servers.ServerVariables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The published OpenAPI document.
 *
 * <p>{@code OpenAPIPublisherTest} writes it to {@code /tmp/openapi-specs.json}, which is where the HMCTS
 * Jenkins library picks it up to publish to {@code hmcts/cnp-api-docs} -- the same wiring
 * {@code spring-boot-template} ships and {@code pcs-api} keeps, unchanged so that it keeps working when the
 * pipeline is onboarded. Which packages are scanned is {@code springdoc.packagesToScan} in
 * {@code application.yaml}.
 */
@Configuration
public class OpenAPIConfiguration {

    /**
     * Referenced by {@code @SecurityRequirements} on the routes that are exceptions to it.
     */
    public static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
            // Do not derive a credential-bearing server URL from the HTTP request used by the publisher.
            // This declares the required transport; deployment must provision a matching TLS certificate.
            .addServersItem(new Server()
                .url("https://deed-poll-api-{environment}.service.core-compute-{environment}.internal")
                .description("Private API TLS endpoint; requires a trusted certificate for the selected host")
                .variables(new ServerVariables().addServerVariable("environment", new ServerVariable()
                    ._default("aat").description("Target deployment environment"))))
            .info(new Info().title("deed-poll-api")
                      .description("Deed poll case management API")
                      .version("v0.0.1")
                      .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")))
            .externalDocs(new ExternalDocumentation()
                              .description("README")
                              .url("https://github.com/hmcts/deed-poll-api"))
            /*
              Declared globally because the service authenticates by default -- see SecurityConfiguration --
              so a route needing a token is the rule and not the exception. A published spec that omitted this
              would describe an open API, and the first consumer generated from it would be a client that
              cannot make a single call.
             */
            .components(new Components().addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("A forwarded IDAM access token.")))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }

}
