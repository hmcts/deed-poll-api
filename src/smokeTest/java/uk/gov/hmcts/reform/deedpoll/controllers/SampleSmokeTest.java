package uk.gov.hmcts.reform.deedpoll.controllers;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * Smoke test: the deployed service answers on its root path.
 *
 * <p><strong>This must not boot the application.</strong> The template version carried
 * {@code @SpringBootTest(webEnvironment = DEFINED_PORT)}, which started a second copy of the service on the
 * Jenkins agent — Flyway and Hibernate included. With no {@code DB_HOST}/{@code DB_PORT} there, the datasource
 * fell back to the docker-compose defaults in {@code application.yaml} and the context failed on
 * {@code Connection to localhost:5433 refused}, before any request reached {@code TEST_URL}. Master build #6
 * died that way, having already deployed successfully. A smoke test talks to a deployment over HTTP and needs
 * no Spring context of its own.
 *
 * <p>{@code TEST_URL} is read from the environment because that is where Jenkins puts it; the localhost
 * default matches {@code server.port} for running this by hand against a local instance.
 */
class SampleSmokeTest {

    private static final String TEST_URL = System.getenv().getOrDefault("TEST_URL", "http://localhost:4550");

    @BeforeEach
    public void setUp() {
        RestAssured.useRelaxedHTTPSValidation();
    }

    @Test
    void smokeTest() {
        Response response = given()
            .baseUri(TEST_URL)
            .contentType(ContentType.JSON)
            .when()
            .get()
            .then()
            .extract().response();

        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertTrue(
            response.asString().startsWith("Welcome"),
            () -> "Expected the root path to answer with the welcome message, got: " + response.asString()
        );
    }
}
