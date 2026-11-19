package com.processing.apitests.tests;

import com.processing.apitests.support.Cards;
import com.processing.apitests.support.Requests;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import com.processing.apitests.support.Transactions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Поиск и экспорт транзакций: GET /api/transactions/search и /export.
 * Источник: TransactionController + TransactionFilter (логика валидации).
 */
class SearchApiTest {

    @Test
    void search_returns200_withTotalAndTransactions() {
        String pan = Cards.createCard();
        String stan = Transactions.authorize(pan, TestData.stan(), new BigDecimal("1000"));
        Transactions.awaitStored(pan, stan);

        Specs.gateway()
                .queryParam("pan", pan)
                .when().get("/api/transactions/search")
                .then()
                .statusCode(200)
                .body("total", greaterThanOrEqualTo(1))
                .body("transactions", notNullValue());
    }

    @Test
    void search_byPan_returnsOnlyMatchingTransactions() {
        String pan = Cards.createCard();
        String stan = Transactions.authorize(pan, TestData.stan(), new BigDecimal("1000"));
        Transactions.awaitStored(pan, stan);

        // GPath-фильтр: отбираем записи, принадлежащие нашему объекту теста
        Specs.gateway()
                .queryParam("pan", pan)
                .when().get("/api/transactions/search")
                .then()
                .statusCode(200)
                .body("transactions.findAll { it.pan == '" + pan + "' }",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.hasEntry("stan", stan)));
    }

    @Test
    void search_byStatus_returnsOnlyThatStatus() {
        String pan = Cards.createCard();
        String stan = Transactions.authorize(pan, TestData.stan(), new BigDecimal("1000"));
        Transactions.awaitStored(pan, stan);

        Specs.gateway()
                .queryParam("pan", pan)
                .queryParam("status", "APPROVED")
                .when().get("/api/transactions/search")
                .then()
                .statusCode(200)
                .body("transactions.find { it.stan == '" + stan + "' }.status", equalTo("APPROVED"));
    }

    @Test
    void search_emptyResult_returnsTotalZero() {
        // PAN, по которому транзакций нет
        String pan = TestData.pan(Requests.BIN);

        Specs.gateway()
                .queryParam("pan", pan)
                .when().get("/api/transactions/search")
                .then()
                .statusCode(200)
                .body("total", equalTo(0))
                .body("transactions", hasSize(0));
    }

    @Test
    void search_pagination_limitsResultSize() {
        String pan = Cards.createCard();
        String stan1 = TestData.stan();
        String stan2 = TestData.stan();
        String stan3 = TestData.stan();
        Transactions.authorize(pan, stan1, new BigDecimal("1000"));
        Transactions.authorize(pan, stan2, new BigDecimal("2000"));
        Transactions.authorize(pan, stan3, new BigDecimal("3000"));
        Transactions.awaitStored(pan, stan3);

        // Ждём, пока все три транзакции появятся, затем проверяем пагинацию
        Specs.gateway()
                .queryParam("pan", pan)
                .queryParam("limit", 2)
                .queryParam("offset", 0)
                .when().get("/api/transactions/search")
                .then()
                .statusCode(200)
                .body("transactions.size()", org.hamcrest.Matchers.lessThanOrEqualTo(2));
    }

    /** Правила валидации параметров поиска: один невалидный класс — один тест. */
    @ParameterizedTest(name = "search invalid limit={0} -> 400")
    @ValueSource(ints = {0, -1, 501})
    void search_invalidLimit_returns400(int limit) {
        Specs.gateway()
                .queryParam("limit", limit)
                .when().get("/api/transactions/search")
                .then()
                .statusCode(400)
                .body("message", notNullValue());
    }

    @Test
    void search_invalidOffset_returns400() {
        Specs.gateway()
                .queryParam("offset", -1)
                .when().get("/api/transactions/search")
                .then()
                .statusCode(400)
                .body("message", notNullValue());
    }

    @Test
    void search_invalidStatus_returns400() {
        Specs.gateway()
                .queryParam("status", "NOT_A_STATUS")
                .when().get("/api/transactions/search")
                .then()
                .statusCode(400)
                .body("message", notNullValue());
    }

    @Test
    void search_dateFromAfterDateTo_returns400() {
        Specs.gateway()
                .queryParam("dateFrom", "2026-06-02")
                .queryParam("dateTo", "2026-06-01")
                .when().get("/api/transactions/search")
                .then()
                .statusCode(400)
                .body("message", notNullValue());
    }

    @Test
    void export_returns200_csvWithHeaders() {
        // Проверка заголовков: Content-Type и Content-Disposition — часть контракта
        Specs.gateway()
                .accept("text/csv")
                .queryParam("limit", 1)
                .when().get("/api/transactions/export")
                .then()
                .statusCode(200)
                .header("Content-Type", containsString("text/csv"))
                .header("Content-Disposition", containsString("attachment"))
                .body(notNullValue());
    }
}
