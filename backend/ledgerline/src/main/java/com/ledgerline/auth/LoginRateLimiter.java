package com.ledgerline.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class LoginRateLimiter {

    private final int maxAttempts;
    private final Duration windowDuration;
    private final int maxTrackedKeys;
    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();

    @Autowired
    public LoginRateLimiter() {
        this(5, Duration.ofMinutes(15), 10_000, Clock.systemUTC());
    }

    public LoginRateLimiter(int maxAttempts, Duration windowDuration, int maxTrackedKeys, Clock clock) {
        this.maxAttempts = maxAttempts;
        this.windowDuration = windowDuration;
        this.maxTrackedKeys = maxTrackedKeys;
        this.clock = clock;
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
        attempts.remove(toEmailKey(email));
        // Shared IP counter is intentionally NOT reset on individual success
    }

    public void recordFailure(String ip, String email) {
        Instant now = clock.instant();
        tryReserve(toIpKey(ip), now);
        tryReserve(toEmailKey(email), now);
    }

    public void reset() {
        attempts.clear();
    }

    private long tryReserve(String key, Instant now) {
        if (attempts.size() >= maxTrackedKeys && !attempts.containsKey(key)) {
            evictExpiredKeys(now);
            if (attempts.size() >= maxTrackedKeys) {
                Iterator<String> it = attempts.keySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
        }

        AtomicLong retryAfter = new AtomicLong(0);
        attempts.compute(key, (k, deque) -> {
            Deque<Instant> current = (deque == null) ? new ArrayDeque<>() : deque;
            cleanExpired(current, now);
            if (current.size() >= maxAttempts) {
                Instant oldest = current.peekFirst();
                long seconds = oldest != null
                        ? Duration.between(now, oldest.plus(windowDuration)).toSeconds()
                        : windowDuration.toSeconds();
                retryAfter.set(Math.max(1, seconds));
                return current;
            }
            current.addLast(now);
            return current;
        });
        return retryAfter.get();
    }

    private void rollbackAttempt(String key, Instant now) {
        attempts.computeIfPresent(key, (k, deque) -> {
            deque.removeLastOccurrence(now);
            return deque.isEmpty() ? null : deque;
        });
    }

    private long getRetryAfterSecondsIfExceeded(String key, Instant now) {
        AtomicLong retryAfter = new AtomicLong(0);
        attempts.computeIfPresent(key, (k, deque) -> {
            cleanExpired(deque, now);
            if (deque.size() >= maxAttempts) {
                Instant oldest = deque.peekFirst();
                if (oldest != null) {
                    long seconds = Duration.between(now, oldest.plus(windowDuration)).toSeconds();
                    retryAfter.set(Math.max(1, seconds));
                } else {
                    retryAfter.set(windowDuration.toSeconds());
                }
            }
            return deque.isEmpty() ? null : deque;
        });
        return retryAfter.get();
    }

    private void cleanExpired(Deque<Instant> deque, Instant now) {
        Instant cutoff = now.minus(windowDuration);
        while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
            deque.pollFirst();
        }
    }

    private void evictExpiredKeys(Instant now) {
        for (String key : attempts.keySet()) {
            attempts.computeIfPresent(key, (k, deque) -> {
                cleanExpired(deque, now);
                return deque.isEmpty() ? null : deque;
            });
        }
    }

    private String toIpKey(String ip) {
        String cleanIp = (ip == null ? "unknown" : ip.trim());
        if (cleanIp.length() > 64) {
            cleanIp = cleanIp.substring(0, 64);
        }
        return "ip:" + cleanIp;
    }

    private String toEmailKey(String email) {
        String cleanEmail = (email == null ? "unknown" : AuthService.normalizeEmail(email));
        if (cleanEmail.length() > 255) {
            cleanEmail = cleanEmail.substring(0, 255);
        }
        return "email:" + cleanEmail;
    }
}
