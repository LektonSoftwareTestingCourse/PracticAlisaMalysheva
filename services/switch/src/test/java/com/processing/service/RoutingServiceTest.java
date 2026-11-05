package com.processing.service;

import com.processing.config.SwitchProperties;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingServiceTest {

    private static final Map<String, String> BIN_TABLE = Map.of(
            "400000", "ISS001",
            "427600", "ISS002"
    );

    private final RoutingService routingService = new RoutingService(switchProperties());

    @Test
    @DisplayName("Если PAN начинается с известного BIN 400000, то getIssuerIdByPan возвращает ISS001, а если PAN начинается с "
        + "BIN 427600, то возвращает ISS002")
    void shouldReturnIssuerIdForKnownBin() {
        assertThat(routingService.getIssuerIdByPan("4000001234567890")).isEqualTo("ISS001");
        assertThat(routingService.getIssuerIdByPan("4276009999999999")).isEqualTo("ISS002");
    }

    @Test
    @DisplayName("Если PAN равен null, то getIssuerIdByPan бросает UnknownBinException")
    void shouldThrowWhenPanIsNull() {
        assertThatThrownBy(() -> routingService.getIssuerIdByPan(null))
                .isInstanceOf(UnknownBinException.class);
    }

    @Test
    @DisplayName("Если PAN короче 6 цифр (5 цифр), то getIssuerIdByPan бросает UnknownBinException")
    void shouldThrowWhenPanIsTooShort() {
        assertThatThrownBy(() -> routingService.getIssuerIdByPan("12345"))
                .isInstanceOf(UnknownBinException.class);
    }

    @Test
    @DisplayName("Если BIN PAN (111111) не найден в таблице маршрутизации, то getIssuerIdByPan бросает UnknownBinException")
    void shouldThrowWhenBinIsUnknown() {
        assertThatThrownBy(() -> routingService.getIssuerIdByPan("1111119999999999"))
                .isInstanceOf(UnknownBinException.class);
    }

    static SwitchProperties switchProperties() {
        return new SwitchProperties(
                "1.0",
                BIN_TABLE,
                "http://auth",
                "http://logger",
                "http://merchant-acquirer",
                new SwitchProperties.HttpProperties(1000, 2000, 2000),
                new SwitchProperties.RetryProperties(3, List.of(100L, 200L)),
                new SwitchProperties.CircuitBreakerSection(
                        new SwitchProperties.CircuitBreakerProperties(50, 10, 5, 10000))
        );
    }
}