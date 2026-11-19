package com.processing.apitests.tests;

import com.processing.apitests.support.Specs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.Matchers.notNullValue;

/**
 * Dashboard API через Gateway: GET /api/dashboard/stats, /recent, /charts
 * (Transaction Logger DashboardController).
 */
class DashboardApiTest {

    @Test
    void stats_returns200_withContractFields() {
        Specs.gateway()
                .when().get("/api/dashboard/stats")
                .then()
                .statusCode(200)
                .body("totalTransactions", notNullValue())
                .body("approvedCount", notNullValue())
                .body("declinedCount", notNullValue())
                .body("approvalRate", notNullValue())
                .body("totalAmount", notNullValue())
                .body("averageAmount", notNullValue())
                .body("avgProcessingTimeMs", notNullValue())
                .body("transactionsPerMinute", notNullValue());
    }

    @Test
    void recent_returns200_list() {
        Specs.gateway()
                .queryParam("limit", 5)
                .when().get("/api/dashboard/recent")
                .then()
                .statusCode(200)
                .body("size()", org.hamcrest.Matchers.lessThanOrEqualTo(5));
    }

    /** limit: границы 1..500, нарушение -> 400. */
    @ParameterizedTest(name = "recent invalid limit={0} -> 400")
    @ValueSource(ints = {0, -1, 501})
    void recent_invalidLimit_returns400(int limit) {
        Specs.gateway()
                .queryParam("limit", limit)
                .when().get("/api/dashboard/recent")
                .then()
                .statusCode(400)
                .body("message", notNullValue());
    }

    @Test
    void charts_returns200_listOfBuckets() {
        Specs.gateway()
                .queryParam("granularity", "hour")
                .when().get("/api/dashboard/charts")
                .then()
                .statusCode(200)
                .body("size()", org.hamcrest.Matchers.greaterThanOrEqualTo(0));
    }

    @Test
    void charts_invalidGranularity_returns400() {
        Specs.gateway()
                .queryParam("granularity", "week")
                .when().get("/api/dashboard/charts")
                .then()
                .statusCode(400)
                .body("message", notNullValue());
    }
}