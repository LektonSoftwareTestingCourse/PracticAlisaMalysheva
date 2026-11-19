package com.processing.apitests.tests;

import com.processing.apitests.support.Json;
import com.processing.apitests.support.Requests;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;

/**
 * Повторная доставка сообщений: POST /api/internal/log (Transaction Logger).
 * Идемпотентность по {@code id}: новая запись -> 201, та же запись -> 200,
 * совпадение ключа при расхождении данных -> 409.
 * Источник: InternalTransactionController + TransactionService.
 */
class IdempotencyApiTest {

    @Test
    void store_newTransaction_returns201() {
        UUID id = UUID.randomUUID();
        String stan = TestData.stan();

        Specs.logger()
                .body(Json.of(transaction(id, stan, "100", "APPROVED")))
                .when().post("/api/internal/log")
                .then()
                .statusCode(201)
                .body("id", equalTo(id.toString()))
                .body("status", notNullValue());
    }

    @Test
    void store_duplicateSameData_returns200() {
        UUID id = UUID.randomUUID();
        String stan = TestData.stan();
        String body = Json.of(transaction(id, stan, "100", "APPROVED"));

        Specs.logger().body(body).when().post("/api/internal/log").then().statusCode(201);

        // Повторная доставка с теми же данными — идемпотентно, без дубликата
        Specs.logger()
                .body(body)
                .when().post("/api/internal/log")
                .then()
                .statusCode(200)
                .body("id", equalTo(id.toString()));
    }

    @Test
    void store_sameIdDifferentData_returns409() {
        UUID id = UUID.randomUUID();
        String stan = TestData.stan();

        Specs.logger()
                .body(Json.of(transaction(id, stan, "100", "APPROVED")))
                .when().post("/api/internal/log")
                .then().statusCode(201);

        // Тот же id, но другие данные (сумма) — конфликт дедупликации
        Specs.logger()
                .body(Json.of(transaction(id, stan, "999", "APPROVED")))
                .when().post("/api/internal/log")
                .then()
                .statusCode(409)
                .body("message", notNullValue());
    }

    /** Валидация TransactionRequest: каждый невалидный класс — отдельный тест. */
    @ParameterizedTest(name = "store validation: {0}")
    @MethodSource("invalidTransactionBodies")
    void store_invalidBody_returns400(String label, String body) {
        Specs.logger()
                .body(body)
                .when().post("/api/internal/log")
                .then()
                .statusCode(400)
                .body(not(emptyOrNullString()));
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> invalidTransactionBodies() {
        UUID id = UUID.randomUUID();
        String valid = "{\"id\":\"" + id + "\",\"mti\":\"0100\",\"stan\":\"000001\","
                + "\"rrn\":\"012345678901\",\"pan\":\"4000001234560001\",\"processingCode\":\"000000\","
                + "\"amount\":100,\"currencyCode\":\"643\",\"terminalId\":\"TERM0001\","
                + "\"merchantId\":\"MERCH000000001\",\"mcc\":\"5411\",\"acquirerId\":\"ACQ001\","
                + "\"issuerId\":\"ISS001\",\"status\":\"APPROVED\","
                + "\"transmissionDateTime\":\"2026-01-01T10:00:00Z\",\"createdAt\":\"2026-01-01T10:00:00Z\"}";
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        "amount zero", valid.replace("\"amount\":100", "\"amount\":0")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "amount negative", valid.replace("\"amount\":100", "\"amount\":-5")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "stan not digits", valid.replace("\"stan\":\"000001\"", "\"stan\":\"abcdef\"")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "pan short", valid.replace("\"pan\":\"4000001234560001\"", "\"pan\":\"1234\"")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "mcc short", valid.replace("\"mcc\":\"5411\"", "\"mcc\":\"54\"")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "id missing", valid.replace("\"id\":\"" + id + "\",", "")));
    }

    private static TransactionRequest transaction(UUID id, String stan, String amount, String status) {
        return new TransactionRequest(
                id,
                "0100",
                stan,
                TestData.rrn(),
                TestData.pan(Requests.BIN),
                "000000",
                new BigDecimal(amount),
                "643",
                TestData.terminalId(),
                "POS",
                TestData.merchantId(),
                "5411",
                "ACQ001",
                "ISS001",
                new BigDecimal("0"),
                TransactionStatus.valueOf(status),
                null,
                "ABC123",
                42,
                Instant.parse("2026-01-01T10:00:00Z"),
                Instant.now());
    }
}