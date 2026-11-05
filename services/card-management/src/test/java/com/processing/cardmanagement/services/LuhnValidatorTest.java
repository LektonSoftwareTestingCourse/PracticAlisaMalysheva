package com.processing.cardmanagement.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class LuhnValidatorTest {

    private final LuhnValidator validator = new LuhnValidator();

    @ParameterizedTest
    @DisplayName("Если PAN равен 79927398713 или 4000008170058502, то алгоритм Луна признаёт его валидным")
    @ValueSource(strings = {
            "79927398713",
            "4000008170058502"
    })
    void shouldValidateCorrectPan(String pan) {
        assertThat(validator.isValid(pan)).isTrue();
    }

    @ParameterizedTest
    @DisplayName("Если PAN равен 79927398714, 4000001234567890 или 1234, то алгоритм Луна признаёт его невалидным")
    @ValueSource(strings = {
            "79927398714",
            "4000001234567890",
            "1234"
    })
    void shouldRejectInvalidPan(String pan) {
        assertThat(validator.isValid(pan)).isFalse();
    }

    @Test
    @DisplayName("Если генерируется PAN с BIN-префиксом 400000, то PAN начинается с 400000, имеет длину 16 и является валидным по"
            + " алгоритму Луна")
    void shouldGenerateValidPanForBin() {
        // Act
        String pan = validator.generatePan("400000");

        // Assert
        assertThat(pan).startsWith("400000");
        assertThat(pan).hasSize(16);
        assertThat(validator.isValid(pan)).isTrue();
    }
}