package com.ledgerline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.ledgerline.audit.AuditService;
import com.ledgerline.auth.LoginRateLimiter;
import com.ledgerline.domain.AccountEntity;
import com.ledgerline.domain.AccountType;
import com.ledgerline.domain.AuditLogEntity;
import com.ledgerline.domain.NormalBalance;
import com.ledgerline.domain.UserEntity;
import com.ledgerline.domain.UserRole;
import com.ledgerline.domain.UserStatus;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.AuditLogRepository;
import com.ledgerline.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Phase1IntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private LoginRateLimiter loginRateLimiter;

    @BeforeEach
    void setUp() {
        TestDatabaseGuard.assertTestDatabase(jdbcTemplate);
        loginRateLimiter.reset();
        cleanTestData();
    }

    @AfterEach
    void tearDown() {
        cleanTestData();
    }

    private void cleanTestData() {
        TestDatabaseGuard.assertTestDatabase(jdbcTemplate);
        jdbcTemplate.execute("TRUNCATE TABLE audit_logs, ledger_entries, ledger_transactions, accounts, users CASCADE");
    }

    // ==========================================
    // 1. ACCOUNTING MODEL & NORMAL BALANCE TESTS
    // ==========================================

    @Test
    void depositIncreasesBothCashAssetAndCustomerLiability() {
        UserEntity customer = insertUser("customer@example.com", UserRole.CUSTOMER);
        UUID cashAssetId = insertSystemAccount("CASH-ASSET-01", "USD", NormalBalance.DEBIT, false, BigDecimal.ZERO);
        UUID customerAccountId = insertCustomerAccount("CUST-ACC-01", customer, "USD", BigDecimal.ZERO);

        transactionTemplate.executeWithoutResult(status -> {
            UUID txId = insertPendingTransaction("USD");
            insertEntry(txId, cashAssetId, "DEBIT", "100.00", "USD");
            insertEntry(txId, customerAccountId, "CREDIT", "100.00", "USD");
            completeTransaction(txId);
        });

        BigDecimal cashBalance = getAccountBalance(cashAssetId);
        BigDecimal customerBalance = getAccountBalance(customerAccountId);

        assertEquals(new BigDecimal("100.00"), cashBalance, "Asset account (DEBIT normal) must increase on debit");
        assertEquals(new BigDecimal("100.00"), customerBalance, "Customer liability account (CREDIT normal) must increase on credit");
    }

    @Test
    void withdrawalDecreasesBothCustomerLiabilityAndCashAsset() {
        UserEntity customer = insertUser("customer@example.com", UserRole.CUSTOMER);
        UUID cashAssetId = insertSystemAccount("CASH-ASSET-01", "USD", NormalBalance.DEBIT, false, new BigDecimal("100.00"));
        UUID customerAccountId = insertCustomerAccount("CUST-ACC-01", customer, "USD", new BigDecimal("100.00"));

        transactionTemplate.executeWithoutResult(status -> {
            UUID txId = insertPendingTransaction("USD");
            insertEntry(txId, customerAccountId, "DEBIT", "40.00", "USD");
            insertEntry(txId, cashAssetId, "CREDIT", "40.00", "USD");
            completeTransaction(txId);
        });

        BigDecimal customerBalance = getAccountBalance(customerAccountId);
        BigDecimal cashBalance = getAccountBalance(cashAssetId);

        assertEquals(new BigDecimal("60.00"), customerBalance, "Customer liability decreases on debit");
        assertEquals(new BigDecimal("60.00"), cashBalance, "Asset account decreases on credit");
    }

    @Test
    void transferDecreasesSenderAndIncreasesReceiver() {
        UserEntity sender = insertUser("sender@example.com", UserRole.CUSTOMER);
        UserEntity receiver = insertUser("receiver@example.com", UserRole.CUSTOMER);

        UUID senderAcc = insertCustomerAccount("SND-01", sender, "USD", new BigDecimal("100.00"));
        UUID receiverAcc = insertCustomerAccount("RCV-01", receiver, "USD", new BigDecimal("20.00"));

        transactionTemplate.executeWithoutResult(status -> {
            UUID txId = insertPendingTransaction("USD");
            insertEntry(txId, senderAcc, "DEBIT", "30.00", "USD");
            insertEntry(txId, receiverAcc, "CREDIT", "30.00", "USD");
            completeTransaction(txId);
        });

        assertEquals(new BigDecimal("70.00"), getAccountBalance(senderAcc));
        assertEquals(new BigDecimal("50.00"), getAccountBalance(receiverAcc));
    }

    @Test
    void customerBalanceCannotBecomeNegative() {
        UserEntity customer = insertUser("overdraft@example.com", UserRole.CUSTOMER);
        UUID cashAssetId = insertSystemAccount("CASH-01", "USD", NormalBalance.DEBIT, false, new BigDecimal("100.00"));
        UUID customerAccountId = insertCustomerAccount("CUST-OD-01", customer, "USD", new BigDecimal("50.00"));

        // Attempting to withdraw 60.00 when balance is 50.00 must violate balance policy constraint
        assertThrows(DataAccessException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            UUID txId = insertPendingTransaction("USD");
            insertEntry(txId, customerAccountId, "DEBIT", "60.00", "USD");
            insertEntry(txId, cashAssetId, "CREDIT", "60.00", "USD");
            completeTransaction(txId);
        }));

        // Rolled back; balance remains 50.00
        assertEquals(new BigDecimal("50.00"), getAccountBalance(customerAccountId));
        assertEquals(new BigDecimal("100.00"), getAccountBalance(cashAssetId));
    }

    @Test
    void failedFinancialTransactionRollsBackAccountBalanceChanges() {
        UserEntity customer = insertUser("rollback@example.com", UserRole.CUSTOMER);
        UUID cashAssetId = insertSystemAccount("CASH-RB-01", "USD", NormalBalance.DEBIT, false, new BigDecimal("100.00"));
        UUID customerAccountId = insertCustomerAccount("CUST-RB-01", customer, "USD", new BigDecimal("100.00"));

        assertThrows(RuntimeException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            UUID txId = insertPendingTransaction("USD");
            insertEntry(txId, cashAssetId, "DEBIT", "50.00", "USD");
            insertEntry(txId, customerAccountId, "CREDIT", "50.00", "USD");
            completeTransaction(txId);
            throw new RuntimeException("Simulated mid-transaction failure");
        }));

        assertEquals(new BigDecimal("100.00"), getAccountBalance(cashAssetId));
        assertEquals(new BigDecimal("100.00"), getAccountBalance(customerAccountId));
    }

    @Test
    void jpaRefreshesBalanceUpdatedByDatabaseTriggers() {
        UserEntity customer = insertUser("refresh@example.com", UserRole.CUSTOMER);
        AccountEntity account = accountRepository.save(new AccountEntity("REFRESH-01", customer, AccountType.CUSTOMER,
                NormalBalance.CREDIT, false, "USD"));
        final UUID customerAccountId = account.getId();

        assertEquals(new BigDecimal("0.00"), account.getBalance());

        // Perform balance-affecting entry through a valid completed transaction
        UUID cashAssetId = insertSystemAccount("CASH-REFRESH", "USD", NormalBalance.DEBIT, false, BigDecimal.ZERO);
        transactionTemplate.executeWithoutResult(status -> {
            UUID txId = insertPendingTransaction("USD");
            insertEntry(txId, cashAssetId, "DEBIT", "25.00", "USD");
            insertEntry(txId, customerAccountId, "CREDIT", "25.00", "USD");
            completeTransaction(txId);
        });

        // Refresh entity from DB
        AccountEntity refreshed = accountRepository.refresh(account);
        assertEquals(new BigDecimal("25.00"), refreshed.getBalance(), "Refreshed JPA entity must reflect trigger-updated balance");
    }

    // ==========================================
    // 2. TRANSACTION LIFECYCLE & INTEGRITY TESTS
    // ==========================================

    @Test
    void validBalancedCompletedTransactionCommits() {
        UUID debitAccount = insertSystemAccount("SYS-DEBIT-01", "USD", NormalBalance.DEBIT, false, BigDecimal.ZERO);
        UUID creditAccount = insertSystemAccount("SYS-CREDIT-01", "USD", NormalBalance.CREDIT, false, BigDecimal.ZERO);

        transactionTemplate.executeWithoutResult(status -> {
            UUID txId = insertPendingTransaction("USD");
            insertEntry(txId, debitAccount, "DEBIT", "50.00", "USD");
            insertEntry(txId, creditAccount, "CREDIT", "50.00", "USD");
            completeTransaction(txId);
        });

        assertEquals(new BigDecimal("50.00"), getAccountBalance(debitAccount));
        assertEquals(new BigDecimal("50.00"), getAccountBalance(creditAccount));
    }

    @Test
    void unbalancedTransactionFailsAtCommit() {
        assertThrows(DataAccessException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            UUID txId = insertPendingTransaction("USD");
            UUID debitAccount = insertSystemAccount("SYS-BAL-01", "USD", NormalBalance.DEBIT, false, BigDecimal.ZERO);
            UUID creditAccount = insertSystemAccount("SYS-BAL-02", "USD", NormalBalance.CREDIT, false, BigDecimal.ZERO);
            insertEntry(txId, debitAccount, "DEBIT", "10.00", "USD");
            insertEntry(txId, creditAccount, "CREDIT", "9.00", "USD");
            completeTransaction(txId);
        }));
    }

    @Test
    void completedTransactionRequiresEntries() {
        assertThrows(DataAccessException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            UUID txId = insertPendingTransaction("USD");
            completeTransaction(txId);
        }));
    }

    @Test
    void pendingTransactionWithEntriesCannotCommit() {
        // A transaction with ledger entries must finish as COMPLETED at commit time
        assertThrows(DataAccessException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            UUID txId = insertPendingTransaction("USD");
            UUID debitAccount = insertSystemAccount("SYS-PEN-01", "USD", NormalBalance.DEBIT, false, BigDecimal.ZERO);
            UUID creditAccount = insertSystemAccount("SYS-PEN-02", "USD", NormalBalance.CREDIT, false, BigDecimal.ZERO);
            insertEntry(txId, debitAccount, "DEBIT", "10.00", "USD");
            insertEntry(txId, creditAccount, "CREDIT", "10.00", "USD");
            // Do not call completeTransaction(txId); remains PENDING
        }));
    }

    @Test
    void failedTransactionWithEntriesCannotCommit() {
        assertThrows(DataAccessException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            UUID txId = insertPendingTransaction("USD");
            UUID debitAccount = insertSystemAccount("SYS-FAIL-01", "USD", NormalBalance.DEBIT, false, BigDecimal.ZERO);
            UUID creditAccount = insertSystemAccount("SYS-FAIL-02", "USD", NormalBalance.CREDIT, false, BigDecimal.ZERO);
            insertEntry(txId, debitAccount, "DEBIT", "10.00", "USD");
            insertEntry(txId, creditAccount, "CREDIT", "10.00", "USD");
            jdbcTemplate.update("UPDATE ledger_transactions SET status = 'FAILED' WHERE id = ?", txId);
        }));
    }

    @Test
    void completedTransactionAndEntriesAreImmutable() {
        UUID debitAccount = insertSystemAccount("SYS-IMM-01", "USD", NormalBalance.DEBIT, false, BigDecimal.ZERO);
        UUID creditAccount = insertSystemAccount("SYS-IMM-02", "USD", NormalBalance.CREDIT, false, BigDecimal.ZERO);
        UUID txId = insertPendingTransaction("USD");

        transactionTemplate.executeWithoutResult(status -> {
            insertEntry(txId, debitAccount, "DEBIT", "10.00", "USD");
            insertEntry(txId, creditAccount, "CREDIT", "10.00", "USD");
            completeTransaction(txId);
        });

        // 1. Cannot mutate completed transaction status (e.g. to REVERSED)
        assertThrows(DataAccessException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("UPDATE ledger_transactions SET status = 'REVERSED' WHERE id = ?", txId);
        }));

        // 2. Cannot delete entries from completed transaction
        assertThrows(DataAccessException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("DELETE FROM ledger_entries WHERE transaction_id = ?", txId);
        }));

        // 3. Cannot add entries to a completed transaction
        assertThrows(DataAccessException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            insertEntry(txId, debitAccount, "DEBIT", "5.00", "USD");
        }));

        // 4. Cannot update existing entries
        assertThrows(DataAccessException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("UPDATE ledger_entries SET amount = 20.00 WHERE transaction_id = ?", txId);
        }));
    }

    @Test
    void ledgerEntryCurrencyMustMatchTransactionAndAccount() {
        assertThrows(DataAccessException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            UUID txId = insertPendingTransaction("EUR");
            UUID accountId = insertSystemAccount("SYS-CURR-01", "USD", NormalBalance.DEBIT, false, BigDecimal.ZERO);
            insertEntry(txId, accountId, "DEBIT", "10.00", "USD");
        }));
    }

    @Test
    void transactionAndAccountCurrencyCannotDivergeFromExistingEntries() {
        UUID debitAccount = insertSystemAccount("SYS-DIV-01", "USD", NormalBalance.DEBIT, false, BigDecimal.ZERO);
        UUID creditAccount = insertSystemAccount("SYS-DIV-02", "USD", NormalBalance.CREDIT, false, BigDecimal.ZERO);
        UUID txId = insertPendingTransaction("USD");

        transactionTemplate.executeWithoutResult(status -> {
            insertEntry(txId, debitAccount, "DEBIT", "10.00", "USD");
            insertEntry(txId, creditAccount, "CREDIT", "10.00", "USD");
            completeTransaction(txId);
        });

        // Attempting to change transaction currency while it has USD entries must fail
        assertThrows(DataAccessException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("UPDATE ledger_transactions SET currency = 'EUR' WHERE id = ?", txId);
        }));

        // Attempting to change account currency while it has USD entries must fail
        assertThrows(DataAccessException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("UPDATE accounts SET currency = 'EUR' WHERE id = ?", debitAccount);
        }));
    }

    @Test
    void accountBalanceProjectionCannotBeChangedDirectly() {
        UUID accountId = insertSystemAccount("SYS-PROJ-01", "USD", NormalBalance.DEBIT, false, BigDecimal.ZERO);
        assertThrows(DataAccessException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("UPDATE accounts SET balance = ? WHERE id = ?", new BigDecimal("100.00"), accountId);
        }));
    }

    // ==========================================
    // 3. AUTHENTICATION & SESSION SECURITY TESTS
    // ==========================================

    @Test
    void sessionFixationProtectionRotatesSessionId() throws Exception {
        String email = "session-rotate@example.com";
        String password = "CorrectHorseBattery12";
        register(email, password);

        // 1. Establish an unauthenticated session
        MockHttpSession unauthSession = new MockHttpSession();
        unauthSession.setAttribute("preAuthMarker", "true");
        String initialSessionId = unauthSession.getId();

        // 2. Perform login using this unauthenticated session
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                .session(unauthSession)
                .with(request -> {
                    request.setRequestedSessionId(initialSessionId);
                    request.setRequestedSessionIdValid(true);
                    return request;
                })
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession postLoginSession = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertNotNull(postLoginSession, "Session must exist after login");
        String newSessionId = postLoginSession.getId();

        // Verify session ID was rotated
        assertNotEquals(initialSessionId, newSessionId, "Session ID must be rotated upon authentication");

        // Verify new session accesses authenticated endpoint
        mockMvc.perform(get("/api/v1/auth/me").session(postLoginSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));

        // Verify old session was invalidated and cannot access authenticated endpoints
        assertTrue(unauthSession.isInvalid(), "Old unauthenticated session must be invalidated");
        mockMvc.perform(get("/api/v1/auth/me").session(unauthSession))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutInvalidatesSessionAndClearsContext() throws Exception {
        String email = "logout-test@example.com";
        String password = "CorrectHorseBattery12";
        register(email, password);

        MvcResult loginResult = login(email, password);
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk());

        // Perform logout
        mockMvc.perform(post("/api/v1/auth/logout")
                .session(session)
                .with(csrf()))
                .andExpect(status().isOk());

        assertTrue(session.isInvalid(), "HTTP session must be invalidated on logout");

        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledUserCannotAuthenticateAndExistingSessionIsInvalidated() throws Exception {
        String email = "disabled-user@example.com";
        String password = "CorrectHorseBattery12";
        register(email, password);

        MvcResult loginResult = login(email, password);
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        // Disable user
        UserEntity user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.setStatus(UserStatus.DISABLED);
        userRepository.saveAndFlush(user);

        // Existing session rejected
        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isUnauthorized());

        // Subsequent login attempt rejected
        mockMvc.perform(post("/api/v1/auth/login")
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void csrfIsRequiredForStateChangingEndpoints() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content("{\"email\":\"nocsrf@example.com\",\"password\":\"CorrectHorseBattery12\"}"))
                .andExpect(status().isForbidden());
    }

    // ==========================================
    // 4. ROLE & AUDITOR AUTHORIZATION TESTS
    // ==========================================

    @Test
    void registrationAssignsCustomerRoleOnly() throws Exception {
        String email = "wannabe-admin@example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"CorrectHorseBattery12\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CUSTOMER"));

        UserEntity user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertEquals(UserRole.CUSTOMER, user.getRole());
    }

    @Test
    void auditorIsStrictlyReadOnly() throws Exception {
        insertUserWithPassword("auditor@example.com", "CorrectHorseBattery12", UserRole.AUDITOR);
        insertUserWithPassword("admin@example.com", "CorrectHorseBattery12", UserRole.ADMIN);
        insertUserWithPassword("customer@example.com", "CorrectHorseBattery12", UserRole.CUSTOMER);

        MvcResult auditorLogin = login("auditor@example.com", "CorrectHorseBattery12");
        MockHttpSession auditorSession = (MockHttpSession) auditorLogin.getRequest().getSession(false);

        MvcResult adminLogin = login("admin@example.com", "CorrectHorseBattery12");
        MockHttpSession adminSession = (MockHttpSession) adminLogin.getRequest().getSession(false);

        MvcResult customerLogin = login("customer@example.com", "CorrectHorseBattery12");
        MockHttpSession customerSession = (MockHttpSession) customerLogin.getRequest().getSession(false);

        // 1. AUDITOR can perform GET on audit logs
        mockMvc.perform(get("/api/v1/audit/logs").session(auditorSession))
                .andExpect(status().isOk());

        // 2. AUDITOR CANNOT perform POST on audit logs (must be 403 Forbidden)
        mockMvc.perform(post("/api/v1/audit/logs")
                .session(auditorSession)
                .with(csrf())
                .contentType("application/json")
                .content("{\"action\":\"TEST\"}"))
                .andExpect(status().isForbidden());

        // 3. AUDITOR CANNOT access administrative endpoints
        mockMvc.perform(get("/api/v1/admin/test").session(auditorSession))
                .andExpect(status().isForbidden());

        // 4. CUSTOMER CANNOT access audit or admin endpoints
        mockMvc.perform(get("/api/v1/audit/logs").session(customerSession))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/test").session(customerSession))
                .andExpect(status().isForbidden());

        // 5. ADMIN can access both admin and audit endpoints
        mockMvc.perform(get("/api/v1/admin/test").session(adminSession))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/audit/logs")
                .session(adminSession)
                .with(csrf()))
                .andExpect(status().isOk());
    }

    // ==========================================
    // 5. PASSWORD HANDLING & VALIDATION TESTS
    // ==========================================

    @Test
    void passwordValidationEnforcesLengthAndUtf8ByteLimit() throws Exception {
        // Less than 12 characters -> rejected
        mockMvc.perform(post("/api/v1/auth/register")
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"short@example.com\",\"password\":\"Short123\"}"))
                .andExpect(status().isBadRequest());

        // Exactly 72 ASCII bytes -> accepted
        String ascii72 = "a".repeat(72);
        mockMvc.perform(post("/api/v1/auth/register")
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"ascii72@example.com\",\"password\":\"" + ascii72 + "\"}"))
                .andExpect(status().isCreated());

        // 73 ASCII bytes -> rejected
        String ascii73 = "a".repeat(73);
        mockMvc.perform(post("/api/v1/auth/register")
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"ascii73@example.com\",\"password\":\"" + ascii73 + "\"}"))
                .andExpect(status().isBadRequest());

        // 24 3-byte unicode characters ('€') = 72 bytes -> accepted
        String unicode72 = "€".repeat(24);
        mockMvc.perform(post("/api/v1/auth/register")
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"unicode72@example.com\",\"password\":\"" + unicode72 + "\"}"))
                .andExpect(status().isCreated());

        // 25 3-byte unicode characters = 75 bytes -> rejected
        String unicode75 = "€".repeat(25);
        mockMvc.perform(post("/api/v1/auth/register")
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"unicode75@example.com\",\"password\":\"" + unicode75 + "\"}"))
                .andExpect(status().isBadRequest());
    }

    // ==========================================
    // 6. LOGIN RATE LIMITING TESTS
    // ==========================================

    @Test
    void loginRateLimiterBlocksExcessiveAttemptsWith429() throws Exception {
        String email = "ratelimit@example.com";
        String password = "CorrectHorseBattery12";
        register(email, password);

        // Perform 5 failed login attempts
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                    .with(csrf())
                    .contentType("application/json")
                    .content("{\"email\":\"" + email + "\",\"password\":\"wrong-password\"}"))
                    .andExpect(status().isUnauthorized());
        }

        // 6th attempt must be blocked by rate limiter with 429 Too Many Requests
        mockMvc.perform(post("/api/v1/auth/login")
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("Too many login attempts. Please try again later."));
    }

    // ==========================================
    // 7. AUDIT LOGGING TESTS
    // ==========================================

    @Test
    void auditEventsAreCreatedForAuthenticationLifecycle() throws Exception {
        String email = "audit-lifecycle@example.com";
        String password = "CorrectHorseBattery12";

        // 1. Registration
        register(email, password);
        List<AuditLogEntity> regLogs = auditLogRepository.findAllByActionOrderByCreatedAtDesc(AuditService.ACTION_REGISTER_SUCCESS);
        assertFalse(regLogs.isEmpty(), "Registration must create audit log");
        assertFalse(regLogs.get(0).getDetails().contains("password"), "Audit logs must not contain credentials");

        // 2. Failed login attempt
        mockMvc.perform(post("/api/v1/auth/login")
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"wrong-pass\"}"))
                .andExpect(status().isUnauthorized());

        List<AuditLogEntity> failLogs = auditLogRepository.findAllByActionOrderByCreatedAtDesc(AuditService.ACTION_LOGIN_FAILURE);
        assertFalse(failLogs.isEmpty(), "Failed login must create audit log");

        // 3. Successful login
        MvcResult loginResult = login(email, password);
        List<AuditLogEntity> loginLogs = auditLogRepository.findAllByActionOrderByCreatedAtDesc(AuditService.ACTION_LOGIN_SUCCESS);
        assertFalse(loginLogs.isEmpty(), "Login must create audit log");

        // 4. Logout
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        mockMvc.perform(post("/api/v1/auth/logout").session(session).with(csrf()))
                .andExpect(status().isOk());

        List<AuditLogEntity> logoutLogs = auditLogRepository.findAllByActionOrderByCreatedAtDesc(AuditService.ACTION_LOGOUT);
        assertFalse(logoutLogs.isEmpty(), "Logout must create audit log");
    }

    @Test
    void auditLogsRejectUpdateAndDelete() {
        UUID auditId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO audit_logs (id, action, details) VALUES (?, ?, '{}'::jsonb)", auditId, "TEST");

        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("UPDATE audit_logs SET action = 'MODIFIED' WHERE id = ?", auditId));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("DELETE FROM audit_logs WHERE id = ?", auditId));
    }

    // ==========================================
    // 8. TEST DATABASE ISOLATION GUARD TEST
    // ==========================================

    @Test
    void testDatabaseGuardProtectsNonTestDatabase() {
        // Current test DB must be ledgerline_test_db
        TestDatabaseGuard.assertTestDatabase(jdbcTemplate);
        String currentDb = jdbcTemplate.queryForObject("SELECT current_database()", String.class);
        assertEquals("ledgerline_test_db", currentDb);
    }

    // ==========================================
    // HELPER METHODS
    // ==========================================

    private void register(String email, String password) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isCreated());
    }

    private MvcResult login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private UserEntity insertUser(String email, UserRole role) {
        return userRepository.save(new UserEntity(email, passwordEncoder.encode("Password12345!"), role));
    }

    private UserEntity insertUserWithPassword(String email, String rawPassword, UserRole role) {
        return userRepository.save(new UserEntity(email, passwordEncoder.encode(rawPassword), role));
    }

    private UUID insertCustomerAccount(String accountNumber, UserEntity owner, String currency, BigDecimal initialBalance) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO accounts
                    (id, account_number, owner_user_id, account_type, normal_balance, allow_negative, currency, status, balance)
                VALUES (?, ?, ?, 'CUSTOMER', 'CREDIT', false, ?, 'ACTIVE', ?)
                """, id, accountNumber, owner.getId(), currency, initialBalance);
        return id;
    }

    private UUID insertSystemAccount(String accountNumber, String currency, NormalBalance normalBalance, boolean allowNegative, BigDecimal initialBalance) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO accounts
                    (id, account_number, account_type, normal_balance, allow_negative, currency, status, balance)
                VALUES (?, ?, 'SYSTEM', ?, ?, ?, 'ACTIVE', ?)
                """, id, accountNumber, normalBalance.name(), allowNegative, currency, initialBalance);
        return id;
    }

    private UUID insertPendingTransaction(String currency) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ledger_transactions
                    (id, transaction_type, status, currency)
                VALUES (?, 'TEST', 'PENDING', ?)
                """, id, currency);
        return id;
    }

    private void insertEntry(UUID transactionId, UUID accountId, String entryType, String amount, String currency) {
        jdbcTemplate.update("""
                INSERT INTO ledger_entries
                    (id, transaction_id, account_id, entry_type, amount, currency)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), transactionId, accountId, entryType, new BigDecimal(amount), currency);
    }

    private void completeTransaction(UUID transactionId) {
        jdbcTemplate.update("""
                UPDATE ledger_transactions
                SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, transactionId);
    }

    private BigDecimal getAccountBalance(UUID accountId) {
        return jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, accountId);
    }
}
