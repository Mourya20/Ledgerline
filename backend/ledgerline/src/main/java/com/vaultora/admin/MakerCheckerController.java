package com.vaultora.admin;

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

import com.vaultora.admin.dto.AdminTransferRequest;
import com.vaultora.admin.dto.InitiateReversalRequest;
import com.vaultora.admin.dto.MakerCheckerResponse;
import com.vaultora.admin.dto.ReviewMakerCheckerRequest;
import com.vaultora.domain.MakerCheckerStatus;
import com.vaultora.domain.UserEntity;
import com.vaultora.domain.UserRole;
import com.vaultora.repository.UserRepository;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin")
public class MakerCheckerController {

    private final MakerCheckerService makerCheckerService;
    private final UserRepository userRepository;

    public MakerCheckerController(MakerCheckerService makerCheckerService, UserRepository userRepository) {
        this.makerCheckerService = makerCheckerService;
        this.userRepository = userRepository;
    }

    @GetMapping("/maker-checker/requests")
    public ResponseEntity<List<MakerCheckerResponse>> getRequests(
            Authentication authentication,
            @RequestParam(required = false) MakerCheckerStatus status) {
        verifyAdmin(authentication);
        return ResponseEntity.ok(makerCheckerService.getRequests(status));
    }

    @GetMapping("/maker-checker/requests/{id}")
    public ResponseEntity<MakerCheckerResponse> getRequestById(
            Authentication authentication,
            @PathVariable UUID id) {
        verifyAdmin(authentication);
        return ResponseEntity.ok(makerCheckerService.getRequestById(id));
    }

    @PostMapping("/maker-checker/requests/transfer")
    public ResponseEntity<MakerCheckerResponse> initiateTransfer(
            Authentication authentication,
            @Valid @RequestBody AdminTransferRequest request) {
        UserEntity admin = verifyAdmin(authentication);
        MakerCheckerResponse response = makerCheckerService.initiateTransfer(admin, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping({"/maker-checker/requests/reversal/{transactionId}", "/transactions/{transactionId}/reverse"})
    public ResponseEntity<MakerCheckerResponse> initiateReversal(
            Authentication authentication,
            @PathVariable UUID transactionId,
            @Valid @RequestBody InitiateReversalRequest request) {
        UserEntity admin = verifyAdmin(authentication);
        MakerCheckerResponse response = makerCheckerService.initiateReversal(admin, transactionId, request.reason());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/maker-checker/requests/{id}/approve")
    public ResponseEntity<MakerCheckerResponse> approveRequest(
            Authentication authentication,
            @PathVariable UUID id) {
        UserEntity checker = verifyAdmin(authentication);
        return ResponseEntity.ok(makerCheckerService.approveRequest(id, checker));
    }

    @PostMapping("/maker-checker/requests/{id}/reject")
    public ResponseEntity<MakerCheckerResponse> rejectRequest(
            Authentication authentication,
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) ReviewMakerCheckerRequest request) {
        UserEntity checker = verifyAdmin(authentication);
        String reason = (request != null) ? request.rejectionReason() : null;
        return ResponseEntity.ok(makerCheckerService.rejectRequest(id, checker, reason));
    }

    private UserEntity verifyAdmin(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BadCredentialsException("Authentication required");
        }
        UserEntity actor = userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new BadCredentialsException("User not found"));
        if (actor.getRole() != UserRole.ADMIN) {
            throw new AccessDeniedException("Admin role required");
        }
        return actor;
    }
}
