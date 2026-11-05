package com.processing.authorization.services;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthorizationEvent;
import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.exceptions.CardNotFoundException;
import com.processing.authorization.exceptions.GetCardException;
import com.processing.authorization.exceptions.InsufficientFundsException;
import com.processing.authorization.exceptions.InternalCardManagerException;
import com.processing.authorization.exceptions.InvalidGetCardRequestException;
import com.processing.authorization.exceptions.InvalidReserveRequestException;
import com.processing.authorization.exceptions.InvalidRollbackRequestException;
import com.processing.authorization.exceptions.PaymentRequiredException;
import com.processing.authorization.exceptions.ReserveException;
import com.processing.authorization.exceptions.RollbackConflictException;
import com.processing.authorization.exceptions.RollbackFailureException;
import com.processing.authorization.exceptions.ServiceUnavailableException;
import com.processing.authorization.repositories.LimitUsageRepository;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    private static final String PAN = "4000001234567890";
    private static final String BIN = "400000";
    private static final String ISSUER_ID = "ISS001";
    private static final String RRN = "012345678901";
    private static final BigDecimal AMOUNT = new BigDecimal("1000");
    private static final BigDecimal HIGH_BALANCE = new BigDecimal("100000");

    @Mock
    private LimitUsageRepository limitUsageRepository;

    @Mock
    private AuthorizationEventNotifier eventNotifier;

    @Mock
    private BinLookupClient binLookupClient;

    @Mock
    private CardManagementClient cardManagementClient;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(limitUsageRepository, eventNotifier, binLookupClient, cardManagementClient);
    }

    // ------------------------------------------------------------------
    // authorize() — happy path
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("authorize: happy path и отказы по карте")
    class AuthorizeCardChecks {

        @Test
        @DisplayName("Если карта активна (getCard возвращает ACTIVE), issuerId найден, "
                + "upsert лимитов вернул 1 и блок RRN свободен, то статус APPROVED, "
                + "rrn и authCode непустые, вызываются reserve(PAN) и notify(event)")
        void shouldApproveAuthorizationWhenCardIsActiveAndSufficientFunds() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
            when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
            when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);
            when(limitUsageRepository.fetchRrnBlock()).thenReturn(0L);

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), Instant.now());

            // Assert
            assertThat(response.status()).isEqualTo("APPROVED");
            assertThat(response.rrn()).isNotBlank();
            assertThat(response.authCode()).isNotBlank();
            verify(cardManagementClient).reserve(any(BigDecimal.class), any(String.class), eq(PAN));
            verify(eventNotifier).notify(any(AuthorizationEvent.class));
        }

        @ParameterizedTest(name = "status={0}")
        @EnumSource(value = CardModelStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"ACTIVE"})
        @DisplayName("Если статус карты один из не-ACTIVE (INACTIVE/BLOCKED/EXPIRED) "
                + "и issuerId найден, то статус DECLINED, declineReason непустой "
                + "и вызывается notify(event)")
        void shouldDeclineWhenCardStatusIsNotActive(CardModelStatus status) {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(cardWithStatus(status));
            when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), Instant.now());

            // Assert
            assertThat(response.status()).isEqualTo("DECLINED");
            assertThat(response.declineReason()).isNotBlank();
            verify(eventNotifier).notify(any(AuthorizationEvent.class));
        }

        @Test
        @DisplayName("Если у карты статус отсутствует (null) и issuerId найден, "
                + "то статус DECLINED и declineReason равен UNKNOWN_REASON")
        void shouldDeclineWhenCardStatusIsNull() {
            // Arrange
            when(cardManagementClient.getCard(PAN))
                    .thenReturn(cardWithStatus(null));
            when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), Instant.now());

            // Assert
            assertThat(response.status()).isEqualTo("DECLINED");
            assertThat(response.declineReason()).isEqualTo("UNKNOWN_REASON");
        }

        @Test
        @DisplayName("Если срок действия активной карты истёк (expiry в прошлом) "
                + "и issuerId найден, то declineReason равен CARD_EXPIRED и reserve не вызывается")
        void shouldDeclineWhenCardIsExpiredByDate() {
            // Arrange
            when(cardManagementClient.getCard(PAN))
                    .thenReturn(cardWithExpiry(YearMonth.now().minusMonths(1)));
            when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), Instant.now());

            // Assert
            assertThat(response.declineReason()).isEqualTo("CARD_EXPIRED");
            verify(cardManagementClient, never()).reserve(any(), any(), any());
        }

        @Test
        @DisplayName("Если баланс активной карты (500) меньше запрашиваемой суммы "
                + "и issuerId найден, то declineReason равен INSUFFICIENT_FUNDS "
                + "и reserve не вызывается")
        void shouldDeclineWhenBalanceIsInsufficient() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(activeCardWithBalance(new BigDecimal("500")));
            when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), Instant.now());

            // Assert
            assertThat(response.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
            verify(cardManagementClient, never()).reserve(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("authorize: обработка исключений getCard")
    class AuthorizeGetCardExceptions {

        @Test
        @DisplayName("Если getCard бросает CardNotFoundException, то статус DECLINED, "
                + "declineReason равен CARD_NOT_FOUND и вызывается notify(event)")
        void shouldDeclineWhenCardNotFound() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenThrow(new CardNotFoundException(PAN));

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), Instant.now());

            // Assert
            assertThat(response.status()).isEqualTo("DECLINED");
            assertThat(response.declineReason()).isEqualTo("CARD_NOT_FOUND");
            verify(eventNotifier).notify(any(AuthorizationEvent.class));
        }

        @Test
        @DisplayName("Если getCard бросает InvalidGetCardRequestException, "
                + "то declineReason равен CARD_NOT_FOUND")
        void shouldDeclineWhenGetCardRequestIsInvalid() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenThrow(new InvalidGetCardRequestException("bad request"));

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), Instant.now());

            // Assert
            assertThat(response.declineReason()).isEqualTo("CARD_NOT_FOUND");
        }

        @ParameterizedTest
        @DisplayName("Если getCard бросает одну из ошибок недоступности "
                + "(ServiceUnavailableException/ResourceAccessException/InternalCardManagerException/GetCardException), "
                + "то declineReason равен SERVICE_UNAVAILABLE")
        @ValueSource(classes = {
                ServiceUnavailableException.class,
                ResourceAccessException.class,
                InternalCardManagerException.class,
                GetCardException.class
        })
        void shouldDeclineWhenCardServiceUnavailable(Class<? extends RuntimeException> exceptionType)
                throws Exception {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenThrow(exceptionType.getConstructor(String.class).newInstance("down"));

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), Instant.now());

            // Assert
            assertThat(response.declineReason()).isEqualTo("SERVICE_UNAVAILABLE");
        }

        @Test
        @DisplayName("Если getCard бросает PaymentRequiredException, то declineReason равен CARD_BLOCKED")
        void shouldDeclineWhenPaymentIsRequired() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenThrow(new PaymentRequiredException("payment required"));

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), Instant.now());

            // Assert
            assertThat(response.declineReason()).isEqualTo("CARD_BLOCKED");
        }

        @Test
        @DisplayName("Если getCard бросает непредвиденное исключение "
                + "(например IllegalStateException), то declineReason равен UNKNOWN_REASON")
        void shouldDeclineWithUnknownReasonOnUnexpectedError() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenThrow(new IllegalStateException("boom"));

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), Instant.now());

            // Assert
            assertThat(response.declineReason()).isEqualTo("UNKNOWN_REASON");
        }
    }

    @Nested
    @DisplayName("authorize: лимиты и резервирование")
    class AuthorizeLimitsAndReservation {

        @Test
        @DisplayName("Если карта активна, issuerId найден и upsert лимитов вернул 0, "
                + "то declineReason равен EXCEEDS_AMOUNT_LIMIT и reserve не вызывается")
        void shouldDeclineWhenLimitsNotUpdated() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
            when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
            when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(0);

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), Instant.now());

            // Assert
            assertThat(response.declineReason()).isEqualTo("EXCEEDS_AMOUNT_LIMIT");
            verify(cardManagementClient, never()).reserve(any(), any(), any());
        }

        @Test
        @DisplayName("Если карта активна, issuerId найден, upsert лимитов бросает "
                + "DuplicateKeyException при первой попытке и возвращает 1 при повторной, "
                + "а блок RRN свободен, то статус APPROVED "
                + "и upsertLimitUsage вызывается дважды")
        void shouldRetryLimitsOnDuplicateKeyAndApprove() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
            when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
            when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any()))
                    .thenThrow(new DuplicateKeyException("dup"))
                    .thenReturn(1);
            when(limitUsageRepository.fetchRrnBlock()).thenReturn(0L);

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), Instant.now());

            // Assert
            assertThat(response.status()).isEqualTo("APPROVED");
            verify(limitUsageRepository, times(2)).upsertLimitUsage(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Если карта активна, issuerId найден, upsert лимитов бросает "
                + "DuplicateKeyException и затем снова RuntimeException, "
                + "то declineReason равен DB_UNAVAILABLE")
        void shouldReturnDbUnavailableWhenRetryFails() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
            when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
            when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any()))
                    .thenThrow(new DuplicateKeyException("dup"))
                    .thenThrow(new RuntimeException("still down"));

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), Instant.now());

            // Assert
            assertThat(response.declineReason()).isEqualTo("DB_UNAVAILABLE");
        }

        @Test
        @DisplayName("Если карта активна, issuerId найден и upsert лимитов бросает "
                + "RuntimeException, то declineReason равен DB_UNAVAILABLE")
        void shouldReturnDbUnavailableOnLimitFailure() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
            when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
            when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("db down"));

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), Instant.now());

            // Assert
            assertThat(response.declineReason()).isEqualTo("DB_UNAVAILABLE");
        }

        @Test
        @DisplayName("Если лимиты обновлены и блок RRN свободен, "
                + "а reserve бросает CardNotFoundException, то declineReason равен CARD_NOT_FOUND")
        void shouldDeclineWhenReserveFailsCardNotFound() {
            // Arrange
            activeCardWithLimitsAndRrn();
            doThrow(new CardNotFoundException(PAN)).when(cardManagementClient).reserve(any(), any(), any());

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), Instant.now());

            // Assert
            assertThat(response.declineReason()).isEqualTo("CARD_NOT_FOUND");
        }

        @Test
        @DisplayName("Если лимиты обновлены и блок RRN свободен, а reserve бросает "
                + "InvalidReserveRequestException, то declineReason равен CARD_NOT_FOUND")
        void shouldDeclineWhenReserveRequestIsInvalid() {
            // Arrange
            activeCardWithLimitsAndRrn();
            doThrow(new InvalidReserveRequestException("bad")).when(cardManagementClient).reserve(any(), any(), any());

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), Instant.now());

            // Assert
            assertThat(response.declineReason()).isEqualTo("CARD_NOT_FOUND");
        }

        @Test
        @DisplayName("Если лимиты обновлены и блок RRN свободен, а reserve бросает "
                + "ServiceUnavailableException, то declineReason равен SERVICE_UNAVAILABLE")
        void shouldDeclineWhenReserveServiceUnavailable() {
            // Arrange
            activeCardWithLimitsAndRrn();
            doThrow(new ServiceUnavailableException("down")).when(cardManagementClient).reserve(any(), any(), any());

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), Instant.now());

            // Assert
            assertThat(response.declineReason()).isEqualTo("SERVICE_UNAVAILABLE");
        }

        @Test
        @DisplayName("Если лимиты обновлены и блок RRN свободен, а reserve бросает "
                + "InsufficientFundsException, то declineReason равен INSUFFICIENT_FUNDS")
        void shouldDeclineWhenReserveInsufficientFunds() {
            // Arrange
            activeCardWithLimitsAndRrn();
            doThrow(new InsufficientFundsException("no funds")).when(cardManagementClient).reserve(any(), any(), any());

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), Instant.now());

            // Assert
            assertThat(response.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        }

        @Test
        @DisplayName("Если лимиты обновлены и блок RRN свободен, "
                + "а reserve бросает ReserveException, то declineReason равен RESERVATION_FAILED")
        void shouldDeclineWhenReserveFails() {
            // Arrange
            activeCardWithLimitsAndRrn();
            doThrow(new ReserveException("failed")).when(cardManagementClient).reserve(any(), any(), any());

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), Instant.now());

            // Assert
            assertThat(response.declineReason()).isEqualTo("RESERVATION_FAILED");
        }

        @Test
        @DisplayName("Если лимиты обновлены и блок RRN свободен, а reserve бросает "
                + "непредвиденное исключение (например IllegalStateException), "
                + "то declineReason равен UNKNOWN_REASON")
        void shouldDeclineWithUnknownReasonWhenReserveThrowsUnexpected() {
            // Arrange
            activeCardWithLimitsAndRrn();
            doThrow(new IllegalStateException("boom")).when(cardManagementClient).reserve(any(), any(), any());

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), Instant.now());

            // Assert
            assertThat(response.declineReason()).isEqualTo("UNKNOWN_REASON");
        }
    }

    @Nested
    @DisplayName("rollback")
    class Rollback {

        @Test
        @DisplayName("Если rollback запрос корректен и cardManagementClient.rollback успешен, "
                + "то статус APPROVED и rollback(request) вызывается")
        void shouldApproveRollback() {
            // Arrange
            RollbackRequest request = new RollbackRequest(RRN, PAN, AMOUNT);

            // Act
            RollbackResponse response = authService.rollback(request, Instant.now());

            // Assert
            assertThat(response.status()).isEqualTo("APPROVED");
            verify(cardManagementClient).rollback(request);
        }

        @Test
        @DisplayName("Если cardManagementClient.rollback бросает CardNotFoundException, "
                + "то declineReason равен TRANSACTION_NOT_FOUND")
        void shouldDeclineRollbackWhenCardNotFound() {
            // Arrange
            RollbackRequest request = new RollbackRequest(RRN, PAN, AMOUNT);
            doThrow(new CardNotFoundException(PAN)).when(cardManagementClient).rollback(request);

            // Act
            RollbackResponse response = authService.rollback(request, Instant.now());

            // Assert
            assertThat(response.declineReason()).isEqualTo("TRANSACTION_NOT_FOUND");
        }

        @Test
        @DisplayName("Если cardManagementClient.rollback бросает InvalidRollbackRequestException, "
                + "то declineReason равен TRANSACTION_NOT_FOUND")
        void shouldDeclineRollbackWhenRequestIsInvalid() {
            // Arrange
            RollbackRequest request = new RollbackRequest(RRN, PAN, AMOUNT);
            doThrow(new InvalidRollbackRequestException("bad")).when(cardManagementClient).rollback(request);

            // Act
            RollbackResponse response = authService.rollback(request, Instant.now());

            // Assert
            assertThat(response.declineReason()).isEqualTo("TRANSACTION_NOT_FOUND");
        }

        @ParameterizedTest
        @DisplayName("Если cardManagementClient.rollback бросает одну из ошибок "
                + "недоступности (ServiceUnavailableException/ResourceAccessException/"
                + "InternalCardManagerException), то declineReason равен SERVICE_UNAVAILABLE")
        @ValueSource(classes = {
                ServiceUnavailableException.class,
                ResourceAccessException.class,
                InternalCardManagerException.class
        })
        void shouldDeclineRollbackWhenServiceUnavailable(Class<? extends RuntimeException> exceptionType)
                throws Exception {
            // Arrange
            RollbackRequest request = new RollbackRequest(RRN, PAN, AMOUNT);
            doThrow(exceptionType.getConstructor(String.class).newInstance("down"))
                    .when(cardManagementClient).rollback(request);

            // Act
            RollbackResponse response = authService.rollback(request, Instant.now());

            // Assert
            assertThat(response.declineReason()).isEqualTo("SERVICE_UNAVAILABLE");
        }

        @Test
        @DisplayName("Если cardManagementClient.rollback бросает RollbackConflictException, "
                + "то declineReason равен ALREADY_ROLLED_BACK")
        void shouldDeclineRollbackWhenAlreadyRolledBack() {
            // Arrange
            RollbackRequest request = new RollbackRequest(RRN, PAN, AMOUNT);
            doThrow(new RollbackConflictException("conflict")).when(cardManagementClient).rollback(request);

            // Act
            RollbackResponse response = authService.rollback(request, Instant.now());

            // Assert
            assertThat(response.declineReason()).isEqualTo("ALREADY_ROLLED_BACK");
        }

        @Test
        @DisplayName("Если cardManagementClient.rollback бросает RollbackFailureException, "
                + "то declineReason равен ROLLBACK_FAILED")
        void shouldDeclineRollbackWhenRollbackFails() {
            // Arrange
            RollbackRequest request = new RollbackRequest(RRN, PAN, AMOUNT);
            doThrow(new RollbackFailureException("failed")).when(cardManagementClient).rollback(request);

            // Act
            RollbackResponse response = authService.rollback(request, Instant.now());

            // Assert
            assertThat(response.declineReason()).isEqualTo("ROLLBACK_FAILED");
        }

        @Test
        @DisplayName("Если cardManagementClient.rollback бросает непредвиденное исключение "
                + "(например IllegalStateException), то declineReason равен UNKNOWN_REASON")
        void shouldDeclineRollbackWithUnknownReason() {
            // Arrange
            RollbackRequest request = new RollbackRequest(RRN, PAN, AMOUNT);
            doThrow(new IllegalStateException("boom")).when(cardManagementClient).rollback(request);

            // Act
            RollbackResponse response = authService.rollback(request, Instant.now());

            // Assert
            assertThat(response.declineReason()).isEqualTo("UNKNOWN_REASON");
        }
    }

    @Nested
    @DisplayName("checkAndUpdateLimits")
    class CheckAndUpdateLimits {

        @Test
        @DisplayName("Если upsert лимитов вернул 1, то checkAndUpdateLimits возвращает true")
        void shouldReturnTrueWhenUpsertSucceeded() {
            // Arrange
            when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);

            // Act
            boolean updated = authService.checkAndUpdateLimits(activeCard(), AMOUNT, java.time.LocalDate.now());

            // Assert
            assertThat(updated).isTrue();
        }

        @Test
        @DisplayName("Если upsert лимитов вернул 0, то checkAndUpdateLimits возвращает false")
        void shouldReturnFalseWhenUpsertAffectedZeroRows() {
            // Arrange
            when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(0);

            // Act
            boolean updated = authService.checkAndUpdateLimits(activeCard(), AMOUNT, java.time.LocalDate.now());

            // Assert
            assertThat(updated).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // Фабричные методы (Object Mother)
    // ------------------------------------------------------------------

    private void activeCardWithLimitsAndRrn() {
        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);
        when(limitUsageRepository.fetchRrnBlock()).thenReturn(0L);
    }

    private AuthorizationRequest validRequest() {
        return new AuthorizationRequest(
                "0100", "000001", PAN, "000000",
                AMOUNT, "643", Instant.now(),
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", ISSUER_ID
        );
    }

    private CardModel activeCard() {
        return cardWithStatus(CardModelStatus.ACTIVE);
    }

    private CardModel activeCardWithBalance(BigDecimal balance) {
        return new CardModel(
                UUID.randomUUID(), PAN, BIN, "IVAN IVANOV",
                YearMonth.now().plusYears(3), CardModelStatus.ACTIVE, "643",
                new BigDecimal("100000"), new BigDecimal("500000"), balance,
                ISSUER_ID, Instant.now()
        );
    }

    private CardModel cardWithExpiry(YearMonth expiry) {
        return new CardModel(
                UUID.randomUUID(), PAN, BIN, "IVAN IVANOV",
                expiry, CardModelStatus.ACTIVE, "643",
                new BigDecimal("100000"), new BigDecimal("500000"), HIGH_BALANCE,
                ISSUER_ID, Instant.now()
        );
    }

    private CardModel cardWithStatus(CardModelStatus status) {
        return new CardModel(
                UUID.randomUUID(), PAN, BIN, "IVAN IVANOV",
                YearMonth.now().plusYears(3), status, "643",
                new BigDecimal("100000"), new BigDecimal("500000"), HIGH_BALANCE,
                ISSUER_ID, Instant.now()
        );
    }
}
