package com.vaultora.security;

import java.util.Arrays;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionFixationProtectionStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.vaultora.audit.AuditService;
import com.vaultora.repository.UserRepository;

@Configuration
public class SecurityConfig {

    private final SessionUserValidationFilter sessionUserValidationFilter;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public SecurityConfig(SessionUserValidationFilter sessionUserValidationFilter,
            UserRepository userRepository,
            AuditService auditService) {
        this.sessionUserValidationFilter = sessionUserValidationFilter;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationProvider authenticationProvider(DatabaseUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new SessionFixationProtectionStrategy();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            AuthenticationProvider authenticationProvider, CorsConfigurationSource corsConfigurationSource,
            AccessDeniedHandler accessDeniedHandler) throws Exception {
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        http
                .authenticationProvider(authenticationProvider)
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionAuthenticationStrategy(sessionAuthenticationStrategy))
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(csrfRequestHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/csrf",
                                "/api/v1/auth/forgot-password",
                                "/api/v1/auth/reset-password",
                                "/api/v1/auth/verify-email",
                                "/api/v1/auth/resend-verification",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/api/v1/docs",
                                "/actuator/health",
                                "/actuator/health/**")
                        .permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/audit/**").hasAnyRole("ADMIN", "AUDITOR")
                        .requestMatchers("/api/v1/audit/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/transactions/**").hasAnyRole("CUSTOMER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts/*/close").hasAnyRole("CUSTOMER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/accounts/**").hasAnyRole("CUSTOMER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/account-requests/*/approve", "/api/v1/account-requests/*/reject", "/api/v1/account-requests/*/accept").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/account-requests/**").hasAnyRole("CUSTOMER", "ADMIN", "AUDITOR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/account-requests/**").hasAnyRole("CUSTOMER", "ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> response.sendError(
                                HttpServletResponse.SC_UNAUTHORIZED, "Authentication required"))
                        .accessDeniedHandler(accessDeniedHandler))
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .addLogoutHandler((request, response, authentication) -> {
                            if (authentication != null) {
                                userRepository.findByEmailIgnoreCase(authentication.getName())
                                        .ifPresent(user -> auditService.recordEvent(user,
                                                AuditService.ACTION_LOGOUT, "USER", user.getId(),
                                                Map.of("email", user.getEmail(), "ip", request.getRemoteAddr())));
                            }
                        })
                        .logoutSuccessHandler((request, response, authentication) -> {
                            response.setStatus(HttpServletResponse.SC_OK);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"message\":\"Logged out successfully\"}");
                        })
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID", "XSRF-TOKEN"))
                .addFilterAfter(sessionUserValidationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    AccessDeniedHandler accessDeniedHandler() {
        return (request, response, exception) -> response.sendError(HttpServletResponse.SC_FORBIDDEN, "Access denied");
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:http://localhost:5173}") String allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList());
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Content-Type", "X-XSRF-TOKEN", "Idempotency-Key"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
