package com.vaultora.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * In-memory sliding-window failed login rate limiter enforcing independent IP and email failure limits.
 * Uses a thread-safe, access-ordered LRU map to bound memory footprint and evict the
 * least-recently-used key when capacity is exceeded.
 *
 * Policy:
 * - Maximum 5 failed attempts per normalized email within 60 seconds.
 * - Maximum 30 failed attempts per client IP within 60 seconds.
 * - Maximum cooldown: 60 seconds (returns actual remaining cooldown in Retry-After header).
 * - Successful logins do not consume failed-login allowances.
 * - Failed credential attempts increment applicable counters.
 * - Successful authentication clears the corresponding email failure counter.
 * - Shared IP failure history is not reset when another account authenticates successfully.
 */
@Component
public class LoginRateLimiter {

    private final int maxEmailAttempts;
    private final int maxIpAttempts;
    private final Duration windowDuration;
    private final int maxTrackedKeys;
    private final Clock clock;
    private final Map<String, Deque<Instant>> attempts;

    @Autowired
    public LoginRateLimiter() {
        this(5, 30, Duration.ofSeconds(60), 10_000, Clock.systemUTC());
    }

    public LoginRateLimiter(int maxEmailAttempts, int maxIpAttempts, Duration windowDuration, int maxTrackedKeys, Clock clock) {
        this.maxEmailAttempts = maxEmailAttempts;
        this.maxIpAttempts = maxIpAttempts;
        this.windowDuration = windowDuration;
        this.maxTrackedKeys = maxTrackedKeys;
        this.clock = clock;
        this.attempts = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Deque<Instant>> eldest) {
                return size() > LoginRateLimiter.this.maxTrackedKeys;
            }
        };
    }

    public int getMaxTrackedKeys() {
        return maxTrackedKeys;
    }

    /**
     * Backward-compatible constructor for tests configuring a single symmetric limit.
     */
    public LoginRateLimiter(int maxAttempts, Duration windowDuration, int maxTrackedKeys, Clock clock) {
        this(maxAttempts, maxAttempts, windowDuration, maxTrackedKeys, clock);
    }

    /**
     * Checks whether the client IP or email is currently blocked due to exceeded failed attempts.
     * Throws RateLimitExceededException with the remaining cooldown if either threshold is reached.
     */
    public void checkAllowed(String ip, String email) {
        Instant now = clock.instant();
        String ipKey = toIpKey(ip);
        String emailKey = toEmailKey(email);

        long ipRetry = getRetryAfterSecondsIfExceeded(ipKey, maxIpAttempts, now);
        if (ipRetry > 0) {
            throw new RateLimitExceededException("Too many login attempts. Please try again later.", ipRetry);
        }
        long emailRetry = getRetryAfterSecondsIfExceeded(emailKey, maxEmailAttempts, now);
        if (emailRetry > 0) {
            throw new RateLimitExceededException("Too many login attempts. Please try again later.", emailRetry);
        }
    }

    /**
     * Records a failed credential attempt for both the client IP and email.
     */
    public void recordFailure(String ip, String email) {
        Instant now = clock.instant();
        synchronized (attempts) {
            recordAttempt(toIpKey(ip), now);
            recordAttempt(toEmailKey(email), now);
        }
    }

    /**
     * Clears the email failure history upon successful authentication.
     * The shared IP counter is preserved so distributed credential stuffing cannot bypass limits.
     */
    public void recordSuccess(String ip, String email) {
        synchronized (attempts) {
            attempts.remove(toEmailKey(email));
        }
    }

    /**
     * Backward-compatible atomic attempt acquisition for concurrent tests:
     * atomically checks limit and records attempt inside synchronized block.
     */
    public void acquireAttempt(String ip, String email) {
        Instant now = clock.instant();
        String ipKey = toIpKey(ip);
        String emailKey = toEmailKey(email);
        synchronized (attempts) {
            long ipRetry = getRetryAfterSecondsIfExceeded(ipKey, maxIpAttempts, now);
            if (ipRetry > 0) {
                throw new RateLimitExceededException("Too many login attempts. Please try again later.", ipRetry);
            }
            long emailRetry = getRetryAfterSecondsIfExceeded(emailKey, maxEmailAttempts, now);
            if (emailRetry > 0) {
                throw new RateLimitExceededException("Too many login attempts. Please try again later.", emailRetry);
            }
            recordAttempt(ipKey, now);
            recordAttempt(emailKey, now);
        }
    }

    public void reset() {
        synchronized (attempts) {
            attempts.clear();
        }
    }

    public boolean containsKey(String key) {
        synchronized (attempts) {
            return attempts.containsKey(key);
        }
    }

    public int getTrackedKeysCount() {
        synchronized (attempts) {
            return attempts.size();
        }
    }

    public int getFailureCount(String key) {
        synchronized (attempts) {
            Deque<Instant> deque = attempts.get(key);
            if (deque == null) return 0;
            cleanExpired(deque, clock.instant());
            return deque.size();
        }
    }

    private void recordAttempt(String key, Instant now) {
        Deque<Instant> deque = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
        cleanExpired(deque, now);
        deque.addLast(now);
    }

    private long getRetryAfterSecondsIfExceeded(String key, int limit, Instant now) {
        synchronized (attempts) {
            Deque<Instant> deque = attempts.get(key);
            if (deque == null) {
                return 0;
            }
            cleanExpired(deque, now);
            if (deque.isEmpty()) {
                attempts.remove(key);
                return 0;
            }
            if (deque.size() >= limit) {
                Instant oldest = deque.peekFirst();
                long seconds = oldest != null
                        ? Duration.between(now, oldest.plus(windowDuration)).toSeconds()
                        : windowDuration.toSeconds();
                return Math.max(1, Math.min(seconds, windowDuration.toSeconds()));
            }
            return 0;
        }
    }

    private void cleanExpired(Deque<Instant> deque, Instant now) {
        Instant cutoff = now.minus(windowDuration);
        while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
            deque.pollFirst();
        }
    }

    public String toIpKey(String ip) {
        String cleanIp = (ip == null ? "unknown" : ip.trim());
        if (cleanIp.length() > 64) {
            cleanIp = cleanIp.substring(0, 64);
        }
        return "ip:" + cleanIp;
    }

    public String toEmailKey(String email) {
        String cleanEmail = (email == null ? "unknown" : AuthService.normalizeEmail(email));
        if (cleanEmail.length() > 255) {
            cleanEmail = cleanEmail.substring(0, 255);
        }
        return "email:" + cleanEmail;
    }

    public int getMaxEmailAttempts() {
        return maxEmailAttempts;
    }

    public int getMaxIpAttempts() {
        return maxIpAttempts;
    }

    public Duration getWindowDuration() {
        return windowDuration;
    }
}
