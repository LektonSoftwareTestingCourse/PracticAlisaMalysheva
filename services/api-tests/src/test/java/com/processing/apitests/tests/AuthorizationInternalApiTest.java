package com.processing.apitests.tests;

import com.processing.apitests.support.Cards;
import com.processing.apitests.support.Json;
import com.processing.apitests.support.Requests;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Внутренние эндпоинты Authorization: POST /api/internal/authorize и
 * POST /api/internal/rollback. В отличие от Gateway здесь каждый бизнес-отказ
 * маппится в отдельный HTTP-статус (AuthControllerImpl).
 * Источник: AuthControllerImpl + DeclineOutcome.
 */
class AuthorizationInternalApiTest {

    @Test
    void authorize_approved_returns200() {
        String pan = Cards.createCard();
        String stan = TestData.stan();

        Specs.authorization()
                .body(Json.of(Requests.authorize(pan, stan, new BigDecimal("1000"))))
                .when().post("/api/internal/authorize")
                .then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"))
                .body("rrn", notNullValue());
    }

    @Test
    void authorize_cardNotFound_returns404_code14() {
        String pan = TestData.pan(Requests.BIN);

        Specs.authorization()
                .body(Json.of(Requests.authorize(pan, TestData.stan(), new BigDecimal("1000"))))
                .when().post("/api/internal/authorize")
                .then()
                .statusCode(404)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("CARD_NOT_FOUND"));
    }

    @Test
    void authorize_insufficientFunds_returns422_code51() {
        String pan = Cards.createCard(new BigDecimal("15000000"),
                new BigDecimal("300000000"), new BigDecimal("100"));

        Specs.authorization()
                .body(Json.of(Requests.authorize(pan, TestData.stan(), new BigDecimal("1000"))))
                .when().post("/api/internal/authorize")
                .then()
                .statusCode(422)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("51"))
                .body("declineReason", equalTo("INSUFFICIENT_FUNDS"));
    }

    @Test
    void authorize_blockedCard_returns403_code05() {
        String pan = Cards.createCard();
        Specs.gateway()
                .body("{\"status\":\"BLOCKED\"}")
                .when().patch("/api/cards/{pan}", pan)
                .then().statusCode(200);

        Specs.authorization()
                .body(Json.of(Requests.authorize(pan, TestData.stan(), new BigDecimal("1000"))))
                .when().post("/api/internal/authorize")
                .then()
                .statusCode(403)
                .body("responseCode", equalTo("05"))
                .body("declineReason", equalTo("CARD_BLOCKED"));
    }

    @Test
    void authorize_expiredCard_returns403_code54() {
        String pan = Cards.createCard();
        Specs.gateway()
                .body("{\"status\":\"EXPIRED\"}")
                .when().patch("/api/cards/{pan}", pan)
                .then().statusCode(200);

        Specs.authorization()
                .body(Json.of(Requests.authorize(pan, TestData.stan(), new BigDecimal("1000"))))
                .when().post("/api/internal/authorize")
                .then()
                .statusCode(403)
                .body("responseCode", equalTo("54"))
                .body("declineReason", equalTo("CARD_EXPIRED"));
    }

    @Test
    void authorize_exceedsAmountLimit_returns400_code61() {
        String pan = Cards.createCard(new BigDecimal("100"),
                new BigDecimal("300000000"), new BigDecimal("100000"));

        Specs.authorization()
                .body(Json.of(Requests.authorize(pan, TestData.stan(), new BigDecimal("500"))))
                .when().post("/api/internal/authorize")
                .then()
                .statusCode(400)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("61"))
                .body("declineReason", equalTo("EXCEEDS_AMOUNT_LIMIT"));
    }

    @Test
    void rollback_success_returns200() {
        String pan = Cards.createCard();
        String rrn = reserve(pan);

        Specs.authorization()
                .body(Json.of(Requests.rollback(pan, rrn, new BigDecimal("100"))))
                .when().post("/api/internal/rollback")
                .then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"));
    }

    @Test
    void rollback_alreadyRolledBack_returns409() {
        String pan = Cards.createCard();
        String rrn = reserve(pan);

        Specs.authorization()
                .body(Json.of(Requests.rollback(pan, rrn, new BigDecimal("100"))))
                .when().post("/api/internal/rollback")
                .then().statusCode(200);

        Specs.authorization()
                .body(Json.of(Requests.rollback(pan, rrn, new BigDecimal("100"))))
                .when().post("/api/internal/rollback")
                .then()
                .statusCode(409)
                .body("status", equalTo("DECLINED"))
                .body("declineReason", equalTo("ALREADY_ROLLED_BACK"));
    }

    @Test
    void rollback_transactionNotFound_returns404() {
        String pan = Cards.createCard();
        String rrn = TestData.rrn();

        Specs.authorization()
                .body(Json.of(Requests.rollback(pan, rrn, new BigDecimal("100"))))
                .when().post("/api/internal/rollback")
                .then()
                .statusCode(404)
                .body("declineReason", equalTo("TRANSACTION_NOT_FOUND"));
    }

    /** Валидация AuthorizationRequest: каждый невалидный класс — отдельный запрос. */
    @ParameterizedTest(name = "authorize validation: {0}")
    @MethodSource("invalidAuthorizationBodies")
    void authorize_invalidField_returns400(String label, String body) {
        Specs.authorization()
                .body(body)
                .when().post("/api/internal/authorize")
                .then()
                .statusCode(400)
                .body(not(emptyOrNullString()));
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> invalidAuthorizationBodies() {
        String valid = "{\"mti\":\"0100\",\"stan\":\"000001\",\"pan\":\"4000001234560001\","
                + "\"processingCode\":\"000000\",\"amount\":1000,\"currencyCode\":\"643\","
                + "\"transmissionDateTime\":\"2026-01-01T10:00:00Z\",\"terminalId\":\"TERM0001\","
                + "\"merchantId\":\"MERCH0000000001\",\"mcc\":\"5411\",\"acquirerId\":\"ACQ001\",\"issuerId\":\"ISS001\"}";
        return Stream.of(
                arg("mti too short", valid.replace("\"mti\":\"0100\"", "\"mti\":\"010\"")),
                arg("stan too short", valid.replace("\"stan\":\"000001\"", "\"stan\":\"12345\"")),
                arg("pan too short", valid.replace("\"pan\":\"4000001234560001\"", "\"pan\":\"1234\"")),
                arg("processingCode short", valid.replace("\"processingCode\":\"000000\"", "\"processingCode\":\"00\"")),
                arg("currencyCode short", valid.replace("\"currencyCode\":\"643\"", "\"currencyCode\":\"64\"")),
                arg("terminalId short", valid.replace("\"terminalId\":\"TERM0001\"", "\"terminalId\":\"TERM\"")),
                arg("merchantId short", valid.replace("\"merchantId\":\"MERCH0000000001\"", "\"merchantId\":\"M\"")),
                arg("mcc short", valid.replace("\"mcc\":\"5411\"", "\"mcc\":\"54\"")),
                arg("amount negative", valid.replace("\"amount\":1000", "\"amount\":-5")),
                arg("mti missing", valid.replace("\"mti\":\"0100\",", "")),
                arg("acquirerId missing", valid.replace(",\"acquirerId\":\"ACQ001\"", "")));
    }

    private static org.junit.jupiter.params.provider.Arguments arg(String label, String body) {
        return org.junit.jupiter.params.provider.Arguments.of(label, body);
    }

    private String reserve(String pan) {
        String rrn = TestData.rrn();
        Specs.gateway()
                .body("{\"amount\":100,\"rrn\":\"" + rrn + "\"}")
                .when().post("/api/cards/{pan}/reserve", pan)
                .then().statusCode(200);
        return rrn;
    }
}