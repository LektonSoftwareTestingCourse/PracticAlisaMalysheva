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
    @DisplayName("Если карта активна, имеет баланс 1000 и резервируется сумма 100 с RRN001, то создаётся резервирование с PAN"
            + " карты, суммой 100 и статусом RESERVED")
    void shouldCreateReservationWhenCardActiveAndFundsAvailable() {
        // Act
        Reservation reservation = card().startReservation(new BigDecimal("100"), "RRN001");

        // Assert
        assertThat(reservation.pan()).isEqualTo(PAN);
        assertThat(reservation.reservationAmount()).isEqualByComparingTo("100");
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    @DisplayName("Если карта имеет статус BLOCKED, то при резервировании суммы 100 с RRN001 бросается IllegalStateException")
    void shouldRejectReservationWhenCardNotActive() {
        // Act + Assert
        assertThatThrownBy(() -> cardWithStatus(CardStatus.BLOCKED).startReservation(new BigDecimal("100"), "RRN001"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Если активная карта имеет баланс 1000, а резервируется сумма 5000, превышающая баланс, то бросается"
            + " InsufficientFundsException")
    void shouldRejectReservationWhenFundsInsufficient() {
        // Act + Assert
        assertThatThrownBy(() -> card().startReservation(new BigDecimal("5000"), "RRN001"))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    @DisplayName("Если на карту с балансом 1000 применяется резервирование на сумму 200, то доступный баланс становится 800")
    void shouldDecreaseBalanceWithReservation() {
        // Arrange
        Reservation reservation = new Reservation(PAN, new BigDecimal("200"), "RRN001");

        // Act
        Card updated = card().withReservation(reservation);

        // Assert
        assertThat(updated.availableBalance()).isEqualByComparingTo("800");
    }

    @Test
    @DisplayName("Если PAN резервирования 1111111111111111 не совпадает с PAN карты, то при применении резервирования бросается"
            + " IllegalArgumentException")
    void shouldRejectReservationWithDifferentPan() {
        // Arrange
        Reservation reservation = new Reservation("1111111111111111", new BigDecimal("200"), "RRN001");

        // Act + Assert
        assertThatThrownBy(() -> card().withReservation(reservation))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Если на карту с балансом 1000 применяется откат резервирования на сумму 200, то доступный баланс становится"
            + " 1200")
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
    @DisplayName("Если карту пометить как удалённую, то её статус становится DELETED")
    void shouldMarkCardAsDeleted() {
        // Act
        Card deleted = card().deleted();

        // Assert
        assertThat(deleted.status()).isEqualTo(CardStatus.DELETED);
    }

    @Test
    @DisplayName("Если карта уже имеет статус DELETED, то при повторном удалении бросается IllegalStateException")
    void shouldRejectDeletingAlreadyDeletedCard() {
        // Act + Assert
        assertThatThrownBy(() -> cardWithStatus(CardStatus.DELETED).deleted())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Если карте задаются статус BLOCKED, дневной лимит 50 и месячный лимит 500, то статус становится BLOCKED,"
            + " дневной лимит 50 и месячный лимит 500")
    void shouldUpdateCardData() {
        // Act
        Card updated = card().withData(CardStatus.BLOCKED, new BigDecimal("50"), new BigDecimal("500"), BALANCE);

        // Assert
        assertThat(updated.status()).isEqualTo(CardStatus.BLOCKED);
        assertThat(updated.dailyLimit()).isEqualByComparingTo("50");
        assertThat(updated.monthlyLimit()).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("Если дневной лимит равен -1, то есть является отрицательным, то при обновлении данных карты бросается"
            + " IllegalArgumentException")
    void shouldRejectNegativeLimit() {
        // Act + Assert
        assertThatThrownBy(() -> card().withData(CardStatus.ACTIVE, new BigDecimal("-1"), MONTHLY, BALANCE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Если дневной лимит 1000 больше месячного лимита 500, то при обновлении данных карты бросается"
            + " IllegalArgumentException")
    void shouldRejectDailyLimitLargerThanMonthly() {
        // Act + Assert
        assertThatThrownBy(() -> card().withData(CardStatus.ACTIVE, new BigDecimal("1000"), new BigDecimal("500"), BALANCE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Если карта имеет статус INACTIVE, то при вызове checkStatusOrThrow бросается IllegalStateException")
    void shouldRejectOperationOnInactiveCard() {
        // Act + Assert
        assertThatThrownBy(() -> cardWithStatus(CardStatus.INACTIVE).checkStatusOrThrow())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Если из черновика с BIN 400000, эмитентом ISS001 и сроком 3 года создаётся карта, то PAN равен переданному,"
            + " статус ACTIVE, issuerId ISS001, срок истечения через 3 года и доступный баланс 1000")
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
    @DisplayName("Если карта копируется с новым PAN 4276001234567890, то PAN копии равен новому значению и идентификатор копии"
            + " отличается от исходного")
    void shouldCopyWithNewPan() {
        // Act
        Card copy = card().copyWithPan("4276001234567890");

        // Assert
        assertThat(copy.pan()).isEqualTo("4276001234567890");
        assertThat(copy.id()).isNotEqualTo(card().id());
    }

    @Test
    @DisplayName("Если резервирование уже откачено и предпринимается повторный откат на сумму 200, то бросается"
            + " RollbackAlreadySatisfiedException")
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