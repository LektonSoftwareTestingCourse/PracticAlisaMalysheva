package com.processing.apitests.tests;

import com.processing.apitests.support.Specs;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Правила валидации Card Management (CreateCardRequest, ReserveRequest,
 * GenerateCardsRequest). Для каждого нарушения проверяется не только
 * статус-код, но и тело ошибки, из которого видно нарушенное поле.
 * Источник: аннотации валидации в {@code common}.
 */
class CardsValidationApiTest {

    /** Один невалидный класс — одно тело запроса; локализуем поле по message. */
    @ParameterizedTest(name = "createCard invalid: {0}")
    @MethodSource("invalidCreateCardBodies")
    void createCard_invalidField_returns400(String label, String body, String messageToken) {
        Specs.gateway()
                .body(body)
                .when().post("/api/cards")
                .then()
                .statusCode(400)
                .body("message", notNullValue())
                .body("message", containsString(messageToken));
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> invalidCreateCardBodies() {
        return Stream.of(
                // bin: длина не 6 — нарушение @Bin
                org.junit.jupiter.params.provider.Arguments.of(
                        "bin short",
                        "{\"bin\":\"12345\",\"cardholderName\":\"IVAN IVANOV\",\"currencyCode\":\"643\","
                                + "\"dailyLimit\":100,\"monthlyLimit\":200,\"initialBalance\":100}",
                        "BIN"),
                // bin содержит буквы
                org.junit.jupiter.params.provider.Arguments.of(
                        "bin letters",
                        "{\"bin\":\"ABCDEF\",\"cardholderName\":\"IVAN IVANOV\",\"currencyCode\":\"643\","
                                + "\"dailyLimit\":100,\"monthlyLimit\":200,\"initialBalance\":100}",
                        "BIN"),
                // cardholderName: строчные буквы запрещены
                org.junit.jupiter.params.provider.Arguments.of(
                        "cardholder lowercase",
                        "{\"bin\":\"400000\",\"cardholderName\":\"ivan ivanov\",\"currencyCode\":\"643\","
                                + "\"dailyLimit\":100,\"monthlyLimit\":200,\"initialBalance\":100}",
                        "uppercase"),
                // currencyCode: не 3 цифры (нарушение @ExactSize(3) → "length")
                org.junit.jupiter.params.provider.Arguments.of(
                        "currency length",
                        "{\"bin\":\"400000\",\"cardholderName\":\"IVAN IVANOV\",\"currencyCode\":\"64\","
                                + "\"dailyLimit\":100,\"monthlyLimit\":200,\"initialBalance\":100}",
                        "length"),
                // dailyLimit отрицательный (нарушение @NotNegative → "negative")
                org.junit.jupiter.params.provider.Arguments.of(
                        "daily limit negative",
                        "{\"bin\":\"400000\",\"cardholderName\":\"IVAN IVANOV\",\"currencyCode\":\"643\","
                                + "\"dailyLimit\":-1,\"monthlyLimit\":200,\"initialBalance\":100}",
                        "negative"));
    }

    /** Граничные значения длины PAN для GET /api/cards/{pan}. */
    @ParameterizedTest(name = "getCard boundary pan={0} -> 400")
    @ValueSource(strings = {"12345678901234", "123456789012345", "123456789012345678"})
    void getCard_panBoundary_returns400(String invalidPan) {
        Specs.gateway()
                .when().get("/api/cards/{pan}", invalidPan)
                .then()
                .statusCode(400)
                .body("error", notNullValue());
    }

    /** count границы в POST /api/cards/generate. */
    @ParameterizedTest(name = "generate count={0} -> 400")
    @ValueSource(ints = {0, -5})
    void generate_countBoundary_returns400(int count) {
        Specs.gateway()
                .body("{\"count\":" + count + ",\"bins\":[\"400000\"]}")
                .when().post("/api/cards/generate")
                .then()
                .statusCode(400)
                .body("message", notNullValue());
    }

    /** bins пустой в POST /api/cards/generate. */
    @ParameterizedTest(name = "generate bins empty -> 400")
    @ValueSource(strings = {"[]"})
    void generate_emptyBins_returns400(String bins) {
        Specs.gateway()
                .body("{\"count\":5,\"bins\":" + bins + "}")
                .when().post("/api/cards/generate")
                .then()
                .statusCode(400)
                .body("message", notNullValue());
    }

    /** rrn нарушает формат 12 цифр в reserve. */
    @ParameterizedTest(name = "reserve rrn={0} -> 400")
    @CsvSource({"12345", "abcdefghijkl", "1234567890123"})
    void reserve_invalidRrn_returns400(String invalidRrn) {
        Specs.gateway()
                .body("{\"amount\":100,\"rrn\":\"" + invalidRrn + "\"}")
                .when().post("/api/cards/4000001234560001/reserve")
                .then()
                .statusCode(400)
                .body("message", notNullValue());
    }
}
