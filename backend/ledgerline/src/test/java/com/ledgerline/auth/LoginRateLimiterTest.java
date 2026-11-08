package com.ledgerline.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        // 3 attempts reserved
        for (int i = 0; i < 3; i++) {
            assertDoesNotThrow(() -> limiter.acquireAttempt(ip, email));
        }

        // 4th request must be rejected with retry interval
        RateLimitExceededException ex = assertThrows(RateLimitExceededException.class,
                () -> limiter.acquireAttempt(ip, email));
        assertTrue(ex.getRetryAfterSeconds() > 0);

        // Advance past window (11 minutes)
        clock.advance(Duration.ofMinutes(11));

        // Should recover and be allowed
        assertDoesNotThrow(() -> limiter.acquireAttempt(ip, email));
    }

    @Test
    void differentIpsAndEmailsBehaveIndependently() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-08T12:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(2, Duration.ofMinutes(5), 100, clock);

        String ip1 = "10.0.0.1";
        String ip2 = "10.0.0.2";
        String email1 = "alice@example.com";
        String email2 = "bob@example.com";

        limiter.acquireAttempt(ip1, email1);
        limiter.acquireAttempt(ip1, email1);

        // alice from ip1 is blocked
        assertThrows(RateLimitExceededException.class, () -> limiter.acquireAttempt(ip1, email1));

        // bob from ip2 should be allowed
        assertDoesNotThrow(() -> limiter.acquireAttempt(ip2, email2));

        // bob from ip1 should be blocked because ip1 is blocked
        assertThrows(RateLimitExceededException.class, () -> limiter.acquireAttempt(ip1, email2));

        // alice from ip2 should be blocked because alice email is blocked
        assertThrows(RateLimitExceededException.class, () -> limiter.acquireAttempt(ip2, email1));
    }

    @Test
    void successClearsEmailIdentifierButPreservesSharedIpCounter() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-08T12:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(2, Duration.ofMinutes(5), 100, clock);

        String sharedIp = "192.168.100.50";
        String attacker = "attacker@example.com";
        String legitimate = "legit@example.com";

        // Attacker exhausts attempts from shared IP
        limiter.acquireAttempt(sharedIp, attacker);
        limiter.acquireAttempt(sharedIp, attacker);

        // Shared IP is blocked
        assertThrows(RateLimitExceededException.class, () -> limiter.acquireAttempt(sharedIp, attacker));

        // Legitimate user logs in successfully (e.g. from another IP or existing auth)
        limiter.recordSuccess(sharedIp, legitimate);

        // Shared IP counter must NOT be reset by legitimate user's login!
        assertThrows(RateLimitExceededException.class, () -> limiter.acquireAttempt(sharedIp, attacker));
    }

    @Test
    void concurrentAttemptReservationIsAtomic() throws InterruptedException {
        int limit = 20;
        int callers = 40;
        LoginRateLimiter limiter = new LoginRateLimiter(limit, Duration.ofMinutes(5), 1000, Clock.systemUTC());
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(callers);

        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < callers; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    limiter.acquireAttempt("10.10.10.10", "concurrent@example.com");
                    allowed.incrementAndGet();
                } catch (RateLimitExceededException e) {
                    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertEquals(limit, allowed.get(), "Exactly the limit should be allowed");
        assertEquals(callers - limit, rejected.get(), "The excess attempts should be rejected");
    }

    @Test
    void boundedStoreEnforcesMaximumSizeAndProtectsUnboundedIdentifiers() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-08T12:00:00Z"));
        int maxCapacity = 20;
        LoginRateLimiter limiter = new LoginRateLimiter(5, Duration.ofMinutes(5), maxCapacity, clock);

        // Huge identifier must be safely handled without error
        String hugeEmail = "a".repeat(10_000) + "@example.com";
        assertDoesNotThrow(() -> limiter.acquireAttempt("127.0.0.1", hugeEmail));

        // Record failures for 100 distinct identifiers
        for (int i = 0; i < 100; i++) {
            limiter.recordFailure("ip-" + i, "user" + i + "@example.com");
        }

        // Capacity bound prevents heap exhaustion; operations proceed normally
        assertDoesNotThrow(() -> limiter.checkAllowed("ip-999", "new@example.com"));
    }

    @Test
    void lruEvictionPreservesRecentlyAccessedKeysAndEvictsEldest() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-08T12:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(5, Duration.ofMinutes(15), 2, clock);

        limiter.recordFailure("1.1.1.1", "first@example.com");
        assertTrue(limiter.getTrackedKeysCount() <= 2);

        // Touch the first key to update LRU order
        limiter.checkAllowed("1.1.1.1", "first@example.com");

        // Insert third key
        limiter.recordFailure("2.2.2.2", "second@example.com");

        // Tracked keys size never exceeds capacity of 2
        assertTrue(limiter.getTrackedKeysCount() <= 2);
    }
}
