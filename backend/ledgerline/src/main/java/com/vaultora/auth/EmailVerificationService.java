package com.vaultora.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vaultora.audit.AuditService;
import com.vaultora.domain.EmailVerificationTokenEntity;
import com.vaultora.domain.UserEntity;
import com.vaultora.domain.UserRole;
import com.vaultora.repository.EmailVerificationTokenRepository;
import com.vaultora.repository.UserRepository;

@Service
public class EmailVerificationService {

    private static final Logger logger = LoggerFactory.getLogger(EmailVerificationService.class);

    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final AuditService auditService;

    public EmailVerificationService(
            UserRepository userRepository,
            EmailVerificationTokenRepository emailVerificationTokenRepository,
            AuditService auditService) {
        this.userRepository = userRepository;
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
        this.auditService = auditService;
    }

    @Transactional
    public String createVerificationToken(UserEntity customer) {
        emailVerificationTokenRepository.deleteAllByUserId(customer.getId());

        String rawToken = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        String tokenHash = hashToken(rawToken);

        Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS);
        EmailVerificationTokenEntity tokenEntity = new EmailVerificationTokenEntity(customer, tokenHash, expiresAt);
        emailVerificationTokenRepository.save(tokenEntity);

        logger.info("[EMAIL-VERIFICATION] Verification link for user {}: /verify-email?token={}", customer.getEmail(), rawToken);

        return rawToken;
    }

    @Transactional
    public Map<String, String> verifyEmail(String rawToken, String clientIp) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Verification token is required");
        }

        String tokenHash = hashToken(rawToken.trim());
        EmailVerificationTokenEntity tokenEntity = emailVerificationTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired email verification token"));

        if (tokenEntity.isUsed()) {
            throw new IllegalArgumentException("Email verification token has already been used");
        }

        if (tokenEntity.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Email verification token has expired");
        }

        UserEntity user = tokenEntity.getUser();
        user.setEmailVerified(true);
        tokenEntity.setUsed(true);

        emailVerificationTokenRepository.save(tokenEntity);
        userRepository.save(user);

        auditService.recordEvent(
                user,
                "USER_EMAIL_VERIFIED",
                "USER",
                user.getId(),
                Map.of("email", user.getEmail(), "ip", clientIp != null ? clientIp : "unknown")
        );

        return Map.of("message", "Email verified successfully. You may now sign in.");
    }

    @Transactional
    public Map<String, String> resendVerification(String rawEmail, String clientIp) {
        String normalizedEmail = AuthService.normalizeEmail(rawEmail);
        Optional<UserEntity> userOpt = userRepository.findByEmailIgnoreCase(normalizedEmail);

        if (userOpt.isPresent()) {
            UserEntity user = userOpt.get();
            if (user.getRole() == UserRole.CUSTOMER && !user.isEmailVerified()) {
                String rawToken = createVerificationToken(user);
                auditService.recordEvent(
                        user,
                        "EMAIL_VERIFICATION_RESENT",
                        "USER",
                        user.getId(),
                        Map.of("email", user.getEmail(), "ip", clientIp != null ? clientIp : "unknown")
                );
            }
        }

        return Map.of("message", "If an unverified account exists with that email, a verification link has been sent.");
    }

    public static String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 digest not available", e);
        }
    }
}
