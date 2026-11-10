package com.vaultora.admin;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import com.vaultora.admin.dto.AssignPasswordRequest;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vaultora.account.dto.AccountResponse;
import com.vaultora.admin.dto.AdminStatsResponse;
import com.vaultora.admin.dto.UpdateAccountStatusRequest;
import com.vaultora.admin.dto.UpdateUserStatusRequest;
import com.vaultora.admin.dto.UserSummaryResponse;
import com.vaultora.domain.AccountEntity;
import com.vaultora.domain.UserEntity;
import com.vaultora.domain.UserRole;
import com.vaultora.repository.UserRepository;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminService adminService;
    private final UserRepository userRepository;

    public AdminController(AdminService adminService, UserRepository userRepository) {
        this.adminService = adminService;
        this.userRepository = userRepository;
    }

    @GetMapping("/stats")
    public ResponseEntity<AdminStatsResponse> getStats(Authentication authentication) {
        verifyAdmin(authentication);
        return ResponseEntity.ok(adminService.getSystemStats());
    }

    @GetMapping("/users")
    public ResponseEntity<List<UserSummaryResponse>> getUsers(Authentication authentication) {
        verifyAdmin(authentication);
        return ResponseEntity.ok(adminService.getAllUsers());
    }

    @GetMapping("/users/{userId}/accounts")
    public ResponseEntity<List<AccountResponse>> getUserAccounts(
            Authentication authentication,
            @PathVariable UUID userId) {
        verifyAdmin(authentication);
        return ResponseEntity.ok(adminService.getUserAccounts(userId));
    }

    @PatchMapping("/users/{userId}/status")
    public ResponseEntity<UserSummaryResponse> updateUserStatus(
            Authentication authentication,
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserStatusRequest request) {
        UserEntity actor = verifyAdmin(authentication);
        return ResponseEntity.ok(adminService.updateUserStatus(userId, request.status(), actor));
    }

    @PatchMapping("/accounts/{accountId}/status")
    public ResponseEntity<AccountResponse> updateAccountStatus(
            Authentication authentication,
            @PathVariable UUID accountId,
            @Valid @RequestBody UpdateAccountStatusRequest request) {
        UserEntity actor = verifyAdmin(authentication);
        AccountEntity updated = adminService.updateAccountStatus(accountId, request.status(), actor);
        return ResponseEntity.ok(AccountResponse.fromEntity(updated));
    }

    
    @PostMapping("/users/{userId}/password")
    public ResponseEntity<Map<String, String>> assignUserPassword(
            Authentication authentication,
            @PathVariable UUID userId,
            @Valid @RequestBody AssignPasswordRequest request) {
        UserEntity actor = verifyAdmin(authentication);
        adminService.assignUserPassword(userId, request.password(), actor);
        return ResponseEntity.ok(Map.of("message", "Password assigned successfully"));
    }

    private UserEntity verifyAdmin(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BadCredentialsException("Authentication required");
        }
        UserEntity actor = userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new BadCredentialsException("User not found"));
        if (actor.getRole() != UserRole.ADMIN) {
            throw new AccessDeniedException("Admin privileges required");
        }
        return actor;
    }
}
