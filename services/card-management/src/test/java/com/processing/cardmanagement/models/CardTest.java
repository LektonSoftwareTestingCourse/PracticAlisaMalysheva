package com.processing.cardmanagement.models;

import com.processing.cardmanagement.exceptions.InsufficientFundsException;
import com.processing.cardmanagement.exceptions.RollbackAlreadySatisfiedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardTest {

    private static final String PAN = "4000001234567890";
    private static final BigDecimal BALANCE = new BigDecimal("1000");
    private static final BigDecimal DAILY = new BigDecimal("100");
    private static final BigDecimal MONTHLY = new BigDecimal("1000");

    @Test
    @DisplayName("создаёт резервирование для активной карты с достаточными средствами")
    void shouldCreateReservationWhenCardActiveAndFundsAvailable() {
        // Act
        Reservation reservation = card().startReservation(new BigDecimal("100"), "RRN001");

        // Assert
        assertThat(reservation.pan()).isEqualTo(PAN);
        assertThat(reservation.reservationAmount()).isEqualByComparingTo("100");
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    @DisplayName("бросает исключение при резервировании на неактивной карте")
    void shouldRejectReservationWhenCardNotActive() {
        // Act + Assert
        assertThatThrownBy(() -> cardWithStatus(CardStatus.BLOCKED).startReservation(new BigDecimal("100"), "RRN001"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("бросает InsufficientFundsException при нехватке средств")
    void shouldRejectReservationWhenFundsInsufficient() {
        // Act + Assert
        assertThatThrownBy(() -> card().startReservation(new BigDecimal("5000"), "RRN001"))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    @DisplayName("уменьшает баланс при резервировании")
    void shouldDecreaseBalanceWithReservation() {
        // Arrange
        Reservation reservation = new Reservation(PAN, new BigDecimal("200"), "RRN001");

        // Act
        Card updated = card().withReservation(reservation);

        // Assert
        assertThat(updated.availableBalance()).isEqualByComparingTo("800");
    }

    @Test
    @DisplayName("бросает исключение, если PAN резервирования не совпадает с картой")
    void shouldRejectReservationWithDifferentPan() {
        // Arrange
        Reservation reservation = new Reservation("1111111111111111", new BigDecimal("200"), "RRN001");

        // Act + Assert
        assertThatThrownBy(() -> card().withReservation(reservation))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("увеличивает баланс при возврате средств")
    void shouldIncreaseBalanceWithRollback() {
        // Arrange
        Reservation reservation = new Reservation(PAN, new BigDecimal("200"), "RRN001");
        ReservationRollback rollback = reservation.startRollback(new BigDecimal("200"));

        // Act
        Card updated = card().withRollback(rollback);

        // Assert
        assertThat(updated.availableBalance()).isEqualByComparingTo("1200");
    }

    @Test
    @DisplayName("помечает карту как удалённую")
    void shouldMarkCardAsDeleted() {
        // Act
        Card deleted = card().deleted();

        // Assert
        assertThat(deleted.status()).isEqualTo(CardStatus.DELETED);
    }

    @Test
    @DisplayName("бросает исключение при повторном удалении")
    void shouldRejectDeletingAlreadyDeletedCard() {
        // Act + Assert
        assertThatThrownBy(() -> cardWithStatus(CardStatus.DELETED).deleted())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("обновляет данные карты")
    void shouldUpdateCardData() {
        // Act
        Card updated = card().withData(CardStatus.BLOCKED, new BigDecimal("50"), new BigDecimal("500"), BALANCE);

        // Assert
        assertThat(updated.status()).isEqualTo(CardStatus.BLOCKED);
        assertThat(updated.dailyLimit()).isEqualByComparingTo("50");
        assertThat(updated.monthlyLimit()).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("бросает исключение при отрицательном лимите")
    void shouldRejectNegativeLimit() {
        // Act + Assert
        assertThatThrownBy(() -> card().withData(CardStatus.ACTIVE, new BigDecimal("-1"), MONTHLY, BALANCE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("бросает исключение, если дневной лимит больше месячного")
    void shouldRejectDailyLimitLargerThanMonthly() {
        // Act + Assert
        assertThatThrownBy(() -> card().withData(CardStatus.ACTIVE, new BigDecimal("1000"), new BigDecimal("500"), BALANCE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("запрещает операции с неактивной картой через checkStatusOrThrow")
    void shouldRejectOperationOnInactiveCard() {
        // Act + Assert
        assertThatThrownBy(() -> cardWithStatus(CardStatus.INACTIVE).checkStatusOrThrow())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("создаёт карту из черновика")
    void shouldCreateCardFromDraft() {
        // Arrange
        CardDraft draft = new CardDraft(
                "400000", "IVAN IVANOV", CardStatus.ACTIVE, "643", DAILY, MONTHLY, BALANCE);

        // Act
        Card card = Card.fromDraft(PAN, "ISS001", 3, draft);

        // Assert
        assertThat(card.pan()).isEqualTo(PAN);
        assertThat(card.status()).isEqualTo(CardStatus.ACTIVE);
        assertThat(card.issuerId()).isEqualTo("ISS001");
        assertThat(card.expiryDate()).isEqualTo(YearMonth.now().plusYears(3));
        assertThat(card.availableBalance()).isEqualByComparingTo(BALANCE);
    }

    @Test
    @DisplayName("создаёт копию карты с новым PAN")
    void shouldCopyWithNewPan() {
        // Act
        Card copy = card().copyWithPan("4276001234567890");

        // Assert
        assertThat(copy.pan()).isEqualTo("4276001234567890");
        assertThat(copy.id()).isNotEqualTo(card().id());
    }

    @Test
    @DisplayName("не позволяет откатить уже откаченное резервирование")
    void shouldRejectRollbackOfRolledBackReservation() {
        // Arrange
        Reservation reservation = new Reservation(PAN, new BigDecimal("200"), "RRN001");
        ReservationRollback rollback = reservation.startRollback(new BigDecimal("200"));
        Reservation rolledBack = reservation.rolledBack(rollback);

        // Act + Assert
        assertThatThrownBy(() -> rolledBack.startRollback(new BigDecimal("200")))
                .isInstanceOf(RollbackAlreadySatisfiedException.class);
    }

    private Card card() {
        return cardWithStatus(CardStatus.ACTIVE);
    }

    private Card cardWithStatus(CardStatus status) {
        return new Card(
                UUID.randomUUID(), PAN, "400000", "IVAN IVANOV",
                YearMonth.now().plusYears(3), status, "643",
                DAILY, MONTHLY, BALANCE, "ISS001", java.time.Instant.now()
        );
    }
}