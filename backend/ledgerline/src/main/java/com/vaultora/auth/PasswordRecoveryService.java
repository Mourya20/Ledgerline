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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vaultora.audit.AuditService;
import com.vaultora.domain.PasswordResetTokenEntity;
import com.vaultora.domain.UserEntity;
import com.vaultora.repository.PasswordResetTokenRepository;
import com.vaultora.repository.UserRepository;

@Service
public class PasswordRecoveryService {

    private static final Logger logger = LoggerFactory.getLogger(PasswordRecoveryService.class);

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;

    public PasswordRecoveryService(
            UserRepository userRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            PasswordEncoder passwordEncoder,
            AuditService auditService,
            JdbcTemplate jdbcTemplate) {
        this.userRepository = userRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Map<String, String> requestPasswordReset(String rawEmail, String clientIp) {
        String normalizedEmail = AuthService.normalizeEmail(rawEmail);
        Optional<UserEntity> userOpt = userRepository.findByEmailIgnoreCase(normalizedEmail);

        if (userOpt.isPresent()) {
            UserEntity user = userOpt.get();
            passwordResetTokenRepository.deleteAllByUserId(user.getId());

            String rawToken = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
            String tokenHash = hashToken(rawToken);

            Instant expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES);
            PasswordResetTokenEntity tokenEntity = new PasswordResetTokenEntity(user, tokenHash, expiresAt);
            passwordResetTokenRepository.save(tokenEntity);

            logger.info("[PASSWORD-RESET] Reset link generated for user {}: /reset-password?token={}", user.getEmail(), rawToken);

            auditService.recordEvent(
                    user,
                    "PASSWORD_RESET_REQUESTED",
                    "USER",
                    user.getId(),
                    Map.of("email", user.getEmail(), "ip", clientIp != null ? clientIp : "unknown")
            );
        }

        return Map.of("message", "If an account exists with that email, a password reset link has been generated.");
    }

    @Transactional
    public Map<String, String> resetPassword(ResetPasswordRequest request, String clientIp) {
        String rawToken = request.token().trim();
        String tokenHash = hashToken(rawToken);

        PasswordResetTokenEntity tokenEntity = passwordResetTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired password reset token"));

        if (tokenEntity.isUsed()) {
            throw new IllegalArgumentException("Password reset token has already been used");
        }

        if (tokenEntity.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Password reset token has expired");
        }

        UserEntity user = tokenEntity.getUser();
        String encodedNewPassword = passwordEncoder.encode(request.newPassword());
        jdbcTemplate.update("CALL change_user_password(?, ?)", user.getId(), encodedNewPassword);

        tokenEntity.setUsed(true);
        passwordResetTokenRepository.save(tokenEntity);

        auditService.recordEvent(
                user,
                "USER_PASSWORD_RESET_SUCCESS",
                "USER",
                user.getId(),
                Map.of("email", user.getEmail(), "ip", clientIp != null ? clientIp : "unknown")
        );

        return Map.of("message", "Password has been reset successfully. You may now sign in.");
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
