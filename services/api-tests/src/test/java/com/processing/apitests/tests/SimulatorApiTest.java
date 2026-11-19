package com.processing.apitests.tests;

import com.processing.apitests.support.Specs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Симуляторы терминалов и мерчантов: POST /api/simulator/terminal/run и
 * POST /api/simulator/merchant/run (маршрутизируются Gateway).
 * Источник: api-spec.md (раздел Simulator).
 */
class SimulatorApiTest {

    @Test
    void terminalRun_returns200_structure() {
        Specs.gateway()
                .body("{\"count\":2,\"scenario\":\"normal\",\"tps\":1}")
                .when().post("/api/simulator/terminal/run")
                .then()
                .statusCode(200)
                .body("totalSubmitted", equalTo(2))
                .body("approved", greaterThanOrEqualTo(0))
                .body("declined", greaterThanOrEqualTo(0))
                .body("elapsedMs", notNullValue())
                .body("transactions", notNullValue());
    }

    /** count < 1 нарушает @Min(1). */
    @ParameterizedTest(name = "terminalRun invalid count={0} -> 400")
    @ValueSource(ints = {0, -1})
    void terminalRun_invalidCount_returns400(int count) {
        Specs.gateway()
                .body("{\"count\":" + count + ",\"scenario\":\"normal\",\"tps\":1}")
                .when().post("/api/simulator/terminal/run")
                .then()
                .statusCode(400);
    }

    @Test
    void terminalRun_invalidScenario_returns400() {
        Specs.gateway()
                .body("{\"count\":2,\"scenario\":\"not_a_scenario\",\"tps\":1}")
                .when().post("/api/simulator/terminal/run")
                .then()
                .statusCode(400);
    }

    @Test
    void merchantRun_returns200_structure() {
        Specs.gateway()
                .body("{\"count\":2,\"scenario\":\"grocery\",\"mccCodes\":[\"5411\"],\"tps\":1}")
                .when().post("/api/simulator/merchant/run")
                .then()
                .statusCode(200)
                .body("totalSubmitted", equalTo(2))
                .body("approved", greaterThanOrEqualTo(0))
                .body("declined", greaterThanOrEqualTo(0))
                .body("transactions", notNullValue());
    }

    @Test
    void merchantRun_invalidScenario_returns400() {
        Specs.gateway()
                .body("{\"count\":2,\"scenario\":\"unknown\"}")
                .when().post("/api/simulator/merchant/run")
                .then()
                .statusCode(400);
    }
}
