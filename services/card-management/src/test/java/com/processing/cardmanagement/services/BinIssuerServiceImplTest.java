package com.processing.cardmanagement.services;

import com.processing.cardmanagement.exceptions.BinAlreadyExistException;
import com.processing.cardmanagement.exceptions.BinNotFoundException;
import com.processing.cardmanagement.models.BinIssuer;
import com.processing.cardmanagement.repositories.BinIssuerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BinIssuerServiceImplTest {

    private static final String BIN = "400000";
    private static final String ISSUER_ID = "ISS001";

    @Mock
    private BinIssuerRepository repository;

    private BinIssuerServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BinIssuerServiceImpl(repository);
    }

    @Test
    @DisplayName("Если в репозитории найден маппинг BIN 400000 с issuerId ISS001, то getIssuerId возвращает ISS001")
    void shouldReturnIssuerIdForKnownBin() {
        when(repository.findByBin(BIN)).thenReturn(Optional.of(new BinIssuer(BIN, ISSUER_ID)));

        assertThat(service.getIssuerId(BIN)).isEqualTo(ISSUER_ID);
    }

    @Test
    @DisplayName("Если маппинг BIN 400000 не найден в репозитории, то getIssuerId бросает BinNotFoundException")
    void shouldThrowWhenBinUnknown() {
        when(repository.findByBin(BIN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getIssuerId(BIN)).isInstanceOf(BinNotFoundException.class);
    }

    @Test
    @DisplayName("Если в репозитории хранится один маппинг BIN, то getAll возвращает список размером 1")
    void shouldReturnAllBins() {
        when(repository.findAll()).thenReturn(List.of(new BinIssuer(BIN, ISSUER_ID)));

        assertThat(service.getAll()).hasSize(1);
    }

    @Test
    @DisplayName("Если BIN 400000 ещё не существует и при сохранении возвращается маппинг с issuerId ISS001, то create возвращает"
            + " созданный маппинг с issuerId ISS001")
    void shouldCreateBinWhenNotExists() {
        when(repository.existsByBin(BIN)).thenReturn(false);
        when(repository.save(new BinIssuer(BIN, ISSUER_ID))).thenReturn(new BinIssuer(BIN, ISSUER_ID));

        BinIssuer created = service.create(BIN, ISSUER_ID);

        assertThat(created.issuerId()).isEqualTo(ISSUER_ID);
    }

    @Test
    @DisplayName("Если BIN 400000 уже существует, то create бросает BinAlreadyExistException")
    void shouldThrowWhenBinAlreadyExists() {
        when(repository.existsByBin(BIN)).thenReturn(true);

        assertThatThrownBy(() -> service.create(BIN, ISSUER_ID)).isInstanceOf(BinAlreadyExistException.class);
    }

    @Test
    @DisplayName("Если BIN 400000 существует, то при удалении вызывается deleteByBin для этого BIN")
    void shouldDeleteExistingBin() {
        when(repository.existsByBin(BIN)).thenReturn(true);

        service.delete(BIN);

        verify(repository).deleteByBin(BIN);
    }

    @Test
    @DisplayName("Если BIN 400000 не существует, то при удалении бросается BinNotFoundException")
    void shouldThrowWhenDeletingUnknownBin() {
        when(repository.existsByBin(BIN)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(BIN)).isInstanceOf(BinNotFoundException.class);
    }
}