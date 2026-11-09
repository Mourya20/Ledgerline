package com.ledgerline.provision;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Idempotent demonstration provisioning tool for LedgerLine.
 *
 * Connects with schema-owner / migration credentials (ledgerline_app) to satisfy
 * V9 trigger constraints (trg_protect_user_role_assignment).
 *
 * Provisions:
 * - 16 demonstration identities (12 CUSTOMER, 3 AUDITOR, 1 ADMIN)
 * - 19 customer accounts with 0.00 INR balance (customer1-customer7 have 2 accounts, customer8-customer12 have 1 account)
 * - Generates private/demo-credentials.local.md
 */
public class DemoProvisioningTool {

    public record DemoUserSpec(
            String username,
            String email,
            String password,
            String role,
            int targetAccounts
    ) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final PasswordEncoder PASSWORD_ENFORCER = new BCryptPasswordEncoder();

    public static final List<DemoUserSpec> SPECIFICATIONS = List.of(
            // 12 Customers (19 accounts total: 7*2 + 5*1)
            new DemoUserSpec("customer1", "customer1@ledgerline.com", "customer1@ledgerline.com", "CUSTOMER", 2),
            new DemoUserSpec("customer2", "customer2@ledgerline.com", "customer2@ledgerline.com", "CUSTOMER", 2),
            new DemoUserSpec("customer3", "customer3@ledgerline.com", "customer3@ledgerline.com", "CUSTOMER", 2),
            new DemoUserSpec("customer4", "customer4@ledgerline.com", "customer4@ledgerline.com", "CUSTOMER", 2),
            new DemoUserSpec("customer5", "customer5@ledgerline.com", "customer5@ledgerline.com", "CUSTOMER", 2),
            new DemoUserSpec("customer6", "customer6@ledgerline.com", "customer6@ledgerline.com", "CUSTOMER", 2),
            new DemoUserSpec("customer7", "customer7@ledgerline.com", "customer7@ledgerline.com", "CUSTOMER", 2),
            new DemoUserSpec("customer8", "customer8@ledgerline.com", "customer8@ledgerline.com", "CUSTOMER", 1),
            new DemoUserSpec("customer9", "customer9@ledgerline.com", "customer9@ledgerline.com", "CUSTOMER", 1),
            new DemoUserSpec("customer10", "customer10@ledgerline.com", "customer10@ledgerline.com", "CUSTOMER", 1),
            new DemoUserSpec("customer11", "customer11@ledgerline.com", "customer11@ledgerline.com", "CUSTOMER", 1),
            new DemoUserSpec("customer12", "customer12@ledgerline.com", "customer12@ledgerline.com", "CUSTOMER", 1),

            // 3 Auditors (0 accounts)
            new DemoUserSpec("auditor1", "auditor1@ledgerline.com", "auditor1@ledgerline.com", "AUDITOR", 0),
            new DemoUserSpec("auditor2", "auditor2@ledgerline.com", "auditor2@ledgerline.com", "AUDITOR", 0),
            new DemoUserSpec("auditor3", "auditor3@ledgerline.com", "auditor3@ledgerline.com", "AUDITOR", 0),

            // 1 Admin (0 accounts)
            new DemoUserSpec("admin1", "admin1@ledgerline.com", "admin1@ledgerline.com", "ADMIN", 0)
    );

