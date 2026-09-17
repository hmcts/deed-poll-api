package uk.gov.hmcts.reform.deedpoll.controllers;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * Functional test: the deployed service answers on its root path.
 *
 * <p><strong>This must not boot the application</strong>, for the reason recorded on
 * {@link SampleSmokeTest} — the template's {@code @SpringBootTest(webEnvironment = DEFINED_PORT)} started a
 * local copy on the Jenkins agent, whose datasource then fell back to the docker-compose defaults and failed
 * on {@code Connection to localhost:5433 refused}. Master build #7 died that way, in this file, one stage
 * after the same fix landed for the smoke test.
 *
 * <p>The localhost default is {@code 4550}, matching {@code server.port}. The template said {@code 8080},
 * which is nothing this service has ever listened on.
 */
class SampleFunctionalTest {

    private static final String TEST_URL = System.getenv().getOrDefault("TEST_URL", "http://localhost:4550");

    @BeforeEach
    public void setUp() {
        RestAssured.baseURI = TEST_URL;
        RestAssured.useRelaxedHTTPSValidation();
    }

    @Test
    void functionalTest() {
        Response response = given()
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
