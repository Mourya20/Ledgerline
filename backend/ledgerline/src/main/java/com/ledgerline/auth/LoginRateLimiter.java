package com.ledgerline.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

@Component
public class LoginRateLimiter {

    private final int maxAttempts;
    private final Duration windowDuration;
    private final int maxTrackedKeys;
    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();

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
            if (deque == null) {
                deque = new ArrayDeque<>();
            }
            synchronized (deque) {
                cleanExpired(deque, now);
                if (deque.size() >= maxAttempts) {
                    Instant oldest = deque.peekFirst();
                    long seconds = oldest != null
                            ? Duration.between(now, oldest.plus(windowDuration)).toSeconds()
                            : windowDuration.toSeconds();
                    retryAfter.set(Math.max(1, seconds));
                    return deque;
                }
                deque.addLast(now);
                return deque;
            }
        });
        return retryAfter.get();
    }

    private void rollbackAttempt(String key, Instant now) {
        Deque<Instant> deque = attempts.get(key);
        if (deque != null) {
            synchronized (deque) {
                deque.removeLastOccurrence(now);
            }
        }
    }

    private long getRetryAfterSecondsIfExceeded(String key, Instant now) {
        Deque<Instant> timestamps = attempts.get(key);
        if (timestamps == null) {
            return 0;
        }
        synchronized (timestamps) {
            cleanExpired(timestamps, now);
            if (timestamps.size() >= maxAttempts) {
                Instant oldest = timestamps.peekFirst();
                if (oldest != null) {
                    long seconds = Duration.between(now, oldest.plus(windowDuration)).toSeconds();
                    return Math.max(1, seconds);
                }
                return windowDuration.toSeconds();
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

    private void evictExpiredKeys(Instant now) {
        Instant cutoff = now.minus(windowDuration);
        for (Map.Entry<String, Deque<Instant>> entry : attempts.entrySet()) {
            Deque<Instant> deque = entry.getValue();
            synchronized (deque) {
                cleanExpired(deque, now);
                if (deque.isEmpty()) {
                    attempts.remove(entry.getKey(), deque);
                }
            }
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
