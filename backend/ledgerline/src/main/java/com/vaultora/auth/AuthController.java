package com.vaultora.auth;

import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vaultora.audit.AuditService;
import com.vaultora.domain.UserEntity;
import com.vaultora.domain.UserRole;
import com.vaultora.repository.UserRepository;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final LoginRateLimiter loginRateLimiter;
    private final AuditService auditService;
    private final PasswordRecoveryService passwordRecoveryService;
    private final EmailVerificationService emailVerificationService;

    public AuthController(
            AuthService authService,
            UserRepository userRepository,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            LoginRateLimiter loginRateLimiter,
            AuditService auditService,
            PasswordRecoveryService passwordRecoveryService,
            EmailVerificationService emailVerificationService) {
        this.authService = authService;
        this.userRepository = userRepository;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.loginRateLimiter = loginRateLimiter;
        this.auditService = auditService;
        this.passwordRecoveryService = passwordRecoveryService;
        this.emailVerificationService = emailVerificationService;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken csrfToken) {
        return Map.of("token", csrfToken.getToken());
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest) {
        UserEntity user = authService.register(request);
        String clientIp = httpRequest.getRemoteAddr();
        auditService.recordEvent(user, AuditService.ACTION_REGISTER_SUCCESS, "USER", user.getId(),
                Map.of("email", user.getEmail(), "role", user.getRole().name(), "ip", clientIp != null ? clientIp : "unknown"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new AuthResponse(user.getId(), user.getEmail(), user.getRole().name()));
    }

    @org.springframework.beans.factory.annotation.Value("${vaultora.auth.email-verification-required:true}")
    private boolean emailVerificationRequired;

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        String clientIp = httpRequest.getRemoteAddr();
        if (clientIp == null) {
            clientIp = "unknown";
        }

        try {
            loginRateLimiter.checkAllowed(clientIp, request.email());
        } catch (RateLimitExceededException ex) {
            auditService.recordEventRequiresNew(null, AuditService.ACTION_RATE_LIMIT_EXCEEDED, "USER", null,
                    Map.of("email", AuthService.normalizeEmail(request.email()), "ip", clientIp, "retryAfterSeconds", ex.getRetryAfterSeconds()));
            throw ex;
        }

        // Email verification check for CUSTOMER role
        if (emailVerificationRequired) {
            Optional<UserEntity> candidateUser = userRepository.findByEmailIgnoreCase(AuthService.normalizeEmail(request.email()));
            if (candidateUser.isPresent()) {
                UserEntity u = candidateUser.get();
                if (u.getRole() == UserRole.CUSTOMER && !u.isEmailVerified()) {
                    loginRateLimiter.recordFailure(clientIp, request.email());
                    String finalIp = clientIp;
                    auditService.recordEventRequiresNew(u, AuditService.ACTION_DISABLED_USER_ATTEMPT, "USER", u.getId(),
                            Map.of("email", u.getEmail(), "ip", finalIp, "reason", "unverified_email"));
                    throw new DisabledException("Email verification required. Please verify your email before signing in.");
                }
            }
        }

        Authentication authentication;
        try {
            authentication = authService.authenticate(request);
        } catch (DisabledException ex) {
            loginRateLimiter.recordFailure(clientIp, request.email());
            String finalIp = clientIp;
            userRepository.findByEmailIgnoreCase(request.email()).ifPresent(u -> {
                auditService.recordEventRequiresNew(u, AuditService.ACTION_DISABLED_USER_ATTEMPT, "USER", u.getId(),
                        Map.of("email", u.getEmail(), "ip", finalIp));
            });
            throw ex;
        } catch (BadCredentialsException ex) {
            loginRateLimiter.recordFailure(clientIp, request.email());
            auditService.recordEventRequiresNew(null, AuditService.ACTION_LOGIN_FAILURE, "USER", null,
                    Map.of("email", AuthService.normalizeEmail(request.email()), "ip", clientIp));
            throw ex;
        } catch (Exception ex) {
            auditService.recordEventRequiresNew(null, AuditService.ACTION_LOGIN_FAILURE, "USER", null,
                    Map.of("email", AuthService.normalizeEmail(request.email()), "ip", clientIp, "reason", "internal_error"));
            throw ex;
        }

        sessionAuthenticationStrategy.onAuthentication(authentication, httpRequest, httpResponse);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        loginRateLimiter.recordSuccess(clientIp, request.email());

        UserEntity user = userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new AuthenticationServiceException("Authenticated user no longer exists"));

        auditService.recordEvent(user, AuditService.ACTION_LOGIN_SUCCESS, "USER", user.getId(),
                Map.of("email", user.getEmail(), "ip", clientIp));

        return new AuthResponse(user.getId(), user.getEmail(), user.getRole().name());
    }

    @GetMapping("/me")
    public AuthResponse me(Authentication authentication) {
        UserEntity user = userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new BadCredentialsException("Authenticated user no longer exists"));
        return new AuthResponse(user.getId(), user.getEmail(), user.getRole().name());
    }

    @PostMapping("/password")
    public ResponseEntity<Map<String, String>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }
        UserEntity user = userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        authService.changePassword(user, request);

        String clientIp = httpRequest.getRemoteAddr();
        auditService.recordEvent(
                user,
                "USER_PASSWORD_CHANGED",
                "USER",
                user.getId(),
                Map.of("email", user.getEmail(), "role", user.getRole().name(), "ip", clientIp != null ? clientIp : "unknown")
        );

        return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest httpRequest) {
        String clientIp = httpRequest.getRemoteAddr();
        return ResponseEntity.ok(passwordRecoveryService.requestPasswordReset(request.email(), clientIp));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request,
            HttpServletRequest httpRequest) {
        String clientIp = httpRequest.getRemoteAddr();
        return ResponseEntity.ok(passwordRecoveryService.resetPassword(request, clientIp));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<Map<String, String>> verifyEmail(
            @Valid @RequestBody VerifyEmailRequest request,
            HttpServletRequest httpRequest) {
        String clientIp = httpRequest.getRemoteAddr();
        return ResponseEntity.ok(emailVerificationService.verifyEmail(request.token(), clientIp));
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<Map<String, String>> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request,
            HttpServletRequest httpRequest) {
        String clientIp = httpRequest.getRemoteAddr();
        return ResponseEntity.ok(emailVerificationService.resendVerification(request.email(), clientIp));
    }
}
