package com.ledgerline.ledgerline.auth;

import java.util.UUID;

public record AuthResponse(UUID userId, String email, String role) {
}
