package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationRequestNormalizerTest {

    @ParameterizedTest(name = "terminalId={0}")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("Если terminalId равен null, пустой строке или строке из пробелов, то normalizeTerminalId возвращает его без "
        + "изменений")
    void shouldKeepBlankTerminalId(String terminalId) {
        assertThat(AuthorizationRequestNormalizer.normalizeTerminalId(terminalId)).isEqualTo(terminalId);
    }

    @Test
    @DisplayName("Если terminalId длиннее 8 символов (9 символов), то normalizeTerminalId обрезает его до 8 символов")
    void shouldTruncateLongTerminalId() {
        assertThat(AuthorizationRequestNormalizer.normalizeTerminalId("123456789")).isEqualTo("12345678");
    }

    @Test
    @DisplayName("Если terminalId короче 8 символов (3 символа), то normalizeTerminalId дополняет его нулями справа до 8 "
        + "символов")
    void shouldPadShortTerminalId() {
        assertThat(AuthorizationRequestNormalizer.normalizeTerminalId("123")).isEqualTo("12300000");
    }

    @Test
    @DisplayName("Если terminalId ровно 8 символов, то normalizeTerminalId возвращает его без изменений")
    void shouldKeepTerminalIdOfExactLength() {
        assertThat(AuthorizationRequestNormalizer.normalizeTerminalId("12345678")).isEqualTo("12345678");
    }

    @ParameterizedTest(name = "merchantId={0}")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("Если merchantId равен null, пустой строке или строке из пробелов, то normalizeMerchantId возвращает его без "
        + "изменений")
    void shouldKeepBlankMerchantId(String merchantId) {
        assertThat(AuthorizationRequestNormalizer.normalizeMerchantId(merchantId)).isEqualTo(merchantId);
    }

    @Test
    @DisplayName("Если merchantId ровно 15 символов, то normalizeMerchantId возвращает его без изменений")
    void shouldKeepMerchantIdOfExactLength() {
        assertThat(AuthorizationRequestNormalizer.normalizeMerchantId("MERCH0000000002")).isEqualTo("MERCH0000000002");
    }

    @Test
    @DisplayName("Если 16-символьный merchantId содержит лишний ноль после префикса MERCH, то normalizeMerchantId убирает этот "
        + "ноль")
    void shouldStripExtraZeroAfterMerchPrefix() {
        assertThat(AuthorizationRequestNormalizer.normalizeMerchantId("MERCH01234567890")).isEqualTo("MERCH1234567890");
    }

    @Test
    @DisplayName("Если merchantId длиннее 15 символов (16 символов), то normalizeMerchantId обрезает его до 15 символов")
    void shouldTruncateLongMerchantId() {
        assertThat(AuthorizationRequestNormalizer.normalizeMerchantId("1234567890123456")).isEqualTo("123456789012345");
    }

    @Test
    @DisplayName("Если merchantId короче 15 символов (3 символа), то normalizeMerchantId дополняет его нулями справа до 15 "
        + "символов")
    void shouldPadShortMerchantId() {
        assertThat(AuthorizationRequestNormalizer.normalizeMerchantId("123")).isEqualTo("123000000000000");
    }

    @Test
    @DisplayName("Если запрос содержит terminalId из 3 символов и merchantId MERCH0, то normalize возвращает terminalId "
        + "12300000 и merchantId MERCH0000000000, а остальные поля (mti, stan, pan, amount, issuerId) сохраняются без "
        + "изменений")
    void shouldNormalizeRequestPreservingOtherFields() {
        // Arrange
        AuthorizationRequest request = new AuthorizationRequest(
                "0100", "000001", "4000001234567890", "000000",
                new BigDecimal("1000"), "643", Instant.now(),
                "123", "POS", "MERCH0", "5045", "ACQ002", "ISS000"
        );

        // Act
        AuthorizationRequest normalized = AuthorizationRequestNormalizer.normalize(request);

        // Assert
        assertThat(normalized.terminalId()).isEqualTo("12300000");
        assertThat(normalized.merchantId()).isEqualTo("MERCH0000000000");
        assertThat(normalized.mti()).isEqualTo(request.mti());
        assertThat(normalized.stan()).isEqualTo(request.stan());
        assertThat(normalized.pan()).isEqualTo(request.pan());
        assertThat(normalized.amount()).isEqualByComparingTo(request.amount());
        assertThat(normalized.issuerId()).isEqualTo(request.issuerId());
    }
}
