package com.processing.apitests.config;

public final class ApiConfig {

    public static final String GATEWAY = env("GATEWAY_URL", "http://localhost:8080");
    public static final String CARD_MANAGEMENT = env("CARD_MGMT_URL", "http://localhost:8081");
    public static final String SWITCH = env("SWITCH_URL", "http://localhost:8082");
    public static final String AUTHORIZATION = env("AUTH_URL", "http://localhost:8083");
    public static final String LOGGER = env("LOGGER_URL", "http://localhost:8088");
    public static final String NOTIFICATIONS = env("NOTIFICATION_URL", "http://localhost:8097");

    private ApiConfig() {
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
