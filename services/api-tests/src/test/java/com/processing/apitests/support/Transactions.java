package com.processing.apitests.support;

import io.restassured.path.json.JsonPath;

import java.math.BigDecimal;
import java.time.Duration;

import static org.awaitility.Awaitility.await;

/**
 * Хелперы для транзакций: отправка авторизации и ожидание асинхронного
 * сохранения в transaction-logger (через RabbitMQ) средствами Awaitility.
 */
public final class Transactions {

    private static final Duration AT_MOST = Duration.ofSeconds(20);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private Transactions() {
    }

    /** Отправляет запрос авторизации через Gateway и возвращает STAN. */
    public static String authorize(String pan, String stan, BigDecimal amount) {
        Specs.gateway()
                .body(Json.of(Requests.authorize(pan, stan, amount)))
                .when().post("/api/transactions")
                .then().statusCode(200);
        return stan;
    }

    /** Ожидает появления транзакции с заданным STAN в поиске по PAN. */
    public static void awaitStored(String pan, String stan) {
        await().atMost(AT_MOST).pollInterval(POLL_INTERVAL).untilAsserted(() -> {
            JsonPath jsonPath = Cards.byPan(pan);
            int matches = jsonPath.getInt(
                    "transactions.findAll { it.stan == '" + stan + "' }.size()");
            org.junit.jupiter.api.Assertions.assertEquals(1, matches,
                    "Transaction " + stan + " not stored yet");
        });
    }
}