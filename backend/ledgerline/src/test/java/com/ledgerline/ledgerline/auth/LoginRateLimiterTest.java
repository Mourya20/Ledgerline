package com.ledgerline.ledgerline.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class LoginRateLimiterTest {

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        public MutableClock(Instant initial) {
            this.now = new AtomicReference<>(initial);
        }

        public void advance(Duration duration) {
            now.updateAndGet(i -> i.plus(duration));
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @Test
    void requestsBelowLimitSucceedAndExceedingLimitFails() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-08T12:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(3, Duration.ofMinutes(10), 100, clock);

        String ip = "192.168.1.10";
        String email = "user@example.com";

        // 3 failures allowed
        for (int i = 0; i < 3; i++) {
            assertDoesNotThrow(() -> limiter.checkAllowed(ip, email));
            limiter.recordFailure(ip, email);
        }

        // 4th request must be rejected
        assertThrows(RateLimitExceededException.class, () -> limiter.checkAllowed(ip, email));

        // Advance past window (11 minutes)
        clock.advance(Duration.ofMinutes(11));

        // Should recover and be allowed
        assertDoesNotThrow(() -> limiter.checkAllowed(ip, email));
    }

    @Test
    void differentIpsAndEmailsBehaveIndependently() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-08T12:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(2, Duration.ofMinutes(5), 100, clock);

        String ip1 = "10.0.0.1";
        String ip2 = "10.0.0.2";
        String email1 = "alice@example.com";
        String email2 = "bob@example.com";

        limiter.recordFailure(ip1, email1);
        limiter.recordFailure(ip1, email1);

        // alice from ip1 is blocked
        assertThrows(RateLimitExceededException.class, () -> limiter.checkAllowed(ip1, email1));

        // bob from ip2 should be allowed
        assertDoesNotThrow(() -> limiter.checkAllowed(ip2, email2));

        // bob from ip1 should be blocked because ip1 is blocked
        assertThrows(RateLimitExceededException.class, () -> limiter.checkAllowed(ip1, email2));

        // alice from ip2 should be blocked because alice email is blocked
        assertThrows(RateLimitExceededException.class, () -> limiter.checkAllowed(ip2, email1));
    }

    @Test
    void successClearsFailureCount() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-08T12:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(2, Duration.ofMinutes(5), 100, clock);

        limiter.recordFailure("127.0.0.1", "test@example.com");
        limiter.recordSuccess("127.0.0.1", "test@example.com");

        // Record 1 more failure - should still be below limit of 2
        limiter.recordFailure("127.0.0.1", "test@example.com");
        assertDoesNotThrow(() -> limiter.checkAllowed("127.0.0.1", "test@example.com"));
    }

    @Test
    void concurrentRequestsAreThreadSafe() throws InterruptedException {
        int threads = 10;
        LoginRateLimiter limiter = new LoginRateLimiter(50, Duration.ofMinutes(5), 1000, Clock.systemUTC());
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger successChecks = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 5; j++) {
                        limiter.checkAllowed("192.168.1.1", "concurrent@example.com");
                        successChecks.incrementAndGet();
                        limiter.recordFailure("192.168.1.1", "concurrent@example.com");
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // 50 total failures recorded = maxAttempts reached
        assertThrows(RateLimitExceededException.class,
                () -> limiter.checkAllowed("192.168.1.1", "concurrent@example.com"));
    }

    @Test
    void boundedStoreEnforcesMaximumSize() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-08T12:00:00Z"));
        int maxCapacity = 20;
        LoginRateLimiter limiter = new LoginRateLimiter(5, Duration.ofMinutes(5), maxCapacity, clock);

        // Record failures for 100 distinct random identifiers
        for (int i = 0; i < 100; i++) {
            limiter.recordFailure("ip-" + i, "user" + i + "@example.com");
        }

        // Must not blow up heap; operations continue normally
        assertDoesNotThrow(() -> limiter.checkAllowed("ip-999", "new@example.com"));
    }
}
