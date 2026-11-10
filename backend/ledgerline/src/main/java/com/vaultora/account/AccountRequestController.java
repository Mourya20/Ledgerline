package com.vaultora.account;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vaultora.account.dto.AccountRequestResponse;
import com.vaultora.account.dto.CreateAccountOpeningRequest;
import com.vaultora.account.dto.ReviewAccountRequest;
import com.vaultora.domain.AccountRequestStatus;
import com.vaultora.domain.UserEntity;
import com.vaultora.domain.UserRole;
import com.vaultora.repository.UserRepository;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/account-requests")
public class AccountRequestController {

    private final AccountRequestService accountRequestService;
    private final UserRepository userRepository;

    public AccountRequestController(AccountRequestService accountRequestService, UserRepository userRepository) {
        this.accountRequestService = accountRequestService;
        this.userRepository = userRepository;
    }

    @PostMapping
    public ResponseEntity<AccountRequestResponse> createRequest(
            Authentication authentication,
            @Valid @RequestBody(required = false) CreateAccountOpeningRequest request) {
        UserEntity actor = resolveUser(authentication);
        String currency = (request != null) ? request.currency() : "INR";
        AccountRequestResponse response = accountRequestService.createRequest(actor, currency);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<AccountRequestResponse>> getRequests(
            Authentication authentication,
            @RequestParam(required = false) AccountRequestStatus status) {
        UserEntity actor = resolveUser(authentication);
        if (actor.getRole() == UserRole.ADMIN || actor.getRole() == UserRole.AUDITOR) {
            return ResponseEntity.ok(accountRequestService.getAllRequests(status));
        }
        return ResponseEntity.ok(accountRequestService.getUserRequests(actor.getId()));
    }

    @PostMapping("/{requestId}/approve")
    public ResponseEntity<AccountRequestResponse> approveRequest(
            Authentication authentication,
            @PathVariable UUID requestId,
            @Valid @RequestBody(required = false) ReviewAccountRequest request) {
        UserEntity actor = resolveUser(authentication);
        verifyAdmin(actor);
        String notes = (request != null) ? request.adminNotes() : null;
        AccountRequestResponse response = accountRequestService.reviewRequest(requestId, true, notes, actor);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{requestId}/accept")
    public ResponseEntity<AccountRequestResponse> acceptRequest(
            Authentication authentication,
            @PathVariable UUID requestId,
            @Valid @RequestBody(required = false) ReviewAccountRequest request) {
        return approveRequest(authentication, requestId, request);
    }

    @PostMapping("/{requestId}/reject")
    public ResponseEntity<AccountRequestResponse> rejectRequest(
            Authentication authentication,
            @PathVariable UUID requestId,
            @Valid @RequestBody(required = false) ReviewAccountRequest request) {
        UserEntity actor = resolveUser(authentication);
        verifyAdmin(actor);
        String notes = (request != null) ? request.adminNotes() : null;
        AccountRequestResponse response = accountRequestService.reviewRequest(requestId, false, notes, actor);
        return ResponseEntity.ok(response);
    }

    private UserEntity resolveUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BadCredentialsException("Authentication required");
        }
        return userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new BadCredentialsException("Authenticated user not found"));
    }

    private void verifyAdmin(UserEntity actor) {
        if (actor.getRole() != UserRole.ADMIN) {
            throw new AccessDeniedException("Administrative privileges required to review account requests");
        }
    }
}
