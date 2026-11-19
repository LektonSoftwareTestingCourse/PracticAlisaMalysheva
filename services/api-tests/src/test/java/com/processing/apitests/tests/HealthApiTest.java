package com.processing.apitests.tests;

import com.processing.apitests.support.Specs;
import org.junit.jupiter.api.Test;

import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Эндпоинт {@code GET /health} Gateway.
 * Источник ожидания: api-spec.md (раздел Health).
 */
class HealthApiTest {

    @Test
    void health_returns200_withDownstreamStatuses() {
        // given / when / then
        Specs.gateway()
                .when().get("/health")
                .then()
                .statusCode(200)
                .header("Content-Type", notNullValue())
                // Проверяем не только статус, но и тело: состав и значения контракта
                .body("status", notNullValue())
                .body("service", equalTo("gateway"))
                .body("version", notNullValue())
                .body("services", hasKey("switch"))
                .body("services", hasKey("authorization"))
                .body("services", hasKey("cardManagement"))
                .body("services", hasKey("logger"));
    }

    @Test
    void health_jsonMatchesContractSchema() {
        Specs.gateway()
                .when().get("/health")
                .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/health-schema.json"));
    }

    @Test
    void health_servicesStatusesAreReported() {
        // Значения статусов downstream-сервисов — часть контракта health-эндпоинта
        Specs.gateway()
                .when().get("/health")
                .then()
                .statusCode(200)
                .body("services.values()", org.hamcrest.Matchers.everyItem(notNullValue()));
    }
}