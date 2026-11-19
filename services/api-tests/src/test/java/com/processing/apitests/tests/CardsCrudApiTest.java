package com.processing.apitests.tests;

import com.processing.apitests.support.Cards;
import com.processing.apitests.support.Json;
import com.processing.apitests.support.Requests;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import com.processing.common.dto.cardmanagement.CreateCardRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;

/**
 * CRUD карт: POST /api/cards, GET /api/cards/{pan}, GET /api/cards,
 * PATCH, DELETE, POST /api/cards/generate, POST /api/cards/bulk-update.
 * Источник ожидания: api-spec.md (раздел Cards) + CardController.
 */
class CardsCrudApiTest {

    @Test
    void createCard_returns201_andContractBody() {
        // given: тело запроса по контракту
        CreateCardRequest request = Requests.createCard(
                new BigDecimal("15000000"),
                new BigDecimal("300000000"),
                new BigDecimal("100000000"));

        // when
        Specs.gateway()
                .body(Json.of(request))
                .when().post("/api/cards")
                // then: проверка тела, а не только статуса
                .then()
                .statusCode(201)
                .header("Content-Type", containsString("application/json"))
                .body(matchesJsonSchemaInClasspath("schemas/card-schema.json"))
                .body("status", equalTo("ACTIVE"))
                .body("bin", equalTo(Requests.BIN))
                .body("currencyCode", equalTo(Requests.CURRENCY))
                .body("cardholderName", equalTo("IVAN IVANOV"))
                .body("pan", matchesPattern("\\d{16}"))
                .body("availableBalance", equalTo(100000000))
                .body("dailyLimit", equalTo(15000000))
                .body("monthlyLimit", equalTo(300000000))
                .body("id", notNullValue())
                .body("issuerId", notNullValue())
                .body("createdAt", notNullValue());
    }

    @Test
    void getCard_returns200_matchesCreated() {
        String pan = Cards.createCard();

        Specs.gateway()
                .when().get("/api/cards/{pan}", pan)
                .then()
                .statusCode(200)
                .body("pan", equalTo(pan))
                .body("status", equalTo("ACTIVE"))
                .body("id", notNullValue());
    }

    @Test
    void getCard_returnsCacheHeader() {
        // Gateway кеширует ответы /api/cards/** (ResponseCaching) и помечает
        // результат заголовком X-Cache (MISS при первом обращении)
        String pan = Cards.createCard();

        Specs.gateway()
                .when().get("/api/cards/{pan}", pan)
                .then()
                .statusCode(200)
                .header("X-Cache", is("MISS"));
    }

    @Test
    void getCard_invalidPan_returns400() {
        // PAN короче 16 цифр — нарушение правила @Pan
        String invalidPan = "123456789012345";

        Specs.gateway()
                .when().get("/api/cards/{pan}", invalidPan)
                .then()
                .statusCode(400)
                .body("error", notNullValue())
                .body("message", notNullValue());
    }

    @Test
    void getCard_notFound_returns404() {
        // Валидный по формату PAN, но карта не создана
        String missingPan = TestData.pan(Requests.BIN);

        Specs.gateway()
                .when().get("/api/cards/{pan}", missingPan)
                .then()
                .statusCode(404)
                .body("error", equalTo("CardNotFoundException"));
    }

    @Test
    void listCards_filtersByBinAndStatus() {
        Cards.createCard();

        Specs.gateway()
                .queryParam("bin", Requests.BIN)
                .queryParam("status", "ACTIVE")
                .when().get("/api/cards")
                .then()
                .statusCode(200)
                .body("total", greaterThanOrEqualTo(1))
                .body("cards", notNullValue());
    }

    @Test
    void listCards_paginationLimitsResultSize() {
        Cards.createCard();
        Cards.createCard();

        Specs.gateway()
                .queryParam("limit", 1)
                .queryParam("offset", 0)
                .when().get("/api/cards")
                .then()
                .statusCode(200)
                .body("cards", hasSize(1));
    }

    @Test
    void listCards_emptyResult_returnsTotalZero() {
        // BIN не задействован в тестах — выборка гарантированно пустая
        Specs.gateway()
                .queryParam("bin", "999999")
                .queryParam("status", "ACTIVE")
                .when().get("/api/cards")
                .then()
                .statusCode(200)
                .body("total", equalTo(0))
                .body("cards", hasSize(0));
    }

    @Test
    void listCards_invalidLimit_returns400() {
        // limit <= 0 нарушает @Positive
        Specs.gateway()
                .queryParam("limit", 0)
                .when().get("/api/cards")
                .then()
                .statusCode(400)
                .body("error", notNullValue())
                .body("message", notNullValue());
    }

    @Test
    void patchCard_changesStatus_returns200() {
        String pan = Cards.createCard();
        String body = "{\"status\":\"BLOCKED\"}";

        Specs.gateway()
                .body(body)
                .when().patch("/api/cards/{pan}", pan)
                .then()
                .statusCode(200)
                .body("status", equalTo("BLOCKED"))
                .body("pan", equalTo(pan));
    }

    @Test
    void patchCard_notFound_returns404() {
        String missingPan = TestData.pan(Requests.BIN);
        String body = "{\"status\":\"BLOCKED\"}";

        Specs.gateway()
                .body(body)
                .when().patch("/api/cards/{pan}", missingPan)
                .then()
                .statusCode(404)
                .body("error", equalTo("CardNotFoundException"));
    }

    @Test
    void deleteCard_returns204_thenGet404() {
        String pan = Cards.createCard();

        // when: удаление (мягкое — статус DELETED)
        Specs.gateway()
                .when().delete("/api/cards/{pan}", pan)
                .then()
                .statusCode(204);

        // then: карта больше не доступна по PAN
        Specs.gateway()
                .when().get("/api/cards/{pan}", pan)
                .then()
                .statusCode(404);
    }

    @Test
    void generate_returns201_withRequestedCount() {
        Specs.gateway()
                .body("{\"count\":5,\"bins\":[\"400000\"]}")
                .when().post("/api/cards/generate")
                .then()
                .statusCode(201)
                .body("generated", equalTo(5))
                .body("cards", hasSize(5));
    }

    @Test
    void generate_invalidCount_returns400() {
        // count < 1 нарушает @Min(1)
        Specs.gateway()
                .body("{\"count\":0,\"bins\":[\"400000\"]}")
                .when().post("/api/cards/generate")
                .then()
                .statusCode(400)
                .body("message", notNullValue());
    }

    @Test
    void bulkUpdate_returns200_withCount() {
        Specs.gateway()
                .body("{\"bins\":[\"400000\"],\"status\":\"ACTIVE\"}")
                .when().post("/api/cards/bulk-update")
                .then()
                .statusCode(200)
                .body("updated", greaterThanOrEqualTo(0));
    }
}