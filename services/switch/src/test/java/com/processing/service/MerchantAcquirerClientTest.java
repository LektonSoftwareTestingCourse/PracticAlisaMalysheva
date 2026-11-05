package com.processing.service;

import com.processing.config.SwitchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestBodyUriSpec;
import org.springframework.web.client.RestClient.ResponseSpec;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MerchantAcquirerClientTest {

    private RestClient restClient;
    private RequestBodyUriSpec spec;
    private ResponseSpec responseSpec;
    private MerchantAcquirerClient client;

    @BeforeEach
    void setUp() {
        restClient = mock(RestClient.class);
        spec = mock(RequestBodyUriSpec.class);
        responseSpec = mock(ResponseSpec.class);

        when(restClient.post()).thenReturn(spec);
        when(spec.uri(anyString())).thenReturn(spec);
        when(spec.body(any(Object.class))).thenReturn(spec);
        when(spec.retrieve()).thenReturn(responseSpec);

        SwitchProperties properties = new SwitchProperties(
                "1.0",
                Map.of("400000", "ISS001"),
                "http://auth",
                "http://logger",
                "http://merchant-acquirer",
                new SwitchProperties.HttpProperties(1000, 2000, 2000),
                new SwitchProperties.RetryProperties(2, List.of(1L)),
                new SwitchProperties.CircuitBreakerSection(
                        new SwitchProperties.CircuitBreakerProperties(50, 5, 5, 10000))
        );
        client = new MerchantAcquirerClient(properties, restClient);
    }

    @Test
    @DisplayName("Если сервис комиссии не вернул тело (body равен null), то fetchAcquiringFee возвращает null")
    void shouldReturnNullWhenFeeBodyMissing() {
        when(responseSpec.body(any(Class.class))).thenReturn(null);

        BigDecimal fee = client.fetchAcquiringFee(Instant.now(), "000001", "4000001234567890", "TERM0001",
                new BigDecimal("1000"));

        assertThat(fee).isNull();
    }

    @Test
    @DisplayName("Если вызов post бросает RuntimeException (сервис комиссии недоступен), то fetchAcquiringFee возвращает null")
    void shouldReturnNullWhenFeeServiceUnavailable() {
        when(restClient.post()).thenThrow(new RuntimeException("down"));

        BigDecimal fee = client.fetchAcquiringFee(Instant.now(), "000001", "4000001234567890", "TERM0001",
                new BigDecimal("1000"));

        assertThat(fee).isNull();
    }
}