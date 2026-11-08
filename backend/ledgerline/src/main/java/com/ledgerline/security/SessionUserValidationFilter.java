package com.ledgerline.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ledgerline.domain.UserEntity;
import com.ledgerline.domain.UserStatus;
import com.ledgerline.repository.UserRepository;

@Component
public class SessionUserValidationFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;

    public SessionUserValidationFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UserDetails details) {
            UserEntity user = userRepository.findByEmailIgnoreCase(details.getUsername()).orElse(null);
            boolean invalid = user == null || user.getStatus() != UserStatus.ACTIVE
                    || authentication.getAuthorities().stream()
                            .noneMatch(authority -> authority.getAuthority().equals("ROLE_" + user.getRole().name()));
            if (invalid) {
                SecurityContextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Session is no longer valid");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
