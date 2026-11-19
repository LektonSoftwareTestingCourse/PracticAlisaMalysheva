package com.processing.apitests.support;

import io.restassured.path.json.JsonPath;

import java.math.BigDecimal;

import static com.processing.apitests.support.Requests.BIN;

/**
 * Support-слой: подготовка данных через API (создание карт) и хелперы поиска.
 * Состояние системы меняется только HTTP-запросами, как требует контракт.
 */
public final class Cards {

    private static final BigDecimal DEFAULT_DAILY = new BigDecimal("15000000");
    private static final BigDecimal DEFAULT_MONTHLY = new BigDecimal("300000000");
    private static final BigDecimal DEFAULT_BALANCE = new BigDecimal("100000000");

    private Cards() {
    }

    /** Создаёт карту с балансом и лимитами по умолчанию, возвращает PAN. */
    public static String createCard() {
        return createCard(DEFAULT_DAILY, DEFAULT_MONTHLY, DEFAULT_BALANCE);
    }

    public static String createCard(BigDecimal dailyLimit,
                                    BigDecimal monthlyLimit,
                                    BigDecimal initialBalance) {
        return Specs.gateway()
                .body(Json.of(Requests.createCard(dailyLimit, monthlyLimit, initialBalance)))
                .when().post("/api/cards")
                .then().statusCode(201)
                .extract().jsonPath().getString("pan");
    }

    public static JsonPath byPan(String pan) {
        return Specs.gateway()
                .queryParam("pan", pan)
                .when().get("/api/transactions/search")
                .then().statusCode(200)
                .extract().jsonPath();
    }

    /** PAN с «неизвестным» BIN, который не маршрутизируется Switch. */
    public static String unknownBinPan() {
        return TestData.pan("999999");
    }

    /** Значение BIN, используемое по умолчанию во всех фикстурах. */
    public static String defaultBin() {
        return BIN;
    }
}
