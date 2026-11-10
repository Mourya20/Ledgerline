package com.vaultora.account;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vaultora.account.dto.AccountBalanceResponse;
import com.vaultora.account.dto.AccountResponse;
import com.vaultora.account.dto.CreateAccountRequest;
import com.vaultora.audit.AuditService;
import com.vaultora.domain.UserEntity;
import com.vaultora.domain.UserRole;
import com.vaultora.repository.UserRepository;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;
    private final StatementService statementService;

    public AccountController(
            AccountService accountService,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuditService auditService,
            JdbcTemplate jdbcTemplate,
            StatementService statementService) {
        this.accountService = accountService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
        this.statementService = statementService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(
            Authentication authentication,
            @Valid @RequestBody(required = false) CreateAccountRequest request) {
        UserEntity actor = resolveUser(authentication);
        CreateAccountRequest req = (request != null) ? request : new CreateAccountRequest("INR");

        if (actor.getRole() != UserRole.ADMIN) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Direct customer account creation is not permitted. Please submit an account opening request.");
        }

        UserEntity targetUser;
        if (actor.getRole() == UserRole.ADMIN) {
            String email = (req.customerEmail() != null && !req.customerEmail().isBlank())
                    ? req.customerEmail().trim().toLowerCase(java.util.Locale.ROOT)
                    : null;
            UUID reqCustomerId = req.customerId();

            if (email == null && reqCustomerId == null) {
                throw new IllegalArgumentException("Administrator must specify customerEmail or customerId to assign the new account");
            }

            Optional<UserEntity> userById = (reqCustomerId != null)
                    ? userRepository.findById(reqCustomerId)
                    : Optional.empty();
            Optional<UserEntity> userByEmail = (email != null)
                    ? userRepository.findByEmailIgnoreCase(email)
                    : Optional.empty();

            if (userById.isPresent() && userByEmail.isPresent() && !userById.get().getId().equals(userByEmail.get().getId())) {
                throw new IllegalArgumentException("Provided customerId and customerEmail refer to different existing users");
            }

            if (userByEmail.isPresent()) {
                targetUser = userByEmail.get();
                if (reqCustomerId != null || req.role() == UserRole.AUDITOR) {
                    if (targetUser.getRole() != UserRole.CUSTOMER && targetUser.getRole() != UserRole.AUDITOR) {
                        throw new IllegalArgumentException("Accounts can only be assigned to users with CUSTOMER or AUDITOR role");
                    }
                } else {
                    if (targetUser.getRole() != UserRole.CUSTOMER) {
                        throw new IllegalArgumentException("Accounts can only be assigned to users with CUSTOMER role");
                    }
                }
            } else if (userById.isPresent()) {
                targetUser = userById.get();
                if (targetUser.getRole() != UserRole.CUSTOMER && targetUser.getRole() != UserRole.AUDITOR) {
                    throw new IllegalArgumentException("Accounts can only be assigned to users with CUSTOMER or AUDITOR role");
                }
            } else {
                // Non-existing user: Admin provisions new user with the assigned ID and selected role
                if (reqCustomerId == null) {
                    throw new IllegalArgumentException("User not found with email: " + email + ". Specify customerId to onboard a new user.");
                }
                if (email == null) {
                    throw new IllegalArgumentException("customerEmail is required when creating a new account");
                }

                UserRole assignedRole = (req.role() != null) ? req.role() : UserRole.CUSTOMER;
                if (assignedRole != UserRole.CUSTOMER && assignedRole != UserRole.AUDITOR) {
                    throw new IllegalArgumentException("Newly onboarded users can only be assigned CUSTOMER or AUDITOR role");
                }

                String password = (req.customerPassword() != null && !req.customerPassword().isBlank())
                        ? req.customerPassword().trim()
                        : "Password123!";
                if (password.length() < 12) {
                    throw new IllegalArgumentException("Password must be at least 12 characters");
                }
                if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
                    throw new IllegalArgumentException("Password must not exceed 72 UTF-8 bytes");
                }
                String encodedPassword = passwordEncoder.encode(password);

                jdbcTemplate.update("CALL admin_onboard_user(?, ?, ?, ?)", reqCustomerId, email, encodedPassword, assignedRole.name());
                targetUser = userRepository.findById(reqCustomerId)
                        .orElseThrow(() -> new IllegalStateException("Failed to find newly onboarded user: " + reqCustomerId));

                auditService.recordEvent(
                        actor,
                        assignedRole == UserRole.AUDITOR ? "AUDITOR_ONBOARDED_BY_ADMIN" : "CUSTOMER_ONBOARDED_BY_ADMIN",
                        "USER",
                        targetUser.getId(),
                        Map.of(
                                "email", targetUser.getEmail(),
                                "assignedId", targetUser.getId().toString(),
                                "role", targetUser.getRole().name()
                        )
                );
            }
        } else {
            targetUser = actor;
        }

        AccountResponse response = accountService.createAccount(actor, targetUser, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<AccountResponse>> getAccounts(
            Authentication authentication,
            @RequestParam(required = false) String customerEmail,
            @RequestParam(required = false) UUID customerId) {
        UserEntity actor = resolveUser(authentication);
        if (actor.getRole() == UserRole.ADMIN) {
            if (customerId != null) {
                UserEntity target = userRepository.findById(customerId)
                        .orElseThrow(() -> new IllegalArgumentException("User not found with ID: " + customerId));
                return ResponseEntity.ok(accountService.getUserAccounts(target.getId()));
            }
            if (customerEmail != null && !customerEmail.isBlank()) {
                UserEntity target = userRepository.findByEmailIgnoreCase(customerEmail.trim())
                        .orElseThrow(() -> new IllegalArgumentException("Customer not found with email: " + customerEmail));
                return ResponseEntity.ok(accountService.getUserAccounts(target.getId()));
            }
            return ResponseEntity.ok(accountService.getAllCustomerAccounts());
        }
        return ResponseEntity.ok(accountService.getUserAccounts(actor.getId()));
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<AccountResponse> getAccount(
            Authentication authentication,
            @PathVariable UUID accountId) {
        UserEntity actor = resolveUser(authentication);
        if (actor.getRole() == UserRole.ADMIN) {
            return ResponseEntity.ok(accountService.getAccountByIdForAdmin(accountId));
        }
        return ResponseEntity.ok(accountService.getAccountById(accountId, actor.getId()));
    }

    @GetMapping("/{accountId}/balance")
    public ResponseEntity<AccountBalanceResponse> getAccountBalance(
            Authentication authentication,
            @PathVariable UUID accountId) {
        UserEntity actor = resolveUser(authentication);
        if (actor.getRole() == UserRole.ADMIN) {
            return ResponseEntity.ok(accountService.getAccountBalanceForAdmin(accountId));
        }
        return ResponseEntity.ok(accountService.getAccountBalance(accountId, actor.getId()));
    }

    @DeleteMapping("/{accountId}")
    public ResponseEntity<AccountResponse> closeAccount(
            Authentication authentication,
            @PathVariable UUID accountId) {
        UserEntity actor = resolveUser(authentication);
        AccountResponse response = accountService.closeAccount(actor, accountId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{accountId}/statement")
    public ResponseEntity<byte[]> getAccountStatement(
            Authentication authentication,
            @PathVariable UUID accountId,
            @RequestParam(defaultValue = "pdf") String format,
            @RequestParam(required = false) java.time.Instant startDate,
            @RequestParam(required = false) java.time.Instant endDate) {
        UserEntity actor = resolveUser(authentication);
        byte[] bytes = statementService.generateStatement(accountId, actor, format, startDate, endDate);

        boolean isCsv = "csv".equalsIgnoreCase(format);
        String contentType = isCsv ? "text/csv; charset=UTF-8" : "application/pdf";
        String ext = isCsv ? "csv" : "pdf";
        String filename = String.format("statement-%s.%s", accountId, ext);

        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(org.springframework.http.MediaType.parseMediaType(contentType))
                .body(bytes);
    }

    private UserEntity resolveUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BadCredentialsException("Authentication required");
        }
        return userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new BadCredentialsException("Authenticated user not found"));
    }
}
