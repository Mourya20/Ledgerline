package com.ledgerline.auth;

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
 * In-memory sliding-window login rate limiter enforcing independent IP and identifier limits.
 * Uses a thread-safe, access-ordered LRU map to bound memory footprint and evict the
 * least-recently-used key when capacity is exceeded.
 *
 * Default configuration: 5 attempts per 15-minute window, bounded at 10,000 tracked keys.
 */
@Component
public class LoginRateLimiter {

    private final int maxAttempts;
    private final Duration windowDuration;
    private final int maxTrackedKeys;
    private final Clock clock;
    private final Map<String, Deque<Instant>> attempts;

    @Autowired
    public LoginRateLimiter() {
        this(5, Duration.ofMinutes(15), 10_000, Clock.systemUTC());
    }

    public LoginRateLimiter(int maxAttempts, Duration windowDuration, int maxTrackedKeys, Clock clock) {
        this.maxAttempts = maxAttempts;
        this.windowDuration = windowDuration;
        this.maxTrackedKeys = maxTrackedKeys;
        this.clock = clock;
        this.attempts = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Deque<Instant>> eldest) {
                return size() > maxTrackedKeys;
            }
        };
    }

    /**
     * Atomically checks limits and reserves an attempt for both the client IP and the email identifier.
     * Throws RateLimitExceededException if either IP or identifier threshold is exceeded.
     */
    public void acquireAttempt(String ip, String email) {
        Instant now = clock.instant();
        String ipKey = toIpKey(ip);
        String emailKey = toEmailKey(email);

        long ipRetry = tryReserve(ipKey, now);
        if (ipRetry > 0) {
            throw new RateLimitExceededException("Too many login attempts. Please try again later.", ipRetry);
        }

        long emailRetry = tryReserve(emailKey, now);
        if (emailRetry > 0) {
            rollbackAttempt(ipKey, now);
            throw new RateLimitExceededException("Too many login attempts. Please try again later.", emailRetry);
        }
    }

    public void checkAllowed(String ip, String email) {
        Instant now = clock.instant();
        String ipKey = toIpKey(ip);
        String emailKey = toEmailKey(email);

        long ipRetry = getRetryAfterSecondsIfExceeded(ipKey, now);
        if (ipRetry > 0) {
            throw new RateLimitExceededException("Too many login attempts. Please try again later.", ipRetry);
        }
        long emailRetry = getRetryAfterSecondsIfExceeded(emailKey, now);
        if (emailRetry > 0) {
            throw new RateLimitExceededException("Too many login attempts. Please try again later.", emailRetry);
        }
    }

    public void recordSuccess(String ip, String email) {
        synchronized (attempts) {
            attempts.remove(toEmailKey(email));
            // Shared IP counter is intentionally NOT reset on individual success
        }
    }

    public void recordFailure(String ip, String email) {
        Instant now = clock.instant();
        tryReserve(toIpKey(ip), now);
        tryReserve(toEmailKey(email), now);
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

    private long tryReserve(String key, Instant now) {
        synchronized (attempts) {
            Deque<Instant> deque = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
            cleanExpired(deque, now);
            if (deque.size() >= maxAttempts) {
                Instant oldest = deque.peekFirst();
                long seconds = oldest != null
                        ? Duration.between(now, oldest.plus(windowDuration)).toSeconds()
                        : windowDuration.toSeconds();
                return Math.max(1, seconds);
            }
            deque.addLast(now);
            return 0;
        }
    }

    private void rollbackAttempt(String key, Instant now) {
        synchronized (attempts) {
            Deque<Instant> deque = attempts.get(key);
            if (deque != null) {
                deque.removeLastOccurrence(now);
                if (deque.isEmpty()) {
                    attempts.remove(key);
                }
            }
        }
    }

    private long getRetryAfterSecondsIfExceeded(String key, Instant now) {
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
            if (deque.size() >= maxAttempts) {
                Instant oldest = deque.peekFirst();
                long seconds = oldest != null
                        ? Duration.between(now, oldest.plus(windowDuration)).toSeconds()
                        : windowDuration.toSeconds();
                return Math.max(1, seconds);
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
}
