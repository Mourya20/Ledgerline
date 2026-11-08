package com.ledgerline.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    public void checkAllowed(String ip, String email) {
        Instant now = clock.instant();
        String ipKey = toIpKey(ip);
        String emailKey = toEmailKey(email);

        if (isExceeded(ipKey, now) || isExceeded(emailKey, now)) {
            throw new RateLimitExceededException("Too many login attempts. Please try again later.");
        }
    }

    public void recordSuccess(String ip, String email) {
        attempts.remove(toIpKey(ip));
        attempts.remove(toEmailKey(email));
    }

    public void recordFailure(String ip, String email) {
        Instant now = clock.instant();
        registerFailure(toIpKey(ip), now);
        registerFailure(toEmailKey(email), now);
    }

    public void reset() {
        attempts.clear();
    }

    private boolean isExceeded(String key, Instant now) {
        Deque<Instant> timestamps = attempts.get(key);
        if (timestamps == null) {
            return false;
        }
        synchronized (timestamps) {
            cleanExpired(timestamps, now);
            return timestamps.size() >= maxAttempts;
        }
    }

    private void registerFailure(String key, Instant now) {
        if (attempts.size() >= maxTrackedKeys && !attempts.containsKey(key)) {
            evictExpiredKeys(now);
            if (attempts.size() >= maxTrackedKeys) {
                // Remove one arbitrary entry to enforce the strict memory bound
                Iterator<String> it = attempts.keySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
        }

        attempts.compute(key, (k, deque) -> {
            if (deque == null) {
                deque = new ArrayDeque<>();
            }
            synchronized (deque) {
                cleanExpired(deque, now);
                deque.addLast(now);
            }
            return deque;
        });
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
        return "ip:" + (ip == null ? "unknown" : ip.trim());
    }

    private String toEmailKey(String email) {
        return "email:" + (email == null ? "unknown" : AuthService.normalizeEmail(email));
    }
}
