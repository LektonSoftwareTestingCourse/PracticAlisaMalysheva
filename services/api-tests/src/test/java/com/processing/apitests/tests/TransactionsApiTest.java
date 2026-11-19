package com.processing.apitests.tests;

import com.processing.apitests.support.Cards;
import com.processing.apitests.support.Json;
import com.processing.apitests.support.Requests;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * POST /api/transactions через Gateway (Switch → Authorization).
 *
 * <p>Важно: через Gateway ВСЕ бизнес-отказы возвращаются HTTP 200, а исход
 * закодирован в теле — в {@code responseCode} и {@code declineReason}
 * (RouteController всегда возвращает 200). Поэтому здесь проверяется
 * совместно код и причина, а не только статус-код.
 */
class TransactionsApiTest {

    @Test
    void authorize_approvedCard_returns200_approved() {
        String pan = Cards.createCard();
        String stan = TestData.stan();

        Specs.gateway()
                .body(Json.of(Requests.authorize(pan, stan, new BigDecimal("1000"))))
                .when().post("/api/transactions")
                .then()
                .statusCode(200)
                // Расхождение с api-spec.md: спецификация декларирует ответный mti "0110",
                // реализация возвращает request.mti() = "0100" (AuthorizationResponse.approved).
                .body("mti", equalTo("0100"))
                .body("stan", equalTo(stan))
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"))
                // генерируемые значения проверяем по формату, а не по точному значению
                .body("rrn", matchesPattern("\\d{12}"))
                .body("authCode", matchesPattern("[A-Z0-9]{6}"))
                .body("processingTimeMs", notNullValue())
                .body("declineReason", nullValue());
    }

    @Test
    void authorize_insufficientFunds_returnsDeclined51() {
        String pan = Cards.createCard(new BigDecimal("15000000"),
                new BigDecimal("300000000"), new BigDecimal("100"));
        String stan = TestData.stan();

        Specs.gateway()
                .body(Json.of(Requests.authorize(pan, stan, new BigDecimal("1000"))))
                .when().post("/api/transactions")
                .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("51"))
                .body("declineReason", equalTo("INSUFFICIENT_FUNDS"))
                .body("rrn", nullValue());
    }

    @Test
    void authorize_blockedCard_returnsDeclined05() {
        String pan = Cards.createCard();
        block(pan);
        String stan = TestData.stan();

        Specs.gateway()
                .body(Json.of(Requests.authorize(pan, stan, new BigDecimal("1000"))))
                .when().post("/api/transactions")
                .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("05"))
                .body("declineReason", equalTo("CARD_BLOCKED"));
    }

    @Test
    void authorize_inactiveCard_returnsDeclined05() {
        String pan = Cards.createCard();
        setStatus(pan, "INACTIVE");
        String stan = TestData.stan();

        Specs.gateway()
                .body(Json.of(Requests.authorize(pan, stan, new BigDecimal("1000"))))
                .when().post("/api/transactions")
                .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("05"))
                .body("declineReason", equalTo("CARD_INACTIVE"));
    }

    @Test
    void authorize_expiredCard_returnsDeclined54() {
        String pan = Cards.createCard();
        setStatus(pan, "EXPIRED");
        String stan = TestData.stan();

        Specs.gateway()
                .body(Json.of(Requests.authorize(pan, stan, new BigDecimal("1000"))))
                .when().post("/api/transactions")
                .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("54"))
                .body("declineReason", equalTo("CARD_EXPIRED"));
    }

    @Test
    void authorize_cardNotFound_returnsDeclined14() {
        // Валидный по формату PAN, но карта не создана
        String pan = TestData.pan(Requests.BIN);
        String stan = TestData.stan();

        Specs.gateway()
                .body(Json.of(Requests.authorize(pan, stan, new BigDecimal("1000"))))
                .when().post("/api/transactions")
                .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("CARD_NOT_FOUND"));
    }

    @Test
    void authorize_exceedsAmountLimit_returnsDeclined61() {
        // balance 100000 > amount 500, но dailyLimit 100 < amount → превышение лимита
        String pan = Cards.createCard(new BigDecimal("100"),
                new BigDecimal("300000000"), new BigDecimal("100000"));
        String stan = TestData.stan();

        Specs.gateway()
                .body(Json.of(Requests.authorize(pan, stan, new BigDecimal("500"))))
                .when().post("/api/transactions")
                .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("61"))
                .body("declineReason", equalTo("EXCEEDS_AMOUNT_LIMIT"));
    }

    @Test
    void authorize_unknownBin_returnsDeclined14() {
        // BIN 999999 не маршрутизируется Switch → unknownBin (код 14)
        String pan = Cards.unknownBinPan();
        String stan = TestData.stan();

        Specs.gateway()
                .body(Json.of(Requests.authorize(pan, stan, new BigDecimal("1000"))))
                .when().post("/api/transactions")
                .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("CARD_NOT_FOUND"));
    }

    private void block(String pan) {
        Specs.gateway()
                .body("{\"status\":\"BLOCKED\"}")
                .when().patch("/api/cards/{pan}", pan)
                .then().statusCode(200);
    }

    private void setStatus(String pan, String status) {
        Specs.gateway()
                .body("{\"status\":\"" + status + "\"}")
                .when().patch("/api/cards/{pan}", pan)
                .then().statusCode(200);
    }
}
