package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardEvent;
import com.processing.cardmanagement.events.CardEventNotifier;
import com.processing.cardmanagement.exceptions.CardNotFoundException;
import com.processing.cardmanagement.exceptions.InsufficientFundsException;
import com.processing.cardmanagement.exceptions.MassiveCardCreationCollisionException;
import com.processing.cardmanagement.exceptions.ReservationAlreadyExistsException;
import com.processing.cardmanagement.exceptions.ReservationNotFoundException;
import com.processing.cardmanagement.exceptions.RollbackAlreadySatisfiedException;
import com.processing.cardmanagement.exceptions.TooLargeLimitException;
import com.processing.cardmanagement.models.Card;
import com.processing.cardmanagement.models.CardDraft;
import com.processing.cardmanagement.models.CardStatus;
import com.processing.cardmanagement.models.Reservation;
import com.processing.cardmanagement.models.ReservationRollback;
import com.processing.cardmanagement.models.ReservationStatus;
import com.processing.cardmanagement.options.CardServiceDefaults;
import com.processing.cardmanagement.options.CardServiceSettings;
import com.processing.cardmanagement.repositories.CardRepository;
import com.processing.cardmanagement.repositories.ReservationRepository;
import com.processing.cardmanagement.repositories.ReservationRollbackRepository;
import com.processing.cardmanagement.services.retries.RetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CardServiceImplTest {

    private static final String PAN = "4000001234567890";
    private static final String BIN = "400000";
    private static final String ISSUER_ID = "ISS001";
    private static final String RRN = "012345678901";
    private static final BigDecimal BALANCE = new BigDecimal("1000");
    private static final BigDecimal AMOUNT = new BigDecimal("100");

    @Mock
    private CardRepository cardRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationRollbackRepository reservationRollbackRepository;

    @Mock
    private CardServiceSettings settings;

    @Mock
    private CardServiceDefaults defaults;

    @Mock
    private PanGenerator panGenerator;

    @Mock
    private CardEventNotifier eventNotifier;

    @Mock
    private BinIssuerService binIssuerService;

    @Mock
    private RetryService retryService;

    @Mock
    private TransactionRunner transactionRunner;

    private CardServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CardServiceImpl(
                cardRepository,
                reservationRepository,
                reservationRollbackRepository,
                settings,
                defaults,
                panGenerator,
                eventNotifier,
                binIssuerService,
                retryService,
                transactionRunner);
        when(settings.cardValidityPeriod()).thenReturn(3);
        when(settings.maxPageLimit()).thenReturn(100);
        when(settings.maxCardCreationRetries()).thenReturn(3);
        runTransactionsInLine();
        runRetriesInLine();
    }

    @Test
    @DisplayName("Если для BIN 400000 определён идентификатор эмитента ISS001 и генерируется PAN 4000001234567890 и карта"
            + " сохраняется в репозитории, то создаётся карта, совпадающая с ожидаемой, вызывается сохранение в репозитории и"
            + " отправляется событие CardEvent")
    void shouldCreateCard() {
        // Arrange
        Card expected = activeCard();
        when(binIssuerService.getIssuerId(BIN)).thenReturn(ISSUER_ID);
        when(panGenerator.generatePan(BIN)).thenReturn(PAN);
        when(cardRepository.create(any())).thenReturn(expected);

        // Act
        Card created = service.createCard(BIN, "IVAN IVANOV", "643",
                new BigDecimal("100"), new BigDecimal("1000"), BALANCE);

        // Assert
        assertThat(created).isSameAs(expected);
        verify(cardRepository).create(any());
        verify(eventNotifier).notifyListeners(any(CardEvent.class));
    }

    @Test
    @DisplayName("Если генерируется PAN 4000001234567890, для BIN 400000 определён эмитент ISS001 и пакетно сохраняются две"
            + " активные карты, то создаются две карты и отправляется событие CardEvent")
    void shouldCreateMultipleCards() {
        // Arrange
        Card card1 = activeCard();
        Card card2 = activeCard();
        when(panGenerator.generatePan(BIN)).thenReturn(PAN);
        when(binIssuerService.getIssuerId(BIN)).thenReturn(ISSUER_ID);
        when(cardRepository.createAll(any())).thenReturn(List.of(card1, card2));

        // Act
        List<Card> created = service.createCards(List.of(
                new CardDraft(BIN, "A", CardStatus.ACTIVE, "643", null, null, null),
                new CardDraft(BIN, "B", CardStatus.ACTIVE, "643", null, null, null)
        ));

        // Assert
        assertThat(created).hasSize(2);
        verify(eventNotifier).notifyListeners(any(CardEvent.class));
    }

    @Test
    @DisplayName("Если пакетное сохранение карт падает с MassiveCardCreationCollisionException и поштучное сохранение карты"
            + " успешно, то создаётся одна карта")
    void shouldCreateCardsIndividuallyOnCollision() {
        // Arrange
        Card card = activeCard();
        when(panGenerator.generatePan(BIN)).thenReturn(PAN);
        when(binIssuerService.getIssuerId(BIN)).thenReturn(ISSUER_ID);
        when(cardRepository.createAll(any())).thenThrow(new MassiveCardCreationCollisionException());
        when(cardRepository.create(any())).thenReturn(card);

        // Act
        List<Card> created = service.createCards(List.of(
                new CardDraft(BIN, "A", CardStatus.ACTIVE, "643", null, null, null)
        ));

        // Assert
        assertThat(created).hasSize(1);
    }

    @Test
    @DisplayName("Если карта с PAN 4000001234567890 найдена в репозитории, то возвращается именно эта карта")
    void shouldGetCardByPan() {
        Card card = activeCard();
        when(cardRepository.findByPan(PAN)).thenReturn(Optional.of(card));

        assertThat(service.getCard(PAN)).isSameAs(card);
    }

    @Test
    @DisplayName("Если карта с PAN 4000001234567890 не найдена в репозитории, то бросается CardNotFoundException")
    void shouldThrowWhenCardNotFound() {
        when(cardRepository.findByPan(PAN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCard(PAN)).isInstanceOf(CardNotFoundException.class);
    }

    @Test
    @DisplayName("Если запрошен лимит страницы 101, превышающий максимальный лимит 100, то бросается TooLargeLimitException")
    void shouldThrowWhenPageLimitTooLarge() {
        assertThatThrownBy(() -> service.getCards(101, null, null, null, null, null, null))
                .isInstanceOf(TooLargeLimitException.class);
    }

    @Test
    @DisplayName("Если параметры пагинации не указаны и по умолчанию лимит страницы равен 10 и смещение равно 0, то репозиторий"
            + " ищет карты с этими значениями и возвращается одна карта")
    void shouldUseDefaultPagination() {
        when(defaults.pageLimit()).thenReturn(10);
        when(defaults.pageOffset()).thenReturn(0L);
        when(cardRepository.findCards(eq(10), eq(0L), any(), any(), any(), any(), any()))
                .thenReturn(List.of(activeCard()));

        List<Card> cards = service.getCards(null, null, null, null, null, null, null);

        assertThat(cards).hasSize(1);
    }

    @Test
    @DisplayName("Если карта с PAN 4000001234567890 найдена для обновления и обновление возвращает переданную карту, то дневной"
            + " лимит становится 50, статус остаётся ACTIVE и отправляется событие CardEvent")
    void shouldPatchCard() {
        // Arrange
        Card existing = activeCard();
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(existing));
        when(cardRepository.update(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        Card updated = service.patchCard(PAN, null, new BigDecimal("50"), null, null);

        // Assert
        assertThat(updated.dailyLimit()).isEqualByComparingTo("50");
        assertThat(updated.status()).isEqualTo(CardStatus.ACTIVE);
        verify(eventNotifier).notifyListeners(any(CardEvent.class));
    }

    @Test
    @DisplayName("Если карта с PAN 4000001234567890 не найдена для обновления, то при обновлении бросается CardNotFoundException")
    void shouldThrowWhenPatchingMissingCard() {
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.patchCard(PAN, null, null, null, null))
                .isInstanceOf(CardNotFoundException.class);
    }

    @Test
    @DisplayName("Если карта с PAN 4000001234567890 найдена для обновления и обновление возвращает переданную карту, то при"
            + " удалении вызывается обновление в репозитории и отправляется событие CardEvent")
    void shouldDeleteCard() {
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(cardRepository.update(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deleteCard(PAN);

        verify(cardRepository).update(any());
        verify(eventNotifier).notifyListeners(any(CardEvent.class));
    }

    @Test
    @DisplayName("Если карта с PAN 4000001234567890 активна, RRN 012345678901 уникален, сохранение резервирования и обновление"
            + " карты возвращают переданные объекты и резервируется сумма 100, то доступный баланс становится 900 и"
            + " отправляется событие CardEvent")
    void shouldReserveFunds() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(reservationRepository.isUnique(RRN, PAN)).thenReturn(true);
        when(reservationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(cardRepository.update(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        Card reserved = service.reserve(PAN, AMOUNT, RRN);

        // Assert
        assertThat(reserved.availableBalance()).isEqualByComparingTo("900");
        verify(eventNotifier).notifyListeners(any(CardEvent.class));
    }

    @Test
    @DisplayName("Если карта с PAN 4000001234567890 активна, но её баланс 50 меньше резервируемой суммы 100, то при"
            + " резервировании бросается InsufficientFundsException")
    void shouldRejectReservationWhenInsufficientFunds() {
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCardWithBalance("50")));

        assertThatThrownBy(() -> service.reserve(PAN, AMOUNT, RRN))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    @DisplayName("Если карта с PAN 4000001234567890 активна, но RRN 012345678901 уже зарегистрирован, то при резервировании"
            + " бросается ReservationAlreadyExistsException")
    void shouldRejectReservationWhenRrnNotUnique() {
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(reservationRepository.isUnique(RRN, PAN)).thenReturn(false);

        assertThatThrownBy(() -> service.reserve(PAN, AMOUNT, RRN))
                .isInstanceOf(ReservationAlreadyExistsException.class);
    }

    @Test
    @DisplayName("Если карта с PAN 4000001234567890 не найдена для обновления, то при резервировании бросается"
            + " CardNotFoundException")
    void shouldThrowWhenReservingMissingCard() {
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reserve(PAN, AMOUNT, RRN))
                .isInstanceOf(CardNotFoundException.class);
    }

    @Test
    @DisplayName("Если карта с PAN 4000001234567890 активна, резервирование с RRN 012345678901 найдено и сохранение отката,"
            + " резервирования и обновление карты возвращают переданные объекты, то при возврате суммы 100 доступный баланс"
            + " становится 1100 и отправляется событие CardEvent")
    void shouldRollbackFunds() {
        // Arrange
        Reservation reservation = new Reservation(PAN, AMOUNT, RRN);
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.of(reservation));
        when(reservationRollbackRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reservationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(cardRepository.update(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        Card rolledBack = service.rollback(PAN, AMOUNT, RRN);

        // Assert
        assertThat(rolledBack.availableBalance()).isEqualByComparingTo("1100");
        verify(eventNotifier).notifyListeners(any(CardEvent.class));
    }

    @Test
    @DisplayName("Если карта с PAN 4000001234567890 активна, но резервирование с RRN 012345678901 не найдено, то при возврате"
            + " средств бросается ReservationNotFoundException")
    void shouldThrowWhenReservationNotFound() {
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rollback(PAN, AMOUNT, RRN))
                .isInstanceOf(ReservationNotFoundException.class);
    }

    @Test
    @DisplayName("Если карта с PAN 4000001234567890 активна и резервирование с RRN 012345678901 уже имеет статус ROLLED_BACK, то"
            + " при повторном возврате бросается RollbackAlreadySatisfiedException")
    void shouldThrowWhenRollbackAlreadySatisfied() {
        // Arrange
        Reservation rolledBack = new Reservation(
                UUID.randomUUID(), PAN, AMOUNT, RRN, ReservationStatus.ROLLED_BACK, Instant.now(), Instant.now());
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.of(rolledBack));

        // Act + Assert
        assertThatThrownBy(() -> service.rollback(PAN, AMOUNT, RRN))
                .isInstanceOf(RollbackAlreadySatisfiedException.class);
    }

    @Test
    @DisplayName("Если по списку BIN 400000 найдена одна карта и обновление возвращает переданную карту, то при массовом переводе"
            + " в статус BLOCKED обновляется одна карта")
    void shouldBulkUpdateStatusByBins() {
        Card card = activeCard();
        when(cardRepository.findCardsByBinsForUpdate(List.of(BIN))).thenReturn(List.of(card));
        when(cardRepository.update(any())).thenAnswer(inv -> inv.getArgument(0));

        int updated = service.bulkUpdateStatus(List.of(BIN), null, CardStatus.BLOCKED);

        assertThat(updated).isEqualTo(1);
    }

    @Test
    @DisplayName("Если по списку PAN 4000001234567890 найдена одна карта и обновление возвращает переданную карту, то при"
            + " массовом переводе в статус BLOCKED обновляется одна карта")
    void shouldBulkUpdateStatusByPans() {
        Card card = activeCard();
        when(cardRepository.findCardsByPansForUpdate(List.of(PAN))).thenReturn(List.of(card));
        when(cardRepository.update(any())).thenAnswer(inv -> inv.getArgument(0));

        int updated = service.bulkUpdateStatus(null, List.of(PAN), CardStatus.BLOCKED);

        assertThat(updated).isEqualTo(1);
    }

    @Test
    @DisplayName("Если при массовом обновлении статуса не указаны ни BIN, ни PAN, то бросается IllegalArgumentException")
    void shouldRejectBulkUpdateWithoutSelectors() {
        assertThatThrownBy(() -> service.bulkUpdateStatus(null, null, CardStatus.BLOCKED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Если при массовом обновлении статуса указаны одновременно и BIN 400000, и PAN 4000001234567890, то бросается"
            + " IllegalArgumentException")
    void shouldRejectBulkUpdateWithBothSelectors() {
        assertThatThrownBy(() -> service.bulkUpdateStatus(List.of(BIN), List.of(PAN), CardStatus.BLOCKED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Если в репозитории всего 5 карт, то подсчёт всех карт возвращает 5 и отправляется событие CardEvent")
    void shouldCountAllCards() {
        when(cardRepository.countAllCards()).thenReturn(5L);

        long count = service.countAllCardsAndUpdate();

        assertThat(count).isEqualTo(5L);
        verify(eventNotifier).notifyListeners(any(CardEvent.class));
    }

    @Test
    @DisplayName("Если при фильтрации по статусу ACTIVE, BIN 400000 и эмитенту ISS001 в репозитории насчитано 3 карты, то"
            + " возвращается 3")
    void shouldCountFilteredCards() {
        when(cardRepository.countCardsFiltered(any(), any(), any(), any(), any())).thenReturn(3L);

        long count = service.countCardsFiltered(CardStatus.ACTIVE, BIN, ISSUER_ID, null, null);

        assertThat(count).isEqualTo(3L);
    }

    // ------------------------------------------------------------------
    // Хелперы
    // ------------------------------------------------------------------

    private void runTransactionsInLine() {
        when(transactionRunner.runSupplier(any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        when(transactionRunner.runSupplierWithNestedQuery(any())).thenAnswer(inv -> {
            java.util.function.Function<TransactionRunner, ?> f = inv.getArgument(0);
            return f.apply(transactionRunner);
        });
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(transactionRunner).run(any());
    }

    private void runRetriesInLine() {
        when(retryService.supply(anyInt(), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
    }

    private Card activeCard() {
        return activeCardWithBalance(BALANCE.toString());
    }

    private Card activeCardWithBalance(String balance) {
        return new Card(
                UUID.randomUUID(), PAN, BIN, "IVAN IVANOV",
                YearMonth.now().plusYears(3), CardStatus.ACTIVE, "643",
                new BigDecimal("100"), new BigDecimal("1000"), new BigDecimal(balance),
                ISSUER_ID, Instant.now()
        );
    }
}
