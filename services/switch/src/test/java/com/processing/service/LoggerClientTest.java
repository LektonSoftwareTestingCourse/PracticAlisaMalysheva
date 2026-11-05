package com.processing.service;

import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LoggerClientTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private LoggerClient loggerClient;

    @BeforeEach
    void setUp() {
        loggerClient = new LoggerClient(rabbitTemplate);
    }

    @Test
    @DisplayName("Если брокер принял сообщение (convertAndSend не бросает исключение), то log возвращает true и convertAndSend "
        + "вызван с EXCHANGE, ROUTING_KEY и TransactionRequest")
    void shouldReturnTrueWhenMessageAccepted() {
        // Arrange
        TransactionRequest transaction = validTransaction();

        // Act
        boolean logged = loggerClient.log(transaction);

        // Assert
        assertThat(logged).isTrue();
        verify(rabbitTemplate).convertAndSend(
                eq(com.processing.config.RabbitMQConfig.EXCHANGE),
                eq(com.processing.config.RabbitMQConfig.ROUTING_KEY),
                any(TransactionRequest.class));
    }

    @Test
    @DisplayName("Если convertAndSend бросает AmqpException, то log возвращает false")
    void shouldReturnFalseWhenPublishFails() {
        // Arrange
        TransactionRequest transaction = validTransaction();
        doThrow(new AmqpException("down")).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(TransactionRequest.class));

        // Act
        boolean logged = loggerClient.log(transaction);

        // Assert
        assertThat(logged).isFalse();
    }

    private TransactionRequest validTransaction() {
        return new TransactionRequest(
                UUID.randomUUID(),
                "0100",
                "000001",
                "012345678901",
                "4000001234567890",
                "000000",
                new BigDecimal("1000"),
                "643",
                "TERM0001",
                "POS",
                "MERCH0000000002",
                "5045",
                "ACQ002",
                "ISS001",
                new BigDecimal("10"),
                TransactionStatus.APPROVED,
                null,
                "TEST01",
                5,
                Instant.now(),
                Instant.now()
        );
    }
}