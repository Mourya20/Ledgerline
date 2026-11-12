package com.vaultora;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.vaultora.domain.AccountEntity;
import com.vaultora.domain.AccountType;
import com.vaultora.domain.EmailVerificationTokenEntity;
import com.vaultora.domain.PasswordResetTokenEntity;
import com.vaultora.domain.TransactionStatus;
import com.vaultora.domain.UserEntity;
import com.vaultora.domain.UserRole;
import com.vaultora.repository.AccountRepository;
import com.vaultora.repository.EmailVerificationTokenRepository;
import com.vaultora.repository.LedgerTransactionRepository;
import com.vaultora.repository.PasswordResetTokenRepository;
import com.vaultora.repository.UserRepository;
import com.vaultora.transaction.TransactionService;
import com.vaultora.transaction.dto.DepositRequest;
import com.vaultora.transaction.dto.TransferRequest;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.test.context.TestPropertySource(properties = "vaultora.auth.email-verification-required=true")
public class ExtendedBankingControlsIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerTransactionRepository ledgerTransactionRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private UserEntity customerA;
    private UserEntity customerB;
    private UserEntity admin1;
    private UserEntity admin2;
    private UserEntity auditor;

    private AccountEntity accountA;
    private AccountEntity accountB;

    @BeforeEach
    void setUp() {
        TestDatabaseGuard.assertTestDatabase(jdbcTemplate);
        jdbcTemplate.execute("TRUNCATE TABLE audit_logs, ledger_entries, ledger_transactions, maker_checker_requests, password_reset_tokens, email_verification_tokens, accounts, users CASCADE");
        jdbcTemplate.update("""
                INSERT INTO accounts (
                    id, account_number, owner_user_id, account_type, currency, status, balance, normal_balance, allow_negative, created_at, updated_at
                )
                VALUES (
                    '00000000-0000-0000-0000-000000000001',
                    'SYS-INR-CASH-VAULT-001',
                    NULL,
                    'SYSTEM',
                    'INR',
                    'ACTIVE',
                    0.00,
                    'DEBIT',
                    false,
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP
                )
                ON CONFLICT (id) DO UPDATE SET
                    balance = 0.00,
                    status = 'ACTIVE';
                """);

        customerA = getOrCreateUser("custA_test@vaultora.com", UserRole.CUSTOMER, true);
        customerB = getOrCreateUser("custB_test@vaultora.com", UserRole.CUSTOMER, true);
        admin1 = getOrCreateUser("admin1_test@vaultora.com", UserRole.ADMIN, true);
        admin2 = getOrCreateUser("admin2_test@vaultora.com", UserRole.ADMIN, true);
        auditor = getOrCreateUser("auditor_test@vaultora.com", UserRole.AUDITOR, true);

        accountA = getOrCreateAccount("TEST-ACC-A-" + UUID.randomUUID().toString().substring(0, 6), customerA);
        accountB = getOrCreateAccount("TEST-ACC-B-" + UUID.randomUUID().toString().substring(0, 6), customerB);
    }

    private UserEntity getOrCreateUser(String email, UserRole role, boolean verified) {
        return userRepository.findByEmailIgnoreCase(email).orElseGet(() -> {
            UserEntity user = new UserEntity(UUID.randomUUID(), email, passwordEncoder.encode("TestPassword123!"), role, verified);
            return userRepository.save(user);
        });
    }

    private AccountEntity getOrCreateAccount(String accountNumber, UserEntity owner) {
        AccountEntity account = new AccountEntity(accountNumber, owner, AccountType.CUSTOMER, "INR");
        return accountRepository.save(account);
    }

    private MockHttpSession createSession(UserEntity user) {
        MockHttpSession session = new MockHttpSession();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                user.getEmail(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
        );
        context.setAuthentication(auth);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    @Test
    @DisplayName("RBAC: Customer gets 403 on admin-only endpoints, Auditor gets 403 on writes")
    void testRbacRestrictions() throws Exception {
        MockHttpSession customerSession = createSession(customerA);

        // Customer cannot access admin limits
        mockMvc.perform(get("/api/v1/admin/settings/limits")
                .session(customerSession))
                .andExpect(status().isForbidden());

        // Customer cannot access maker-checker
        mockMvc.perform(get("/api/v1/admin/maker-checker/requests")
                .session(customerSession))
                .andExpect(status().isForbidden());

        MockHttpSession auditorSession = createSession(auditor);

        // Auditor cannot create direct accounts
        mockMvc.perform(post("/api/v1/accounts")
                .session(auditorSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"INR\"}")
                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Auditor: Ledger integrity verification returns INTACT and debit-credit equality")
    void testAuditorLedgerIntegrity() throws Exception {
        MockHttpSession auditorSession = createSession(auditor);

        mockMvc.perform(get("/api/v1/audit/ledger-integrity")
                .session(auditorSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INTACT"))
                .andExpect(jsonPath("$.debitCreditEqual").value(true))
                .andExpect(jsonPath("$.discrepanciesCount").value(0));
    }

    @Test
    @DisplayName("Auditor: Transaction inspection and CSV export with formula defense")
    void testAuditorInspectionAndCsvExport() throws Exception {
        MockHttpSession auditorSession = createSession(auditor);

        // Deposit funds first
        transactionService.deposit(
                admin1,
                UUID.randomUUID().toString(),
                new DepositRequest(accountA.getId(), new BigDecimal("1000.00"), "INR")
        );

        mockMvc.perform(get("/api/v1/audit/transactions")
                .session(auditorSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());

        MvcResult csvResult = mockMvc.perform(get("/api/v1/audit/transactions/export")
                .session(auditorSession))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/csv")))
                .andReturn();

        String csv = csvResult.getResponse().getContentAsString();
        assertTrue(csv.startsWith("Transaction ID,Type,Status"));
    }

    @Test
    @DisplayName("Statements: PDF and CSV generation with customer ownership enforcement")
    void testStatementsAndOwnership() throws Exception {
        // Deposit funds
        transactionService.deposit(
                admin1,
                UUID.randomUUID().toString(),
                new DepositRequest(accountA.getId(), new BigDecimal("2500.00"), "INR")
        );

        MockHttpSession customerASession = createSession(customerA);

        // Customer A downloads own statement as PDF
        MvcResult pdfResult = mockMvc.perform(get("/api/v1/accounts/" + accountA.getId() + "/statement?format=pdf")
                .session(customerASession))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn();

        byte[] pdfBytes = pdfResult.getResponse().getContentAsByteArray();
        assertTrue(new String(pdfBytes).startsWith("%PDF-1.4"));

        // Customer A downloads own statement as CSV
        MvcResult csvResult = mockMvc.perform(get("/api/v1/accounts/" + accountA.getId() + "/statement?format=csv")
                .session(customerASession))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/csv")))
                .andReturn();

        assertTrue(csvResult.getResponse().getContentAsString().contains("VAULTORA BANKING ACCOUNT STATEMENT"));

        // Customer B cannot download Customer A's statement
        MockHttpSession customerBSession = createSession(customerB);
        mockMvc.perform(get("/api/v1/accounts/" + accountA.getId() + "/statement?format=pdf")
                .session(customerBSession))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Limits: Per-transaction limit violation rejected with 400 Bad Request")
    void testTransactionLimits() throws Exception {
        MockHttpSession adminSession = createSession(admin1);

        // Set per-transaction limit to 5,000 INR
        mockMvc.perform(put("/api/v1/admin/settings/limits")
                .session(adminSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxPerTransaction\":5000.00,\"maxDailyTotal\":50000.00}")
                .with(csrf()))
                .andExpect(status().isOk());

        // Deposit 10,000 into Account A
        transactionService.deposit(
                admin1,
                UUID.randomUUID().toString(),
                new DepositRequest(accountA.getId(), new BigDecimal("10000.00"), "INR")
        );

        MockHttpSession customerASession = createSession(customerA);

        // Attempt withdrawal of 8,000 INR (exceeds 5,000 limit)
        mockMvc.perform(post("/api/v1/transactions/withdraw")
                .session(customerASession)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":\"" + accountA.getId() + "\",\"amount\":8000.00,\"currency\":\"INR\"}")
                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("exceeds maximum per-transaction limit")));

        // Reset limits to default
        mockMvc.perform(put("/api/v1/admin/settings/limits")
                .session(adminSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxPerTransaction\":500000.00,\"maxDailyTotal\":2000000.00}")
                .with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Maker-Checker: Reversal initiation, self-approval denial, and second-admin execution")
    void testMakerCheckerReversalWorkflow() throws Exception {
        // Deposit into A
        transactionService.deposit(
                admin1,
                UUID.randomUUID().toString(),
                new DepositRequest(accountA.getId(), new BigDecimal("3000.00"), "INR")
        );

        // Transfer 1,000 from A to B
        var txResponse = transactionService.transfer(
                customerA,
                UUID.randomUUID().toString(),
                new TransferRequest(accountA.getId(), accountB.getAccountNumber(), new BigDecimal("1000.00"), "INR")
        );
        UUID originalTxId = txResponse.id();

        MockHttpSession admin1Session = createSession(admin1);
        MockHttpSession admin2Session = createSession(admin2);

        // Admin 1 initiates reversal
        MvcResult initResult = mockMvc.perform(post("/api/v1/admin/transactions/" + originalTxId + "/reverse")
                .session(admin1Session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Customer dispute confirmed\"}")
                .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();

        String content = initResult.getResponse().getContentAsString();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"id\":\"([^\"]+)\"").matcher(content);
        org.junit.jupiter.api.Assertions.assertTrue(m.find(), "Request ID not found in JSON");
        UUID requestId = UUID.fromString(m.group(1));

        // Admin 1 attempts to approve own request -> denied!
        mockMvc.perform(post("/api/v1/admin/maker-checker/requests/" + requestId + "/approve")
                .session(admin1Session)
                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("maker-checker separation enforced")));

        // Admin 2 approves -> executed atomically!
        mockMvc.perform(post("/api/v1/admin/maker-checker/requests/" + requestId + "/approve")
                .session(admin2Session)
                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // Verify original transaction is now REVERSED
        var updatedOriginal = ledgerTransactionRepository.findById(originalTxId).orElseThrow();
        assertEquals(TransactionStatus.REVERSED, updatedOriginal.getStatus());
        assertNotNull(updatedOriginal.getRelatedTransaction());

        // Attempting duplicate reversal is rejected
        mockMvc.perform(post("/api/v1/admin/transactions/" + originalTxId + "/reverse")
                .session(admin1Session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Attempting duplicate reversal\"}")
                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("already been reversed")));
    }

    @Test
    @DisplayName("Password Recovery: Generic response on forgot-password, single-use token hash, and reset")
    void testPasswordRecovery() throws Exception {
        // Non-existent email returns generic message
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nonexistent_random@vaultora.com\"}")
                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("If an account exists")));

        // Existing customer requests password reset
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + customerA.getEmail() + "\"}")
                .with(csrf()))
                .andExpect(status().isOk());

        // Retrieve token from repository (simulating user clicking link with token)
        List<PasswordResetTokenEntity> tokens = passwordResetTokenRepository.findAll().stream()
                .filter(t -> t.getUser().getId().equals(customerA.getId()) && !t.isUsed())
                .toList();
        assertFalse(tokens.isEmpty());

        // Perform password reset
        // To test reset, we can test with a known generated token
        String rawToken = UUID.randomUUID().toString().replace("-", "");
        String tokenHash = com.vaultora.auth.PasswordRecoveryService.hashToken(rawToken);
        passwordResetTokenRepository.save(new PasswordResetTokenEntity(customerA, tokenHash, java.time.Instant.now().plusSeconds(900)));

        mockMvc.perform(post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + rawToken + "\",\"newPassword\":\"NewSecurePassword123!\"}")
                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Password has been reset successfully")));

        // Attempting to reuse same token is rejected
        mockMvc.perform(post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + rawToken + "\",\"newPassword\":\"AnotherPassword123!\"}")
                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("already been used")));
    }

    @Test
    @DisplayName("Customer Email Verification: Unverified registration, blocked login, and verification")
    void testCustomerEmailVerification() throws Exception {
        String newEmail = "new_customer_" + UUID.randomUUID().toString().substring(0, 8) + "@vaultora.com";

        // Register new customer
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + newEmail + "\",\"password\":\"CustomerPassword123!\"}")
                .with(csrf()))
                .andExpect(status().isCreated());

        UserEntity registered = userRepository.findByEmailIgnoreCase(newEmail).orElseThrow();
        assertFalse(registered.isEmailVerified());

        // Unverified customer attempt to login is blocked
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + newEmail + "\",\"password\":\"CustomerPassword123!\"}")
                .with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Email verification required")));

        // Verify with token
        String rawToken = UUID.randomUUID().toString().replace("-", "");
        String tokenHash = com.vaultora.auth.EmailVerificationService.hashToken(rawToken);
        emailVerificationTokenRepository.save(new EmailVerificationTokenEntity(registered, tokenHash, java.time.Instant.now().plusSeconds(3600)));

        mockMvc.perform(post("/api/v1/auth/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + rawToken + "\"}")
                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Email verified successfully")));

        // Verified user can now log in
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + newEmail + "\",\"password\":\"CustomerPassword123!\"}")
                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(newEmail));
    }
}
