package com.processing.apitests.tests;

import com.processing.apitests.support.Cards;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import com.processing.apitests.support.Transactions;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Асинхронные эффекты и сквозная цепочка. Логирование транзакций и уведомления
 * о картах происходят через RabbitMQ, поэтому проверяются ожиданием условия
 * через Awaitility (без фиксированных пауз), а не паузой.
 */
class AsyncFlowApiTest {

    @Test
    void approvedTransaction_becomesSearchable_async() {
        String pan = Cards.createCard();
        String stan = TestData.stan();
        Transactions.authorize(pan, stan, new BigDecimal("1000"));

        // Асинхронное появление: транзакция через Gateway доходит до хранилища
        // и становится доступной для поиска
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    JsonPath jsonPath = Cards.byPan(pan);
                    int matches = jsonPath.getInt(
                            "transactions.findAll { it.stan == '" + stan + "' }.size()");
                    org.junit.jupiter.api.Assertions.assertEquals(1, matches,
                            "Transaction not stored yet");
                });

        // Сквозная проверка: транзакция записана со статусом APPROVED
        Specs.gateway()
                .queryParam("pan", pan)
                .when().get("/api/transactions/search")
                .then()
                .body("transactions.find { it.stan == '" + stan + "' }.status",
                        org.hamcrest.Matchers.equalTo("APPROVED"))
                .body("transactions.find { it.stan == '" + stan + "' }.responseCode",
                        org.hamcrest.Matchers.equalTo("00"));
    }

    @Test
    void declinedTransaction_isNotApproved_async() {
        // Карта с малым остатком — транзакция будет отклонена
        String pan = Cards.createCard(new BigDecimal("15000000"),
                new BigDecimal("300000000"), new BigDecimal("100"));
        String stan = TestData.stan();
        Transactions.authorize(pan, stan, new BigDecimal("1000"));

        // Отклонённая транзакция также логируется асинхронно...
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    JsonPath jsonPath = Cards.byPan(pan);
                    int matches = jsonPath.getInt(
                            "transactions.findAll { it.stan == '" + stan + "' }.size()");
                    org.junit.jupiter.api.Assertions.assertEquals(1, matches,
                            "Declined transaction not stored yet");
                });

        // ...но эффект одобрения НЕ наступил: нет APPROVED-транзакции с этим STAN,
        // сама транзакция записана как DECLINED
        Specs.gateway()
                .queryParam("pan", pan)
                .when().get("/api/transactions/search")
                .then()
                .body("transactions.findAll { it.stan == '" + stan
                        + "' && it.status == 'APPROVED' }", hasSize(0))
                .body("transactions.find { it.stan == '" + stan + "' }.status",
                        org.hamcrest.Matchers.equalTo("DECLINED"));
    }

    @Test
    void cardPatchEvent_reachesNotificationService_async() {
        String pan = Cards.createCard();

        // PATCH порождает card-событие, которое через outbox/RabbitMQ доходит
        // до Notification Service и сохраняется
        Specs.gateway()
                .body("{\"status\":\"BLOCKED\"}")
                .when().patch("/api/cards/{pan}", pan)
                .then().statusCode(200);

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    Specs.notifications()
                            .queryParam("page", 0)
                            .queryParam("size", 100)
                            .when().get("/api/notifications")
                            .then()
                            .statusCode(200)
                            .body("content.findAll { it.payload.contains('" + pan + "') }",
                                    hasSize(greaterThanOrEqualTo(1)));
                });
    }
}