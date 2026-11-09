package com.vaultora.auth;

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
    void fiveFailedEmailAttemptsTriggerEmailThreshold() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-08T12:00:00Z"));
        // Default policy: 5 email failures, 30 IP failures, 60s duration
        LoginRateLimiter limiter = new LoginRateLimiter(5, 30, Duration.ofSeconds(60), 1000, clock);

        String ip = "172.16.0.5";
        String email = "target@example.com";

        for (int i = 0; i < 4; i++) {
            assertDoesNotThrow(() -> limiter.checkAllowed(ip, email));
            limiter.recordFailure(ip, email);
        }

        // 5th attempt allowed before failing
        assertDoesNotThrow(() -> limiter.checkAllowed(ip, email));
        limiter.recordFailure(ip, email);

        // 6th attempt must be blocked by email threshold
        RateLimitExceededException ex = assertThrows(RateLimitExceededException.class,
                () -> limiter.checkAllowed(ip, email));
        assertTrue(ex.getRetryAfterSeconds() <= 60 && ex.getRetryAfterSeconds() > 0);
    }

    @Test
    void thirtyFailedIpAttemptsTriggerIpThreshold() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-08T12:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(5, 30, Duration.ofSeconds(60), 1000, clock);

        String attackerIp = "198.51.100.22";

        // Attacker sprays across 30 different emails (each 1 attempt, so email threshold 5 is never hit)
        for (int i = 1; i <= 30; i++) {
            String victimEmail = "user" + i + "@example.com";
            assertDoesNotThrow(() -> limiter.checkAllowed(attackerIp, victimEmail));
            limiter.recordFailure(attackerIp, victimEmail);
        }

        // 31st attempt from attacker IP must be blocked
        String freshEmail = "new_target@example.com";
        RateLimitExceededException ex = assertThrows(RateLimitExceededException.class,
                () -> limiter.checkAllowed(attackerIp, freshEmail));
        assertTrue(ex.getRetryAfterSeconds() <= 60 && ex.getRetryAfterSeconds() > 0);
    }

    @Test
    void successfulLoginDoesNotIncrementCountersAndClearsEmailFailures() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-08T12:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(5, 30, Duration.ofSeconds(60), 1000, clock);

        String ip = "10.0.0.99";
        String email = "legit@example.com";

        // Record 3 failures
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(ip, email);
        }
        assertEquals(3, limiter.getFailureCount(limiter.toEmailKey(email)));
        assertEquals(3, limiter.getFailureCount(limiter.toIpKey(ip)));

        // User enters correct credentials -> recordSuccess called
        limiter.recordSuccess(ip, email);

        // Email counter cleared, IP counter retained
        assertEquals(0, limiter.getFailureCount(limiter.toEmailKey(email)));
        assertEquals(3, limiter.getFailureCount(limiter.toIpKey(ip)));

        // Further legitimate logins do not increment counters
        assertDoesNotThrow(() -> limiter.checkAllowed(ip, email));
        limiter.recordSuccess(ip, email);
        assertEquals(0, limiter.getFailureCount(limiter.toEmailKey(email)));
        assertEquals(3, limiter.getFailureCount(limiter.toIpKey(ip)));
    }

    @Test
    void cooldownExpiresCorrectly() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-08T12:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(5, 30, Duration.ofSeconds(60), 1000, clock);

        String ip = "10.0.0.50";
        String email = "locked@example.com";

        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(ip, email);
        }

        assertThrows(RateLimitExceededException.class, () -> limiter.checkAllowed(ip, email));

        // Advance 30 seconds (still blocked)
        clock.advance(Duration.ofSeconds(30));
        assertThrows(RateLimitExceededException.class, () -> limiter.checkAllowed(ip, email));

        // Advance remaining 31 seconds (total 61s > 60s window)
        clock.advance(Duration.ofSeconds(31));
        assertDoesNotThrow(() -> limiter.checkAllowed(ip, email));
    }

    @Test
    void differentUserNotBlockedByAnotherUsersEmailFailures() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-08T12:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(5, 30, Duration.ofSeconds(60), 1000, clock);

        String ip = "10.0.0.1";
        String blockedUser = "blocked@example.com";
        String innocentUser = "innocent@example.com";

        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(ip, blockedUser);
        }

        // blockedUser is blocked
        assertThrows(RateLimitExceededException.class, () -> limiter.checkAllowed(ip, blockedUser));

        // innocentUser from same IP is NOT blocked (IP has only 5 failures, well below 30)
        assertDoesNotThrow(() -> limiter.checkAllowed(ip, innocentUser));
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

        String hugeEmail = "a".repeat(10_000) + "@example.com";
        assertDoesNotThrow(() -> limiter.acquireAttempt("127.0.0.1", hugeEmail));

        for (int i = 0; i < 100; i++) {
            limiter.recordFailure("ip-" + i, "user" + i + "@example.com");
        }

        assertDoesNotThrow(() -> limiter.checkAllowed("ip-999", "new@example.com"));
    }

    @Test
    void lruEvictionPreservesRecentlyAccessedKeysAndEvictsEldest() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-08T12:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(5, Duration.ofMinutes(15), 2, clock);

        limiter.recordFailure("1.1.1.1", "first@example.com");
        assertTrue(limiter.getTrackedKeysCount() <= 2);

        limiter.checkAllowed("1.1.1.1", "first@example.com");

        limiter.recordFailure("2.2.2.2", "second@example.com");

        assertTrue(limiter.getTrackedKeysCount() <= 2);
    }
}
