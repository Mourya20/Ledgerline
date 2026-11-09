package com.ledgerline.admin;

import java.util.List;
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

import com.ledgerline.account.dto.AccountResponse;
import com.ledgerline.admin.dto.AdminStatsResponse;
import com.ledgerline.admin.dto.UpdateAccountStatusRequest;
import com.ledgerline.admin.dto.UpdateUserStatusRequest;
import com.ledgerline.admin.dto.UserSummaryResponse;
import com.ledgerline.domain.AccountEntity;
import com.ledgerline.domain.UserEntity;
import com.ledgerline.domain.UserRole;
import com.ledgerline.repository.UserRepository;

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
