package com.processing.gateway.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogDataMaskerTest {

    private final LogDataMasker masker = new LogDataMasker();

    @Test
    @DisplayName("Если на входе JSON с полем pan=\"4000001234567890\", то результат равен "
            + "\"{\"pan\":\"400000****7890\"}\" (PAN маскируется)")
    void shouldMaskPan() {
        String masked = masker.maskData("{\"pan\":\"4000001234567890\"}");

        assertThat(masked).isEqualTo("{\"pan\":\"400000****7890\"}");
    }

    @Test
    @DisplayName("Если на входе JSON с полем cvv=\"123\", то результат равен "
            + "\"{\"cvv\":\"***\"}\" (CVV полностью маскируется)")
    void shouldMaskCvv() {
        String masked = masker.maskData("{\"cvv\":\"123\"}");

        assertThat(masked).isEqualTo("{\"cvv\":\"***\"}");
    }

    @Test
    @DisplayName("Если на входе JSON содержит одновременно "
            + "pan=\"4000001234567890\" и cvv=\"999\", то результат содержит "
            + "и замаскированный PAN (400000****7890), "
            + "и замаскированный CVV (***)")
    void shouldMaskPanAndCvvTogether() {
        String masked = masker.maskData("{\"pan\":\"4000001234567890\",\"cvv\":\"999\"}");

        assertThat(masked)
                .contains("\"pan\":\"400000****7890\"")
                .contains("\"cvv\":\"***\"");
    }

    @Test
    @DisplayName("Если ключ задан в верхнем регистре (\"PAN\"), то он распознаётся "
            + "без учёта регистра и результат содержит замаскированный "
            + "\"pan\":\"400000****7890\"")
    void shouldMaskUppercasePanKey() {
        String masked = masker.maskData("{\"PAN\":\"4000001234567890\"}");

        assertThat(masked).contains("\"pan\":\"400000****7890\"");
    }

    @Test
    @DisplayName("Если входные данные не содержат полей PAN и CVV (например, "
            + "\"{\"mti\":\"0100\",\"stan\":\"000001\"}\"), то результат равен "
            + "исходной строке без изменений")
    void shouldLeaveDataWithoutSensitiveFieldsUntouched() {
        String input = "{\"mti\":\"0100\",\"stan\":\"000001\"}";

        assertThat(masker.maskData(input)).isEqualTo(input);
    }
}
