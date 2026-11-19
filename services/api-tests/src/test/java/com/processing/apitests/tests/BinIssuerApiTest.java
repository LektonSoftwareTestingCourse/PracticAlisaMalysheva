package com.processing.apitests.tests;

import com.processing.apitests.support.Json;
import com.processing.apitests.support.Specs;
import com.processing.common.dto.cardmanagement.CreateBinIssuerRequest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * BIN → Issuer mapping (BinIssuerController).
 *
 * <p>Замечание: эндпоинты {@code /api/bins} не маршрутизируются Gateway
 * (маршрут есть только на {@code /api/cards/**}), поэтому тестируются
 * напрямую против Card Management. Это зафиксировано в отчёте как расхождение
 * между контроллером и конфигурацией маршрутов Gateway.
 */
class BinIssuerApiTest {

    @Test
    void getBins_returns200_includesSeededBin() {
        Specs.cardManagement()
                .when().get("/api/bins")
                .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(1))
                .body("find { it.bin == '400000' }.issuerId", equalTo("ISS001"));
    }

    @Test
    void getBin_returns200_mapping() {
        Specs.cardManagement()
                .when().get("/api/bins/{bin}", "400000")
                .then()
                .statusCode(200)
                .body("bin", equalTo("400000"))
                .body("issuerId", equalTo("ISS001"));
    }

    @Test
    void getBin_notFound_returns404() {
        Specs.cardManagement()
                .when().get("/api/bins/{bin}", "999999")
                .then()
                .statusCode(404)
                .body("error", equalTo("BinNotFoundException"));
    }

    @Test
    void createBin_returns201() {
        String bin = "400010";

        Specs.cardManagement()
                .body(Json.of(new CreateBinIssuerRequest(bin, "ISS999")))
                .when().post("/api/bins")
                .then()
                .statusCode(201)
                .body("bin", equalTo(bin))
                .body("issuerId", equalTo("ISS999"));
    }

    @Test
    void createBin_duplicate_returns409() {
        // BIN 400000 уже существует в seed-данных
        Specs.cardManagement()
                .body(Json.of(new CreateBinIssuerRequest("400000", "ISS111")))
                .when().post("/api/bins")
                .then()
                .statusCode(409)
                .body("error", equalTo("BinAlreadyExistException"));
    }

    @Test
    void deleteBin_returns204() {
        String bin = "400011";
        Specs.cardManagement()
                .body(Json.of(new CreateBinIssuerRequest(bin, "ISS111")))
                .when().post("/api/bins")
                .then().statusCode(201);

        Specs.cardManagement()
                .when().delete("/api/bins/{bin}", bin)
                .then()
                .statusCode(204);
    }
}