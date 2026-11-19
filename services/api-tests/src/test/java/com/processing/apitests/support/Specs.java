package com.processing.apitests.support;

import com.processing.apitests.config.ApiConfig;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

/**
 * Общие {@link RequestSpecification} для каждого сервиса. Базовый адрес и
 * заголовки задаются один раз, в каждом тесте не дублируются.
 */
public final class Specs {

    private static final RequestSpecification GATEWAY = base(ApiConfig.GATEWAY);
    private static final RequestSpecification CARD_MANAGEMENT = base(ApiConfig.CARD_MANAGEMENT);
    private static final RequestSpecification SWITCH = base(ApiConfig.SWITCH);
    private static final RequestSpecification AUTHORIZATION = base(ApiConfig.AUTHORIZATION);
    private static final RequestSpecification LOGGER = base(ApiConfig.LOGGER);
    private static final RequestSpecification NOTIFICATIONS = base(ApiConfig.NOTIFICATIONS);

    private Specs() {
    }

    private static RequestSpecification base(String baseUri) {
        return new RequestSpecBuilder()
                .setBaseUri(baseUri)
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .build();
    }

    public static RequestSpecification gateway() {
        return RestAssured.given().spec(GATEWAY);
    }

    public static RequestSpecification cardManagement() {
        return RestAssured.given().spec(CARD_MANAGEMENT);
    }

    public static RequestSpecification switchService() {
        return RestAssured.given().spec(SWITCH);
    }

    public static RequestSpecification authorization() {
        return RestAssured.given().spec(AUTHORIZATION);
    }

    public static RequestSpecification logger() {
        return RestAssured.given().spec(LOGGER);
    }

    public static RequestSpecification notifications() {
        return RestAssured.given().spec(NOTIFICATIONS);
    }
}
