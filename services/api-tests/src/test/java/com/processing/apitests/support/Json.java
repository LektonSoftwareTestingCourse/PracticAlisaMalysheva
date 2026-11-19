package com.processing.apitests.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Сериализация тел запросов в JSON. Один настроенный {@link ObjectMapper} на
 * весь набор: регистрируются модули JSR-310 (Instant) и включено строгое
 * поведение для record-типов модуля {@code common}.
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private Json() {
    }

    public static String of(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize request body: " + value, e);
        }
    }
}