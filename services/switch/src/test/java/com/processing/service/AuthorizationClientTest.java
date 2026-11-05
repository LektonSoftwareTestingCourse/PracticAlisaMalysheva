package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.config.CircuitBreakerFactory;
import com.processing.config.RetryFactory;
import com.processing.config.SwitchProperties;
import com.processing.exception.AuthorizationException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestBodyUriSpec;
import org.springframework.web.client.RestClient.RequestHeadersUriSpec;
import org.springframework.web.client.RestClient.ResponseSpec;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthorizationClientTest {

    private static final String PAN = "4000001234567890";

    private RestClient restClient;
    private RequestBodyUriSpec spec;
    private ResponseSpec responseSpec;
    private RequestHeadersUriSpec getSpec;
    private AuthorizationClient client;
    private SwitchProperties properties;

    @BeforeEach
    void setUp() {
        restClient = mock(RestClient.class);
        spec = mock(RequestBodyUriSpec.class);
        responseSpec = mock(ResponseSpec.class);
        getSpec = mock(RequestHeadersUriSpec.class);

        when(restClient.post()).thenReturn(spec);
        when(spec.uri(anyString())).thenReturn(spec);
        when(spec.body(any(Object.class))).thenReturn(spec);
        when(spec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
        when(restClient.get()).thenReturn(getSpec);
        when(getSpec.uri(anyString())).thenReturn(getSpec);
        when(getSpec.retrieve()).thenReturn(responseSpec);

        properties = new SwitchProperties(
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
        Retry retry = RetryFactory.authorizationRetry(properties);
        CircuitBreaker circuitBreaker = CircuitBreakerFactory.authorizationCircuitBreaker(properties);
        client = new AuthorizationClient(properties, restClient, retry, circuitBreaker);
    }

    @Test
    @DisplayName("Если authorize возвращает ответ со статусом APPROVED, то client.authorize возвращает response со status "
        + "APPROVED")
    void shouldReturnResponseOnAuthorize() {
        // Arrange
        AuthorizationRequest request = validRequest();
        AuthorizationResponse approved = AuthorizationResponse.approved(
                request, "012345678901", "TEST01", Instant.now());
        when(responseSpec.body(AuthorizationResponse.class)).thenReturn(approved);

        // Act
        AuthorizationResponse response = client.authorize(request);

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("Если authorize возвращает некорректный ответ (status UNKNOWN, responseCode 99), то client.authorize бросает "
        + "AuthorizationException")
    void shouldThrowWhenAuthorizeReturnsInvalidResponse() {
        // Arrange
        AuthorizationRequest request = validRequest();
        when(responseSpec.body(AuthorizationResponse.class))
                .thenReturn(new AuthorizationResponse("0110", request.stan(), null, null, "99", "UNKNOWN", null, 0));

        // Act + Assert
        assertThatThrownBy(() -> client.authorize(request))
                .isInstanceOf(AuthorizationException.class);
    }

    @Test
    @DisplayName("Если circuit breaker открыт, то client.authorize бросает AuthorizationException")
    void shouldThrowWhenCircuitBreakerIsOpen() {
        // Arrange
        AuthorizationRequest request = validRequest();
        // контур с minimumNumberOfCalls=1 открывается после одной ошибки (failure rate 100%)
        SwitchProperties cbProps = new SwitchProperties(
                "1.0",
                Map.of("400000", "ISS001"),
                "http://auth",
                "http://logger",
                "http://merchant-acquirer",
                new SwitchProperties.HttpProperties(1000, 2000, 2000),
                new SwitchProperties.RetryProperties(2, List.of(1L)),
                new SwitchProperties.CircuitBreakerSection(
                        new SwitchProperties.CircuitBreakerProperties(50, 1, 1, 10000))
        );
        CircuitBreaker cb = CircuitBreakerFactory.authorizationCircuitBreaker(cbProps);
        cb.onError(System.nanoTime(), java.util.concurrent.TimeUnit.NANOSECONDS, new RuntimeException("down"));
        Retry retry = RetryFactory.authorizationRetry(properties);
        AuthorizationClient clientWithOpenBreaker = new AuthorizationClient(properties, restClient, retry, cb);

        // Act + Assert
        assertThatThrownBy(() -> clientWithOpenBreaker.authorize(request))
                .isInstanceOf(AuthorizationException.class);
    }

    @Test
    @DisplayName("Если rollback возвращает ответ со статусом APPROVED, то client.rollback возвращает response со status APPROVED")
    void shouldReturnRollbackResponse() {
        // Arrange
        AuthorizationRequest request = validRequest();
        RollbackResponse approved = RollbackResponse.approved(
                new com.processing.common.dto.authorization.RollbackRequest(
                        "012345678901", PAN, new BigDecimal("1000")), Instant.now());
        when(responseSpec.body(RollbackResponse.class)).thenReturn(approved);

        // Act
        RollbackResponse response = client.rollback(request, "012345678901");

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("Если rollback возвращает некорректный ответ (status UNKNOWN, responseCode 99), то client.rollback возвращает "
        + "null")
    void shouldReturnNullWhenRollbackResponseInvalid() {
        // Arrange
        AuthorizationRequest request = validRequest();
        when(responseSpec.body(RollbackResponse.class))
                .thenReturn(new RollbackResponse("012345678901", "99", "UNKNOWN", null, 0));

        // Act
        RollbackResponse response = client.rollback(request, "012345678901");

        // Assert
        assertThat(response).isNull();
    }

    @Test
    @DisplayName("Если вызов post бросает RuntimeException, то client.rollback возвращает null")
    void shouldReturnNullWhenRollbackThrows() {
        // Arrange
        AuthorizationRequest request = validRequest();
        when(restClient.post()).thenThrow(new RuntimeException("down"));

        // Act
        RollbackResponse response = client.rollback(request, "012345678901");

        // Assert
        assertThat(response).isNull();
    }

    @Test
    @DisplayName("Если health-check проходит успешно, то checkHealth возвращает ok")
    void shouldReturnOkWhenHealthSucceeds() {
        assertThat(client.checkHealth()).isEqualTo("ok");
    }

    @Test
    @DisplayName("Если вызов get бросает RuntimeException, то checkHealth возвращает down")
    void shouldReturnDownWhenHealthFails() {
        when(restClient.get()).thenThrow(new RuntimeException("down"));

        assertThat(client.checkHealth()).isEqualTo("down");
    }

    private AuthorizationRequest validRequest() {
        return new AuthorizationRequest(
                "0100", "000001", PAN, "000000",
                new BigDecimal("1000"), "643", Instant.now(),
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", "ISS001"
        );
    }
}