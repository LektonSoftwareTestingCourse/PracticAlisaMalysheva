package com.processing.gateway.circuitbreaker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryCircuitBreakerTest {

    private static final Duration OPEN_DURATION = Duration.ofSeconds(10);
    private static final int THRESHOLD = 2;

    @Test
    @DisplayName("Если контур находится в закрытом состоянии (CLOSED), "
            + "то allowRequest возвращает true")
    void shouldAllowWhenClosed() {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(0));
        InMemoryCircuitBreaker breaker = InMemoryCircuitBreaker.forTesting(OPEN_DURATION, THRESHOLD, clock);

        assertThat(breaker.allowRequest("svc")).isTrue();
    }

    @Test
    @DisplayName("Если зафиксирована первая ошибка "
            + "(не достигнут порог THRESHOLD=2), то allowRequest возвращает true, "
            + "а после второй ошибки (порог достигнут) контур переходит в OPEN "
            + "и allowRequest возвращает false")
    void shouldOpenAfterThresholdFailures() {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(0));
        InMemoryCircuitBreaker breaker = InMemoryCircuitBreaker.forTesting(OPEN_DURATION, THRESHOLD, clock);

        breaker.recordFailure("svc");
        assertThat(breaker.allowRequest("svc")).isTrue();

        breaker.recordFailure("svc");
        assertThat(breaker.allowRequest("svc")).isFalse();
    }

    @Test
    @DisplayName("Если контур открыт (OPEN) и после истечения openDuration (10 секунд) "
            + "разрешён один пробный запрос, то первый allowRequest возвращает true, "
            + "а второй false")
    void shouldAllowSingleProbeAfterOpenDuration() {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(0));
        InMemoryCircuitBreaker breaker = InMemoryCircuitBreaker.forTesting(OPEN_DURATION, THRESHOLD, clock);

        breaker.recordFailure("svc");
        breaker.recordFailure("svc");
        assertThat(breaker.allowRequest("svc")).isFalse();

        clock.advance(OPEN_DURATION);

        assertThat(breaker.allowRequest("svc")).isTrue();
        assertThat(breaker.allowRequest("svc")).isFalse();
    }

    @Test
    @DisplayName("Если контур в состоянии HALF_OPEN (после истечения openDuration) "
            + "и зафиксирован success, то контур закрывается "
            + "и allowRequest возвращает true")
    void shouldCloseOnSuccessInHalfOpen() {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(0));
        InMemoryCircuitBreaker breaker = InMemoryCircuitBreaker.forTesting(OPEN_DURATION, THRESHOLD, clock);

        breaker.recordFailure("svc");
        breaker.recordFailure("svc");
        clock.advance(OPEN_DURATION);

        assertThat(breaker.allowRequest("svc")).isTrue();
        breaker.recordSuccess("svc");
        assertThat(breaker.allowRequest("svc")).isTrue();
    }

    @Test
    @DisplayName("Если контур в состоянии HALF_OPEN (после истечения openDuration) "
            + "и зафиксирована ошибка, то контур снова открывается "
            + "и allowRequest возвращает false")
    void shouldReopenOnFailureInHalfOpen() {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(0));
        InMemoryCircuitBreaker breaker = InMemoryCircuitBreaker.forTesting(OPEN_DURATION, THRESHOLD, clock);

        breaker.recordFailure("svc");
        breaker.recordFailure("svc");
        clock.advance(OPEN_DURATION);

        assertThat(breaker.allowRequest("svc")).isTrue();
        breaker.recordFailure("svc");
        assertThat(breaker.allowRequest("svc")).isFalse();
    }

    @Test
    @DisplayName("Если контур в состоянии HALF_OPEN, пробный запрос разрешён "
            + "(allowRequest вернул true), а затем вызван releaseRequest, то слот "
            + "пробного запроса освобождается и следующий "
            + "allowRequest возвращает true")
    void shouldReleaseProbeSlot() {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(0));
        InMemoryCircuitBreaker breaker = InMemoryCircuitBreaker.forTesting(OPEN_DURATION, THRESHOLD, clock);

        breaker.recordFailure("svc");
        breaker.recordFailure("svc");
        clock.advance(OPEN_DURATION);

        assertThat(breaker.allowRequest("svc")).isTrue();
        breaker.releaseRequest("svc");
        assertThat(breaker.allowRequest("svc")).isTrue();
    }

    @Test
    @DisplayName("Если для сервиса \"a\" зафиксированы 2 ошибки "
            + "(порог достигнут), то allowRequest(\"a\") возвращает false, "
            + "а allowRequest(\"b\") возвращает true, так как состояние контура "
            + "для разных сервисов независимо")
    void shouldTrackIndependentStatePerService() {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(0));
        InMemoryCircuitBreaker breaker = InMemoryCircuitBreaker.forTesting(OPEN_DURATION, THRESHOLD, clock);

        breaker.recordFailure("a");
        breaker.recordFailure("a");

        assertThat(breaker.allowRequest("a")).isFalse();
        assertThat(breaker.allowRequest("b")).isTrue();
    }

    /** Clock с управляемым значением для детерминированных тестов. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
