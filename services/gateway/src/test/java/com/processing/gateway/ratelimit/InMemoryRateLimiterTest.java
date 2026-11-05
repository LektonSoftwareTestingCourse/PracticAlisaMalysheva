package com.processing.gateway.ratelimit;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRateLimiterTest {

    @Test
    @DisplayName("Если лимитер создан с capacity=2, refill=0 и по ключу \"key\" трижды "
            + "вызван allowRequest, то первые два вызова возвращают true, а третий false")
    void shouldLimitRequestsToCapacity() {
        MutableTicker ticker = new MutableTicker(0);
        InMemoryRateLimiter limiter = InMemoryRateLimiter.forTesting(2, 0, Duration.ofMinutes(10), 100, ticker);

        assertThat(limiter.allowRequest("key")).isTrue();
        assertThat(limiter.allowRequest("key")).isTrue();
        assertThat(limiter.allowRequest("key")).isFalse();
    }

    @Test
    @DisplayName("Если лимитер с capacity=1 и refill=1 токен в секунду, "
            + "токен израсходован (первый allowRequest вернул true, "
            + "второй false) и прошла 1 секунда, то следующий allowRequest "
            + "возвращает true (токен восполнен)")
    void shouldRefillTokensOverTime() {
        MutableTicker ticker = new MutableTicker(0);
        InMemoryRateLimiter limiter = InMemoryRateLimiter.forTesting(1, 1, Duration.ofMinutes(10), 100, ticker);

        assertThat(limiter.allowRequest("key")).isTrue();
        assertThat(limiter.allowRequest("key")).isFalse();

        ticker.advance(1_000_000_000L); // 1 second

        assertThat(limiter.allowRequest("key")).isTrue();
    }

    @Test
    @DisplayName("Если лимитер с capacity=1 и refill=10 токенов в секунду, "
            + "токен израсходован и прошло 10 секунд (накоплено 100 токенов, "
            + "но не выше потолка capacity=1), то первый allowRequest возвращает true, "
            + "а второй снова false")
    void shouldCapTokensAtCapacity() {
        MutableTicker ticker = new MutableTicker(0);
        InMemoryRateLimiter limiter = InMemoryRateLimiter.forTesting(1, 10, Duration.ofMinutes(10), 100, ticker);

        assertThat(limiter.allowRequest("key")).isTrue();
        assertThat(limiter.allowRequest("key")).isFalse();

        ticker.advance(10_000_000_000L); // 10 seconds -> 100 tokens, capped at capacity 1

        assertThat(limiter.allowRequest("key")).isTrue();
        assertThat(limiter.allowRequest("key")).isFalse();
    }

    @Test
    @DisplayName("Если лимитер с capacity=1, refill=0 и вызваны allowRequest "
            + "для разных ключей \"a\" и \"b\", то оба вызова возвращают true, "
            + "так как для каждого ключа ведётся отдельный бакет")
    void shouldKeepIndependentBucketsPerKey() {
        MutableTicker ticker = new MutableTicker(0);
        InMemoryRateLimiter limiter = InMemoryRateLimiter.forTesting(1, 0, Duration.ofMinutes(10), 100, ticker);

        assertThat(limiter.allowRequest("a")).isTrue();
        assertThat(limiter.allowRequest("b")).isTrue();
    }

    /** Ticker с управляемым значением для детерминированных тестов. */
    private static final class MutableTicker implements Ticker {
        private long nanos;

        private MutableTicker(long nanos) {
            this.nanos = nanos;
        }

        private void advance(long deltaNanos) {
            this.nanos += deltaNanos;
        }

        @Override
        public long read() {
            return nanos;
        }
    }
}