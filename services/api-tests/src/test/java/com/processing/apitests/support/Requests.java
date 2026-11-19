package com.processing.apitests.support;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.cardmanagement.CreateCardRequest;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Фабрики тел запросов. Значения соответствуют контракту из {@code common}.
 */
public final class Requests {

    public static final String BIN = "400000";
    public static final String CURRENCY = "643";
    public static final String PROCESSING_CODE = "000000";
    public static final String MCC = "5411";
    public static final String ACQUIRER = "ACQ001";
    public static final String ISSUER = "ISS001";

    private Requests() {
    }

    public static CreateCardRequest createCard(BigDecimal dailyLimit,
                                               BigDecimal monthlyLimit,
                                               BigDecimal initialBalance) {
        return new CreateCardRequest(
                BIN,
                "IVAN IVANOV",
                CURRENCY,
                dailyLimit,
                monthlyLimit,
                initialBalance);
    }

    public static AuthorizationRequest authorize(String pan, String stan, BigDecimal amount) {
        return new AuthorizationRequest(
                "0100",
                stan,
                pan,
                PROCESSING_CODE,
                amount,
                CURRENCY,
                Instant.now(),
                TestData.terminalId(),
                "POS",
                TestData.merchantId(),
                MCC,
                ACQUIRER,
                ISSUER);
    }

    public static RollbackRequest rollback(String pan, String rrn, BigDecimal amount) {
        return new RollbackRequest(rrn, pan, amount);
    }
}