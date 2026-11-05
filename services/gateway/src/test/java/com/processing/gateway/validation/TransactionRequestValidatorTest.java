package com.processing.gateway.validation;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionRequestValidatorTest {

    private final TransactionRequestValidator validator = new TransactionRequestValidator();

    @Test
    @DisplayName("Если запрос валиден (mti=\"0100\", pan из ровно 16 цифр, amount>0, "
            + "currencyCode из 3 символов, mcc из 4 цифр, acquirerId не пуст), "
            + "то validator не бросает исключение")
    void shouldAcceptValidRequest() {
        assertThatCode(() -> validator.validate(validRequest().build())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Если в validator передаётся null-запрос, то выбрасывается "
            + "TransactionValidationException с сообщением \"Request body is required\"")
    void shouldRejectNullRequest() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Request body is required");
    }

    @Test
    @DisplayName("Если mti пустой (\"\"), то выбрасывается TransactionValidationException "
            + "с упоминанием поля \"mti\"")
    void shouldRejectBlankMti() {
        assertThatThrownBy(() -> validator.validate(validRequest().mti("").build()))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("mti");
    }

    @Test
    @DisplayName("Если pan пустой (null), то выбрасывается TransactionValidationException "
            + "с упоминанием поля \"pan\"")
    void shouldRejectBlankPan() {
        assertThatThrownBy(() -> validator.validate(validRequest().pan(null).build()))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("pan");
    }

    @Test
    @DisplayName("Если acquirerId состоит только из пробелов (\"  \"), то "
            + "выбрасывается TransactionValidationException с упоминанием поля \"acquirerId\"")
    void shouldRejectBlankAcquirerId() {
        assertThatThrownBy(() -> validator.validate(validRequest().acquirerId("  ").build()))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("acquirerId");
    }

    @Test
    @DisplayName("Если mti=\"0200\" (отличен от \"0100\"), то выбрасывается "
            + "TransactionValidationException с сообщением \"Field 'mti' must be '0100'\"")
    void shouldRejectWrongMti() {
        assertThatThrownBy(() -> validator.validate(validRequest().mti("0200").build()))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'mti' must be '0100'");
    }

    @ParameterizedTest(name = "pan={0}")
    @DisplayName("Если pan=\"4000001234\" (10 цифр), pan=\"40000012345678901\" (17 цифр) или "
            + "pan=\"400000123456789a\" (содержит нецифровой символ), то выбрасывается "
            + "TransactionValidationException с сообщением \"Field 'pan' must be exactly 16 digits\"")
    @CsvSource({
            "4000001234",
            "40000012345678901",
            "400000123456789a"
    })
    void shouldRejectInvalidPan(String pan) {
        assertThatThrownBy(() -> validator.validate(validRequest().pan(pan).build()))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'pan' must be exactly 16 digits");
    }

    @Test
    @DisplayName("Если amount равен null, то выбрасывается TransactionValidationException "
            + "с сообщением \"Field 'amount' is required\"")
    void shouldRejectNullAmount() {
        assertThatThrownBy(() -> validator.validate(validRequest().amount(null).build()))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'amount' is required");
    }

    @ParameterizedTest(name = "amount={0}")
    @DisplayName("Если amount=0 или amount=-5 (неположительный), то выбрасывается "
            + "TransactionValidationException с сообщением \"Field 'amount' must be > 0\"")
    @CsvSource("0,-5")
    void shouldRejectNonPositiveAmount(String amount) {
        assertThatThrownBy(() -> validator.validate(validRequest().amount(new BigDecimal(amount)).build()))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'amount' must be > 0");
    }

    @ParameterizedTest(name = "currencyCode={0}")
    @DisplayName("Если currencyCode=\"64\" (2 символа) или currencyCode=\"6433\" (4 символа), "
            + "то выбрасывается TransactionValidationException с сообщением "
            + "\"Field 'currencyCode' must be exactly 3 characters\"")
    @CsvSource({"64", "6433"})
    void shouldRejectInvalidCurrencyLength(String currencyCode) {
        assertThatThrownBy(() -> validator.validate(validRequest().currencyCode(currencyCode).build()))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'currencyCode' must be exactly 3 characters");
    }

    @ParameterizedTest(name = "mcc={0}")
    @DisplayName("Если mcc=\"50\" (2 цифры), mcc=\"50455\" (5 цифр) или mcc=\"50a5\" "
            + "(содержит нецифровой символ), то выбрасывается "
            + "TransactionValidationException с сообщением \"Field 'mcc' must be exactly 4 digits\"")
    @CsvSource({"50", "50455", "50a5"})
    void shouldRejectInvalidMcc(String mcc) {
        assertThatThrownBy(() -> validator.validate(validRequest().mcc(mcc).build()))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'mcc' must be exactly 4 digits");
    }

    private AuthorizationRequest.AuthorizationRequestBuilder validRequest() {
        return AuthorizationRequest.builder()
                .mti("0100")
                .stan("000001")
                .pan("4000001234567890")
                .processingCode("000000")
                .amount(new BigDecimal("1000"))
                .currencyCode("643")
                .transmissionDateTime(Instant.now())
                .terminalId("TERM0001")
                .terminalType("POS")
                .merchantId("MERCH0000000002")
                .mcc("5045")
                .acquirerId("ACQ002")
                .issuerId("ISS001");
    }
}
