package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.exception.AuthorizationException;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteServiceTest {

    private static final String PAN = "4000001234567890";
    private static final String ISSUER_ID = "ISS001";

    @Mock
    private RoutingService routingService;

    @Mock
    private AuthorizationClient authorizationClient;

    @Mock
    private AcquiringFeeClient acquiringFeeClient;

    @Mock
    private LoggerClient loggerClient;

    private RouteService routeService;

    @BeforeEach
    void setUp() {
        routeService = new RouteService(routingService, authorizationClient, acquiringFeeClient, loggerClient);
    }

    @Test
    @DisplayName("Если маршрутизация возвращает issuerId для PAN, authorize возвращает APPROVED, fetchAcquiringFee возвращает "
        + "10 и log возвращает true, то response.status равен APPROVED, authorize и log вызваны, а rollback не вызывался")
    void shouldReturnApprovedWhenRoutingSucceedsAndLogged() {
        // Arrange
        AuthorizationRequest request = validRequest();
        AuthorizationResponse approved = AuthorizationResponse.approved(
                request.withIssuerId(ISSUER_ID), "012345678901", "TEST01", Instant.now());
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(approved);
        when(acquiringFeeClient.fetchAcquiringFee(any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new BigDecimal("10"));
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = routeService.route(request);

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        verify(authorizationClient).authorize(any(AuthorizationRequest.class));
        verify(loggerClient).log(any());
        verify(authorizationClient, never()).rollback(any(), anyString());
    }

    @Test
    @DisplayName("Если маршрутизация возвращает issuerId, authorize возвращает APPROVED, fetchAcquiringFee возвращает null, "
        + "log возвращает false и rollback возвращает APPROVED, то response.status равен DECLINED, response.responseCode равен "
        + "96 и rollback вызван")
    void shouldRollbackAndReturnSystemErrorWhenLoggingFailsAfterApproval() {
        // Arrange
        AuthorizationRequest request = validRequest();
        AuthorizationResponse approved = AuthorizationResponse.approved(
                request.withIssuerId(ISSUER_ID), "012345678901", "TEST01", Instant.now());
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(approved);
        when(acquiringFeeClient.fetchAcquiringFee(any(), anyString(), anyString(), anyString(), any())).thenReturn(null);
        when(loggerClient.log(any())).thenReturn(false);
        when(authorizationClient.rollback(any(), anyString()))
                .thenReturn(RollbackResponse.approved(newRollbackRequest(), Instant.now()));

        // Act
        AuthorizationResponse response = routeService.route(request);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("96");
        verify(authorizationClient).rollback(any(), anyString());
    }

    @Test
    @DisplayName("Если маршрутизация возвращает issuerId, authorize возвращает APPROVED, log возвращает false и rollback "
        + "возвращает null, то response.responseCode равен 96")
    void shouldReturnSystemErrorWhenRollbackReturnsNull() {
        // Arrange
        AuthorizationRequest request = validRequest();
        AuthorizationResponse approved = AuthorizationResponse.approved(
                request.withIssuerId(ISSUER_ID), "012345678901", "TEST01", Instant.now());
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(approved);
        when(loggerClient.log(any())).thenReturn(false);
        when(authorizationClient.rollback(any(), anyString())).thenReturn(null);

        // Act
        AuthorizationResponse response = routeService.route(request);

        // Assert
        assertThat(response.responseCode()).isEqualTo("96");
    }

    @Test
    @DisplayName("Если маршрутизация бросает UnknownBinException для PAN, то response.responseCode равен 14, "
        + "response.declineReason равен CARD_NOT_FOUND и authorize не вызывался")
    void shouldReturnUnknownBinWhenRoutingFails() {
        // Arrange
        AuthorizationRequest request = validRequest();
        when(routingService.getIssuerIdByPan(PAN)).thenThrow(new UnknownBinException("400000"));

        // Act
        AuthorizationResponse response = routeService.route(request);

        // Assert
        assertThat(response.responseCode()).isEqualTo("14");
        assertThat(response.declineReason()).isEqualTo("CARD_NOT_FOUND");
        verify(authorizationClient, never()).authorize(any());
    }

    @Test
    @DisplayName("Если маршрутизация возвращает issuerId и authorize бросает AuthorizationException, то response.responseCode "
        + "равен 05 и response.status равен DECLINED")
    void shouldReturnAuthUnavailableWhenAuthorizationFails() {
        // Arrange
        AuthorizationRequest request = validRequest();
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any()))
                .thenThrow(new AuthorizationException(request.stan(), 3, "circuit open"));

        // Act
        AuthorizationResponse response = routeService.route(request);

        // Assert
        assertThat(response.responseCode()).isEqualTo("05");
        assertThat(response.status()).isEqualTo("DECLINED");
    }

    @Test
    @DisplayName("Если PAN имеет длину 4 (короткий) и маршрутизация бросает UnknownBinException, то response.responseCode "
        + "равен 14")
    void shouldHandleShortPan() {
        // Arrange
        AuthorizationRequest request = validRequestWithPan("1234");
        when(routingService.getIssuerIdByPan("1234")).thenThrow(new UnknownBinException("null-or-short"));

        // Act
        AuthorizationResponse response = routeService.route(request);

        // Assert
        assertThat(response.responseCode()).isEqualTo("14");
    }

    @Test
    @DisplayName("Если PAN равен null и маршрутизация бросает UnknownBinException, то response.responseCode равен 14")
    void shouldHandleNullPan() {
        // Arrange
        AuthorizationRequest request = validRequestWithPan(null);
        when(routingService.getIssuerIdByPan(null)).thenThrow(new UnknownBinException("null-or-short"));

        // Act
        AuthorizationResponse response = routeService.route(request);

        // Assert
        assertThat(response.responseCode()).isEqualTo("14");
    }

    @Test
    @DisplayName("Если authorize возвращает ответ с null processingTimeMs и log возвращает true, то routed.declineReason равен "
        + "reason")
    void shouldLogZeroProcessingTimeWhenResponseHasNullProcessingTime() {
        // Arrange
        AuthorizationRequest request = validRequest();
        AuthorizationResponse response = new AuthorizationResponse(
                "0110", request.stan(), null, null, "05", "DECLINED", "reason", null);
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(response);
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse routed = routeService.route(request);

        // Assert
        assertThat(routed.declineReason()).isEqualTo("reason");
    }

    @Test
    @DisplayName("Если маршрутизация возвращает issuerId, authorize возвращает APPROVED, log возвращает false и rollback "
        + "возвращает DECLINED, то response.responseCode равен 96")
    void shouldReturnSystemErrorWhenRollbackDeclined() {
        // Arrange
        AuthorizationRequest request = validRequest();
        AuthorizationResponse approved = AuthorizationResponse.approved(
                request.withIssuerId(ISSUER_ID), "012345678901", "TEST01", Instant.now());
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(approved);
        when(loggerClient.log(any())).thenReturn(false);
        when(authorizationClient.rollback(any(), anyString()))
                .thenReturn(com.processing.common.dto.authorization.RollbackResponse.declined(
                        newRollbackRequest(), "reason", "05", Instant.now()));

        // Act
        AuthorizationResponse response = routeService.route(request);

        // Assert
        assertThat(response.responseCode()).isEqualTo("96");
    }

    @Test
    @DisplayName("Если AuthorizationException создан с null последней ошибкой и stan равным 000001, то message содержит "
        + "unknown и getStan возвращает 000001")
    void shouldBuildMessageWithUnknownWhenLastErrorIsNull() {
        // Act
        AuthorizationException ex = new AuthorizationException("000001", 3, null);

        // Assert
        assertThat(ex.getMessage()).contains("unknown");
        assertThat(ex.getStan()).isEqualTo("000001");
    }

    private AuthorizationRequest validRequest() {
        return validRequestWithPan(PAN);
    }

    private AuthorizationRequest validRequestWithPan(String pan) {
        return new AuthorizationRequest(
                "0100", "000001", pan, "000000",
                new BigDecimal("1000"), "643", Instant.now(),
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", "ISS000"
        );
    }

    private com.processing.common.dto.authorization.RollbackRequest newRollbackRequest() {
        return new com.processing.common.dto.authorization.RollbackRequest(
                "012345678901", PAN, new BigDecimal("1000"));
    }
}