    public static void main(String[] args) {
        boolean updatePasswordsIfMismatch = Arrays.asList(args).contains("--update-passwords");
        System.out.println("=== LedgerLine Demonstration Provisioning Tool ===");
        System.out.println("Mode: Idempotent provisioning (updatePasswords=" + updatePasswordsIfMismatch + ")");

        String dbUrl = resolveConfig("FLYWAY_DB_URL", resolveConfig("DB_URL", "jdbc:postgresql://localhost:5432/ledgerline_db"));
        String dbUser = resolveConfig("FLYWAY_DB_USERNAME", resolveConfig("FLYWAY_DB_USER", resolveConfig("DB_BOOTSTRAP_USER", "ledgerline_app")));
        String dbPass = resolveConfig("FLYWAY_DB_PASSWORD", resolveConfig("DB_PASSWORD", "Mourya@123"));

        System.out.println("Database URL:  " + dbUrl);
        System.out.println("Database User: " + dbUser);

        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass)) {
            conn.setAutoCommit(false);
            try {
                Map<String, List<String>> userAccountsMap = new LinkedHashMap<>();
                int usersCreated = 0;
                int usersReused = 0;
                int accountsCreated = 0;
                int accountsReused = 0;

                for (DemoUserSpec spec : SPECIFICATIONS) {
                    UUID userId = null;

                    // 1. Check if user exists
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT id, email, role, status, password_hash FROM users WHERE LOWER(email) = LOWER(?)")) {
                        ps.setString(1, spec.email);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                userId = (UUID) rs.getObject("id");
                                String existingRole = rs.getString("role");
                                String existingStatus = rs.getString("status");
                                String existingHash = rs.getString("password_hash");

                                if (!spec.role.equalsIgnoreCase(existingRole)) {
                                    throw new IllegalStateException("Role conflict for demo user " + spec.email +
                                            ": expected " + spec.role + ", found " + existingRole);
                                }
                                if (!"ACTIVE".equalsIgnoreCase(existingStatus)) {
                                    throw new IllegalStateException("Status conflict for demo user " + spec.email +
                                            ": expected ACTIVE, found " + existingStatus);
                                }

                                boolean passwordMatches = PASSWORD_ENFORCER.matches(spec.password, existingHash);
                                if (!passwordMatches) {
                                    if (updatePasswordsIfMismatch) {
                                        String newHash = PASSWORD_ENFORCER.encode(spec.password);
                                        try (PreparedStatement updatePs = conn.prepareStatement(
                                                "UPDATE users SET password_hash = ?, updated_at = NOW() WHERE id = ?")) {
                                            updatePs.setString(1, newHash);
                                            updatePs.setObject(2, userId);
                                            updatePs.executeUpdate();
                                        }
                                        System.out.println("  [UPDATE] Updated password hash for: " + spec.email);
                                    } else {
                                        throw new IllegalStateException("Password hash mismatch for existing demo user " +
                                                spec.email + ". Run with --update-passwords to synchronize.");
                                    }
                                }
                                usersReused++;
                                System.out.println("  [REUSE] Demo user verified: " + spec.email + " (" + spec.role + ")");
                            }
                        }
                    }

                    // 2. Insert user if not exists
                    if (userId == null) {
                        userId = UUID.randomUUID();
                        String passwordHash = PASSWORD_ENFORCER.encode(spec.password);
                        try (PreparedStatement insertPs = conn.prepareStatement(
                                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                                        "VALUES (?, ?, ?, ?, 'ACTIVE', NOW(), NOW())")) {
                            insertPs.setObject(1, userId);
                            insertPs.setString(2, spec.email);
                            insertPs.setString(3, passwordHash);
                            insertPs.setString(4, spec.role);
                            insertPs.executeUpdate();
                        }
                        usersCreated++;
                        System.out.println("  [CREATE] Provisioned demo user: " + spec.email + " (" + spec.role + ")");
                    }

                    // 3. Process Accounts
                    List<String> assignedAccounts = new ArrayList<>();
                    if (spec.targetAccounts > 0) {
                        // Retrieve existing accounts
                        try (PreparedStatement accPs = conn.prepareStatement(
                                "SELECT id, account_number, balance, status FROM accounts WHERE owner_user_id = ? ORDER BY created_at ASC")) {
                            accPs.setObject(1, userId);
                            try (ResultSet accRs = accPs.executeQuery()) {
                                while (accRs.next()) {
                                    assignedAccounts.add(accRs.getString("account_number"));
                                    accountsReused++;
                                }
                            }
                        }

                        int currentCount = assignedAccounts.size();
                        if (currentCount > spec.targetAccounts) {
                            System.out.println("  [WARN] User " + spec.email + " has " + currentCount +
                                    " accounts (target: " + spec.targetAccounts + "). Preserving existing records.");
                        } else if (currentCount < spec.targetAccounts) {
                            int toCreate = spec.targetAccounts - currentCount;
                            for (int i = 0; i < toCreate; i++) {
                                String accountNumber = generateUniqueAccountNumber(conn);
                                UUID accountId = UUID.randomUUID();
                                try (PreparedStatement createAccPs = conn.prepareStatement(
                                        "INSERT INTO accounts (id, account_number, owner_user_id, account_type, " +
                                                "currency, balance, normal_balance, allow_negative, status, created_at, updated_at) " +
                                                "VALUES (?, ?, ?, 'CUSTOMER', 'INR', 0.00, 'CREDIT', false, 'ACTIVE', NOW(), NOW())")) {
                                    createAccPs.setObject(1, accountId);
                                    createAccPs.setString(2, accountNumber);
                                    createAccPs.setObject(3, userId);
                                    createAccPs.executeUpdate();
                                }
                                assignedAccounts.add(accountNumber);
                                accountsCreated++;
                                System.out.println("    [CREATE] Account: " + accountNumber + " for " + spec.email);
                            }
                        }
                    }

                    userAccountsMap.put(spec.username, assignedAccounts);
                }

                conn.commit();
                System.out.println("\nDatabase provisioning completed successfully!");
                System.out.println("Users: " + usersCreated + " created, " + usersReused + " reused (Total: 16)");
                System.out.println("Accounts: " + accountsCreated + " created, " + accountsReused + " reused (Total: 19)");

                // 4. Generate private/demo-credentials.local.md
                generateCredentialRecord(userAccountsMap);

            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            }
        } catch (Exception e) {
            System.err.println("PROVISIONING FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static String generateUniqueAccountNumber(Connection conn) throws SQLException {
        for (int i = 0; i < 20; i++) {
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
        throw new IllegalStateException("Failed to generate unique account number after 20 attempts");
    }

    private static void generateCredentialRecord(Map<String, List<String>> userAccountsMap) throws IOException {
        Path projectRoot = findProjectRoot();
        Path privateDir = projectRoot.resolve("private");
        if (!Files.exists(privateDir)) {
            Files.createDirectories(privateDir);
        }

        Path targetFile = privateDir.resolve("demo-credentials.local.md");
        StringBuilder sb = new StringBuilder();
        sb.append("# LedgerLine — Assessment Demonstration Credentials\n\n");
        sb.append("> **CONFIDENTIAL — EVALUATOR ACCESS ONLY**\n");
        sb.append("> This document contains demonstration authentication credentials and allocated accounts.\n");
        sb.append("> It is ignored by Git (`/private/` in `.gitignore`) and is never committed to version control.\n\n");

        sb.append("## Access URLs\n\n");
        sb.append("- **Local Frontend:** [http://localhost:5173/login](http://localhost:5173/login)\n");
        sb.append("- **Local Backend API:** [http://localhost:8080/](http://localhost:8080/)\n");
        sb.append("- **Deployed Frontend (Vercel):** `https://<deployed-vercel-domain>/login` *(set once deployed)*\n");
        sb.append("- **Deployed Backend (Render):** `https://<deployed-render-domain>/` *(set once deployed)*\n\n");

        sb.append("## Demonstration Identities (16 Total)\n\n");
        sb.append("| Identity Label | Role | Authentication Email | Password | Accounts Allocated | Account Numbers |\n");
        sb.append("| :--- | :--- | :--- | :--- | :---: | :--- |\n");

        for (DemoUserSpec spec : SPECIFICATIONS) {
            List<String> accounts = userAccountsMap.getOrDefault(spec.username, List.of());
            String accountsStr = accounts.isEmpty() ? "None (Privileged Role)" : String.join(", ", accounts);
            sb.append(String.format("| `%s` | **%s** | `%s` | `%s` | %d | %s |\n",
                    spec.username,
                    spec.role,
                    spec.email,
                    spec.password,
                    accounts.size(),
                    accountsStr
            ));
        }

        sb.append("\n## Account Allocation Summary\n\n");
        sb.append("- **Customers 1–7:** 2 accounts each (14 accounts total)\n");
        sb.append("- **Customers 8–12:** 1 account each (5 accounts total)\n");
        sb.append("- **Auditors 1–3:** 0 accounts (Strictly read-only audit log inspection)\n");
        sb.append("- **Admin 1:** 0 accounts (System administration and audit inspection)\n");
        sb.append("- **All Accounts:** Currency `INR`, Balance `₹0.00`, Status `ACTIVE`, Normal Balance `CREDIT`\n");
        sb.append("- **Password Policy:** Plaintext matches complete email address, stored in database as BCrypt cost-10 hashes\n");

        Files.writeString(targetFile, sb.toString());
        System.out.println("\n[SUCCESS] Local credential record written to: " + targetFile.toAbsolutePath());
    }

    private static Path findProjectRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve(".git")) || Files.exists(current.resolve("backend"))) {
                return current;
            }
            current = current.getParent();
        }
        return Paths.get("").toAbsolutePath();
    }

    private static String resolveConfig(String key, String fallback) {
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) return env;

        String prop = System.getProperty(key);
        if (prop != null && !prop.isBlank()) return prop;

        String dotEnvVal = readFromDotEnv(key);
        if (dotEnvVal != null && !dotEnvVal.isBlank()) return dotEnvVal;

        return fallback;
    }

    private static String readFromDotEnv(String key) {
        Path root = findProjectRoot();
        Path dotEnv = root.resolve(".env");
        if (!Files.exists(dotEnv)) return null;

        try (BufferedReader reader = new BufferedReader(new FileReader(dotEnv.toFile()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#") || !line.contains("=")) continue;
                int eq = line.indexOf('=');
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if (k.equals(key)) {
                    if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
                        return v.substring(1, v.length() - 1);
                    }
                    return v;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
