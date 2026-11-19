package com.processing.apitests.tests;

import com.processing.apitests.support.Cards;
import com.processing.apitests.support.Json;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.cardmanagement.ReserveRequest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Резервирование и откат средств: POST /api/cards/{pan}/reserve и
 * POST /api/cards/{pan}/rollback (CardController).
 */
class CardsReserveApiTest {

    @Test
    void reserve_reducesBalance_returns200() {
        String pan = Cards.createCard(new BigDecimal("100000"),
                new BigDecimal("100000000"), new BigDecimal("100000"));
        long before = balanceOf(pan);

        Specs.gateway()
                .body(Json.of(new ReserveRequest(new BigDecimal("1000"), TestData.rrn())))
                .when().post("/api/cards/{pan}/reserve", pan)
                .then()
                .statusCode(200)
                .body("availableBalance", equalTo((int) (before - 1000)));

        // Сверяем, что изменение реально сохранилось
        long after = balanceOf(pan);
        org.junit.jupiter.api.Assertions.assertEquals(before - 1000, after);
    }

    @Test
    void reserve_insufficientFunds_returns402() {
        String pan = Cards.createCard(new BigDecimal("100000"),
                new BigDecimal("100000000"), new BigDecimal("100"));

        Specs.gateway()
                .body(Json.of(new ReserveRequest(new BigDecimal("1000"), TestData.rrn())))
                .when().post("/api/cards/{pan}/reserve", pan)
                .then()
                .statusCode(402)
                .body("error", notNullValue())
                .body("message", notNullValue());
    }

    @Test
    void reserve_duplicateRrn_returns409() {
        String pan = Cards.createCard();
        String rrn = TestData.rrn();

        Specs.gateway()
                .body(Json.of(new ReserveRequest(new BigDecimal("100"), rrn)))
                .when().post("/api/cards/{pan}/reserve", pan)
                .then()
                .statusCode(200);

        // Повторное резервирование с тем же RRN — конфликт (дедупликация)
        Specs.gateway()
                .body(Json.of(new ReserveRequest(new BigDecimal("100"), rrn)))
                .when().post("/api/cards/{pan}/reserve", pan)
                .then()
                .statusCode(409)
                .body("error", equalTo("ReservationAlreadyExistsException"));
    }

    @Test
    void reserve_invalidAmount_returns400() {
        String pan = Cards.createCard();

        // amount <= 0 нарушает @Positive
        Specs.gateway()
                .body(Json.of(new ReserveRequest(new BigDecimal("0"), TestData.rrn())))
                .when().post("/api/cards/{pan}/reserve", pan)
                .then()
                .statusCode(400)
                .body("error", equalTo("MethodArgumentNotValidException"));
    }

    @Test
    void rollback_restoresBalance_returns200() {
        String pan = Cards.createCard();
        String rrn = TestData.rrn();
        long before = balanceOf(pan);

        Specs.gateway()
                .body(Json.of(new ReserveRequest(new BigDecimal("1000"), rrn)))
                .when().post("/api/cards/{pan}/reserve", pan)
                .then()
                .statusCode(200);

        Specs.gateway()
                .body(Json.of(new RollbackRequest(rrn, pan, new BigDecimal("1000"))))
                .when().post("/api/cards/{pan}/rollback", pan)
                .then()
                .statusCode(200)
                .body("availableBalance", equalTo((int) before));
    }

    @Test
    void rollback_twice_returns409() {
        String pan = Cards.createCard();
        String rrn = TestData.rrn();

        Specs.gateway()
                .body(Json.of(new ReserveRequest(new BigDecimal("100"), rrn)))
                .when().post("/api/cards/{pan}/reserve", pan)
                .then()
                .statusCode(200);

        Specs.gateway()
                .body(Json.of(new RollbackRequest(rrn, pan, new BigDecimal("100"))))
                .when().post("/api/cards/{pan}/rollback", pan)
                .then()
                .statusCode(200);

        // Повторный откат уже удовлетворённого резервирования — конфликт
        Specs.gateway()
                .body(Json.of(new RollbackRequest(rrn, pan, new BigDecimal("100"))))
                .when().post("/api/cards/{pan}/rollback", pan)
                .then()
                .statusCode(409)
                .body("error", equalTo("RollbackAlreadySatisfiedException"));
    }

    @Test
    void rollback_notFound_returns404() {
        String pan = Cards.createCard();
        // RRN, для которого не было резервирования
        String rrn = TestData.rrn();

        Specs.gateway()
                .body(Json.of(new RollbackRequest(rrn, pan, new BigDecimal("100"))))
                .when().post("/api/cards/{pan}/rollback", pan)
                .then()
                .statusCode(404);
    }

    private long balanceOf(String pan) {
        JsonPath jsonPath = Specs.gateway()
                .when().get("/api/cards/{pan}", pan)
                .then().statusCode(200)
                .extract().jsonPath();
        return jsonPath.getLong("availableBalance");
    }
}