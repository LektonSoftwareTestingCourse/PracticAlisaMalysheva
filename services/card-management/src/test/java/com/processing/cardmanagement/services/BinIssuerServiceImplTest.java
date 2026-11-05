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
    @DisplayName("возвращает issuerId для известного BIN")
    void shouldReturnIssuerIdForKnownBin() {
        when(repository.findByBin(BIN)).thenReturn(Optional.of(new BinIssuer(BIN, ISSUER_ID)));

        assertThat(service.getIssuerId(BIN)).isEqualTo(ISSUER_ID);
    }

    @Test
    @DisplayName("бросает BinNotFoundException для неизвестного BIN")
    void shouldThrowWhenBinUnknown() {
        when(repository.findByBin(BIN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getIssuerId(BIN)).isInstanceOf(BinNotFoundException.class);
    }

    @Test
    @DisplayName("возвращает все BIN-маппинги")
    void shouldReturnAllBins() {
        when(repository.findAll()).thenReturn(List.of(new BinIssuer(BIN, ISSUER_ID)));

        assertThat(service.getAll()).hasSize(1);
    }

    @Test
    @DisplayName("создаёт BIN-маппинг, если BIN ещё не существует")
    void shouldCreateBinWhenNotExists() {
        when(repository.existsByBin(BIN)).thenReturn(false);
        when(repository.save(new BinIssuer(BIN, ISSUER_ID))).thenReturn(new BinIssuer(BIN, ISSUER_ID));

        BinIssuer created = service.create(BIN, ISSUER_ID);

        assertThat(created.issuerId()).isEqualTo(ISSUER_ID);
    }

    @Test
    @DisplayName("бросает BinAlreadyExistException, если BIN уже существует")
    void shouldThrowWhenBinAlreadyExists() {
        when(repository.existsByBin(BIN)).thenReturn(true);

        assertThatThrownBy(() -> service.create(BIN, ISSUER_ID)).isInstanceOf(BinAlreadyExistException.class);
    }

    @Test
    @DisplayName("удаляет BIN-маппинг")
    void shouldDeleteExistingBin() {
        when(repository.existsByBin(BIN)).thenReturn(true);

        service.delete(BIN);

        verify(repository).deleteByBin(BIN);
    }

    @Test
    @DisplayName("бросает BinNotFoundException при удалении неизвестного BIN")
    void shouldThrowWhenDeletingUnknownBin() {
        when(repository.existsByBin(BIN)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(BIN)).isInstanceOf(BinNotFoundException.class);
    }
}