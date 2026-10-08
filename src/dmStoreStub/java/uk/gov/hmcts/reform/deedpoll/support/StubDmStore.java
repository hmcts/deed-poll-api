package uk.gov.hmcts.reform.deedpoll.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * The dm-store subset CDAM uses, with real bytes and mutable metadata. No CDAM or CCD behaviour is stubbed here.
 * Response provenance and the published spec's header discrepancies are recorded in dm-store-contract.md.
 * Unknown methods and paths remain WireMock 404s; permanent deletion is deliberately unsupported.
 */
public final class StubDmStore implements AutoCloseable {

    public static final String CFTLIB_VERSION = "0.19.2377";
    public static final String SPEC_COMMIT = "6d8040564754e7f8db60f146370a37ae8d6e1eba";
    public static final List<String> UPLOAD_PARTS = List.of(
        "files", "classification", "metadata[jurisdiction]", "metadata[case_type_id]", "ttl");

    /** The same table registers the mappings and is checked against the pinned OpenAPI document. */
    public static final List<Operation> OPERATIONS = List.of(
        new Operation("POST", "/documents", List.of("serviceauthorization")),
        new Operation("GET", "/documents/{documentId}", List.of("serviceauthorization")),
        new Operation("GET", "/documents/{documentId}/binary", List.of("serviceauthorization", "user-id")),
        new Operation("PATCH", "/documents", List.of("serviceauthorization")),
        new Operation("PATCH", "/documents/{documentId}", List.of("serviceauthorization")),
        new Operation("DELETE", "/documents/{documentId}", List.of("serviceauthorization")));

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Map<String, Stored> documents = new LinkedHashMap<>();
    private final WireMockServer server;

    public StubDmStore(int port) {
        server = new WireMockServer(wireMockConfig().port(port).extensions(new Documents()));
        server.start();
        for (Operation operation : OPERATIONS) {
            MappingBuilder mapping = WireMock.request(operation.method(), WireMock.urlPathMatching(
                operation.path().replace("{documentId}", "[a-fA-F0-9-]{36}")));
            operation.headers().forEach(header -> mapping.withHeader(header, WireMock.matching(".+")));
            if (operation.method().equals("POST")) {
                UPLOAD_PARTS.forEach(part -> mapping.withMultipartRequestBody(
                    WireMock.aMultipart().withName(part)));
            }
            if (operation.method().equals("DELETE")) {
                mapping.withQueryParam("permanent", WireMock.equalTo("false"));
            }
            server.stubFor(mapping.willReturn(WireMock.aResponse().withTransformers("dm-documents")));
        }
    }

    public WireMockServer server() {
        return server;
    }

    @Override
    public void close() {
        server.stop();
    }

    public record Operation(String method, String path, List<String> headers) {
    }

    private record Stored(ObjectNode metadata, byte[] bytes) {
    }

    private final class Documents implements ResponseDefinitionTransformerV2 {
        @Override
        public String getName() {
            return "dm-documents";
        }

        @Override
        public boolean applyGlobally() {
            return false;
        }

        @Override
        public ResponseDefinition transform(ServeEvent event) {
            synchronized (documents) {
                try {
                    return respond(event.getRequest());
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }
        }

        private ResponseDefinition respond(Request request) throws IOException {
            String path = request.getUrl().split("\\?")[0];
            String method = request.getMethod().getName();
            if (method.equals("POST")) {
                final String id = UUID.randomUUID().toString();
                Request.Part file = request.getPart("files");
                ObjectNode metadata = JSON.createObjectNode();
                metadata.put("classification", request.getPart("classification").getBody().asString());
                metadata.put("size", file.getBody().asBytes().length);
                // Recognises only our two fixtures; real MIME detection is dm-store's, outside this stub's scope.
                metadata.put("mimeType", file.getBody().asString().startsWith("%PDF-")
                    ? "application/pdf" : "text/plain");
                String disposition = file.getHeader("Content-Disposition").firstValue();
                // Sliced rather than matched: the `.*filename="([^"]+)".*` it replaces backtracks super-linearly.
                String afterName = disposition.substring(disposition.indexOf("filename=\"") + "filename=\"".length());
                metadata.put("originalDocumentName", afterName.substring(0, afterName.indexOf('"')));
                metadata.put("createdBy", request.getHeader("user-id"));
                metadata.put("ttl", request.getPart("ttl").getBody().asString());
                ObjectNode fields = metadata.putObject("metadata");
                fields.put("jurisdiction", request.getPart("metadata[jurisdiction]").getBody().asString());
                fields.put("case_type_id", request.getPart("metadata[case_type_id]").getBody().asString());
                ObjectNode links = metadata.putObject("_links");
                String href = "http://dm-store:8080/documents/" + id;
                links.putObject("self").put("href", href);
                links.putObject("binary").put("href", href + "/binary");
                documents.put(id, new Stored(metadata, file.getBody().asBytes()));
                ObjectNode response = JSON.createObjectNode();
                response.putObject("_embedded").putArray("documents").add(metadata);
                return json(response);
            }
            if (path.equals("/documents") && method.equals("PATCH")) {
                JsonNode body = JSON.readTree(request.getBodyAsString());
                for (JsonNode update : body.path("documents")) {
                    if (!documents.containsKey(update.path("documentId").asText())) {
                        return WireMock.aResponse().withStatus(404).build();
                    }
                }
                for (JsonNode update : body.path("documents")) {
                    ObjectNode metadata = documents.get(update.path("documentId").asText()).metadata();
                    ((ObjectNode) metadata.path("metadata")).setAll((ObjectNode) update.path("metadata"));
                    metadata.set("ttl", body.path("ttl"));
                }
                return json(JSON.createObjectNode().put("result", "Success"));
            }
            String id = path.split("/")[2];
            Stored stored = documents.get(id);
            if (stored == null) {
                return WireMock.aResponse().withStatus(404).build();
            }
            if (method.equals("DELETE")) {
                documents.remove(id);
                return WireMock.aResponse().withStatus(204).build();
            }
            if (method.equals("PATCH")) {
                stored.metadata().set("ttl", JSON.readTree(request.getBodyAsString()).path("ttl"));
            }
            if (path.endsWith("/binary")) {
                return WireMock.aResponse().withHeader("Content-Type", stored.metadata().path("mimeType").asText())
                    .withHeader("OriginalFileName", stored.metadata().path("originalDocumentName").asText())
                    .withBody(stored.bytes()).build();
            }
            return json(stored.metadata());
        }

        private ResponseDefinition json(JsonNode body) {
            return WireMock.aResponse().withHeader("Content-Type", "application/hal+json")
                .withBody(body.toString()).build();
        }
    }
}
