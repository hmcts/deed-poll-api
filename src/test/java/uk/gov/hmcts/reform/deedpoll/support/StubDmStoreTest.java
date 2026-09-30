package uk.gov.hmcts.reform.deedpoll.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class StubDmStoreTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void uploadAttachReadAndSoftDeleteKeepBytesAndMetadataConsistent() throws Exception {
        try (StubDmStore stub = new StubDmStore(0)) {
            String boundary = "dm-boundary";
            String body = part(boundary, "classification", "RESTRICTED")
                + part(boundary, "metadata[jurisdiction]", "TEST")
                + part(boundary, "metadata[case_type_id]", "TEST")
                + part(boundary, "ttl", "2099-01-01T00:00:00.000+0000")
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"sample.txt\""
                + "\r\nContent-Type: text/plain\r\n\r\noriginal bytes\r\n--" + boundary + "--\r\n";
            HttpResponse<String> upload = send(stub, "/documents", "POST", body,
                "multipart/form-data; boundary=" + boundary);
            assertThat(upload.statusCode()).isEqualTo(200);
            JsonNode document = json.readTree(upload.body()).path("_embedded").path("documents").get(0);
            String path = URI.create(document.path("_links").path("self").path("href").asText()).getPath();
            String id = path.substring(path.lastIndexOf('/') + 1);
            assertThat(send(stub, path + "/binary", "GET", "", "application/json").body())
                .isEqualTo("original bytes");
            assertThat(send(stub, "/documents", "PATCH", """
                {"ttl":null,"documents":[{"documentId":"%s","metadata":{"case_id":"1234567890123456"}}]}
                """.formatted(id), "application/json").statusCode()).isEqualTo(200);
            JsonNode attached = json.readTree(send(stub, path, "GET", "", "application/json").body());
            assertThat(attached.path("metadata").path("case_id").asText()).isEqualTo("1234567890123456");
            assertThat(attached.path("ttl").isNull()).isTrue();
            assertThat(send(stub, path, "PATCH", "{\"ttl\":\"2099-02-01T00:00:00.000+0000\"}",
                "application/json").statusCode()).isEqualTo(200);
            assertThat(send(stub, path + "?permanent=true", "DELETE", "", "application/json").statusCode())
                .isEqualTo(404);
            assertThat(send(stub, path + "?permanent=false", "DELETE", "", "application/json").statusCode())
                .isEqualTo(204);
            assertThat(send(stub, path, "GET", "", "application/json").statusCode()).isEqualTo(404);
            assertThat(send(stub, path + "/binary", "GET", "", "application/json").statusCode()).isEqualTo(404);
        }
    }

    @Test
    void missingRequiredHeadersAndMultipartFieldsAreNotAccepted() throws Exception {
        try (StubDmStore stub = new StubDmStore(0)) {
            HttpResponse<String> noHeaders = http.send(HttpRequest.newBuilder(
                URI.create(stub.server().baseUrl() + "/documents")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
            assertThat(noHeaders.statusCode()).isEqualTo(404);
            assertThat(send(stub, "/documents", "POST", "", "multipart/form-data; boundary=empty").statusCode())
                .isEqualTo(404);
        }
    }

    private HttpResponse<String> send(StubDmStore stub, String path, String method, String body, String type)
        throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(stub.server().baseUrl() + path))
            .header("ServiceAuthorization", "test-service").header("user-id", "test-user")
            .header("Content-Type", type).method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private String part(String boundary, String name, String value) {
        return "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n";
    }
}
