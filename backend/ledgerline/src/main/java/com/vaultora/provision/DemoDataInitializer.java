package com.vaultora.provision;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Automatically provisions demonstration identities (12 customers, 3 auditors, 1 admin)
 * and their initial accounts on application startup when connected to an unpopulated database.
 *
 * Runs only when not in the test profile (@Profile("!test")).
 */
@Component
@Profile("!test")
public class DemoDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DataSource dataSource;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public DemoDataInitializer(DataSource dataSource, PasswordEncoder passwordEncoder) {
        this.dataSource = dataSource;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Checking demonstration user provisioning in database...");
        try (Connection conn = dataSource.getConnection()) {
            boolean adminExists = false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM users WHERE LOWER(email) = 'admin1@ledgerline.com'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        adminExists = true;
                    }
                }
            }

            if (adminExists) {
                log.info("Demo identities already present. Skipping automatic initialization.");
                return;
            }

            log.info("Provisioning 16 demonstration identities and accounts...");
            conn.setAutoCommit(false);
            try {
                int usersCreated = 0;
                int accountsCreated = 0;

                for (DemoProvisioningTool.DemoUserSpec spec : DemoProvisioningTool.SPECIFICATIONS) {
                    UUID userId = UUID.randomUUID();
                    String passwordHash = passwordEncoder.encode(spec.password());

                    try (PreparedStatement insertPs = conn.prepareStatement(
                            "INSERT INTO users (id, email, password_hash, role, status, email_verified, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, 'ACTIVE', true, NOW(), NOW()) " +
                            "ON CONFLICT (email) DO NOTHING")) {
                        insertPs.setObject(1, userId);
                        insertPs.setString(2, spec.email());
                        insertPs.setString(3, passwordHash);
                        insertPs.setString(4, spec.role());
                        int rows = insertPs.executeUpdate();
                        if (rows > 0) {
                            usersCreated++;
                        }
                    }

                    // Retrieve actual user ID in case user was pre-existing
                    UUID effectiveUserId = userId;
                    try (PreparedStatement idPs = conn.prepareStatement("SELECT id FROM users WHERE LOWER(email) = LOWER(?)")) {
                        idPs.setString(1, spec.email());
                        try (ResultSet rs = idPs.executeQuery()) {
                            if (rs.next()) {
                                effectiveUserId = (UUID) rs.getObject("id");
                            }
                        }
                    }

                    if (spec.targetAccounts() > 0) {
                        for (int i = 0; i < spec.targetAccounts(); i++) {
                            String accountNumber = generateUniqueAccountNumber(conn);
                            UUID accountId = UUID.randomUUID();
                            try (PreparedStatement createAccPs = conn.prepareStatement(
                                    "INSERT INTO accounts (id, account_number, owner_user_id, account_type, " +
                                    "currency, balance, normal_balance, allow_negative, status, created_at, updated_at) " +
                                    "VALUES (?, ?, ?, 'CUSTOMER', 'INR', 0.00, 'CREDIT', false, 'ACTIVE', NOW(), NOW())")) {
                                createAccPs.setObject(1, accountId);
                                createAccPs.setString(2, accountNumber);
                                createAccPs.setObject(3, effectiveUserId);
                                createAccPs.executeUpdate();
                                accountsCreated++;
                            }
                        }
                    }
                }

                conn.commit();
                log.info("Demo data initialized successfully: {} users created, {} accounts created.", usersCreated, accountsCreated);
            } catch (Exception ex) {
                conn.rollback();
                log.error("Failed to initialize demo data: {}", ex.getMessage(), ex);
            }
        } catch (SQLException e) {
            log.error("Database connection error during demo data initialization: {}", e.getMessage(), e);
        }
    }

    private String generateUniqueAccountNumber(Connection conn) throws SQLException {
        for (int i = 0; i < 30; i++) {
            long number = RANDOM.nextLong(100_000_000_000L, 1_000_000_000_000L);
            String candidate = "VLTR" + number;
            try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM accounts WHERE account_number = ?")) {
                ps.setString(1, candidate);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return candidate;
                    }
                }
            }
        }
        throw new IllegalStateException("Failed to generate unique account number after 30 attempts");
    }
}
