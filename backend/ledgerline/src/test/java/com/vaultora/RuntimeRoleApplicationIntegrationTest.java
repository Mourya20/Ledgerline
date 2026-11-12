package com.vaultora;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;

import com.vaultora.account.AccountService;
import com.vaultora.account.dto.AccountBalanceResponse;
import com.vaultora.account.dto.AccountResponse;
import com.vaultora.account.dto.CreateAccountRequest;
import com.vaultora.auth.AuthService;
import com.vaultora.auth.RegisterRequest;
import com.vaultora.domain.TransactionStatus;
import com.vaultora.domain.UserEntity;
import com.vaultora.domain.UserRole;
import com.vaultora.domain.UserStatus;
import com.vaultora.repository.UserRepository;
import com.vaultora.transaction.TransactionService;
import com.vaultora.transaction.dto.DepositRequest;
import com.vaultora.transaction.dto.TransactionEntryResponse;
import com.vaultora.transaction.dto.TransactionResponse;
import com.vaultora.transaction.dto.TransferRequest;
import com.vaultora.transaction.dto.WithdrawRequest;

/**
 * End-to-end integration test verifying that core application workflows execute
 * successfully when the Spring DataSource is connected exclusively as the
 * restricted {@code ledgerline_runtime} database role.
 *
 * Migration DDL is executed by Flyway using the migration role (ledgerline_app),
 * while all runtime application data access is performed by ledgerline_runtime.
 */
@SpringBootTest(properties = {
    "spring.datasource.username=${vaultora.runtime.username:ledgerline_runtime}",
    "spring.datasource.password=${vaultora.runtime.password}"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RuntimeRoleApplicationIntegrationTest {

    public static final UUID SYSTEM_CASH_VAULT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final String SYSTEM_CASH_VAULT_NUMBER = "SYS-INR-CASH-VAULT-001";

    @Autowired
    private AuthService authService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate applicationJdbcTemplate;

    @Value("${spring.datasource.url}")
    private String testDbUrl;

    @Value("${spring.flyway.user:ledgerline_app}")
    private String adminUser;

    @Value("${spring.flyway.password}")
    private String adminPassword;

    // Test state passed sequentially between ordered tests
    private UserEntity customerA;
    private UserEntity customerB;
    private UUID accountAId;
    private String accountANumber;
    private UUID accountBId;
    private String accountBNumber;

    @BeforeAll
    void setUpAll() {
        cleanTestDataAsAdmin();
        seedSystemCashVaultAsAdmin();
    }

    @AfterAll
    void tearDownAll() {
        cleanTestDataAsAdmin();
        seedSystemCashVaultAsAdmin();
    }

    private void cleanTestDataAsAdmin() {
        DriverManagerDataSource adminDs = new DriverManagerDataSource();
        adminDs.setDriverClassName("org.postgresql.Driver");
        adminDs.setUrl(testDbUrl);
        adminDs.setUsername(adminUser);
        adminDs.setPassword(adminPassword);
        JdbcTemplate adminJdbc = new JdbcTemplate(adminDs);
        TestDatabaseGuard.assertTestDatabase(adminJdbc);
        adminJdbc.execute("TRUNCATE TABLE audit_logs, ledger_entries, ledger_transactions, accounts, users CASCADE");
    }

    private void seedSystemCashVaultAsAdmin() {
        DriverManagerDataSource adminDs = new DriverManagerDataSource();
        adminDs.setDriverClassName("org.postgresql.Driver");
        adminDs.setUrl(testDbUrl);
        adminDs.setUsername(adminUser);
        adminDs.setPassword(adminPassword);
        JdbcTemplate adminJdbc = new JdbcTemplate(adminDs);
        TestDatabaseGuard.assertTestDatabase(adminJdbc);
        adminJdbc.update(
                "INSERT INTO accounts (id, account_number, owner_user_id, account_type, normal_balance, allow_negative, currency, status, balance) "
                        + "VALUES (?, ?, NULL, 'SYSTEM', 'DEBIT', FALSE, 'INR', 'ACTIVE', 0.00) "
                        + "ON CONFLICT (id) DO NOTHING",
                SYSTEM_CASH_VAULT_ID, SYSTEM_CASH_VAULT_NUMBER);
    }

    // =========================================================================
    // 1. IDENTITY CONFIRMATION
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("1. Confirm Application DataSource is running under ledgerline_runtime")
    void confirmApplicationDataSourceIsRuntimeRole() {
        String currentUser = applicationJdbcTemplate.queryForObject("SELECT CURRENT_USER", String.class);
        String sessionUser = applicationJdbcTemplate.queryForObject("SELECT SESSION_USER", String.class);

        assertEquals("ledgerline_runtime", currentUser, "Application DataSource must run as ledgerline_runtime");
        assertEquals("ledgerline_runtime", sessionUser, "Application session user must be ledgerline_runtime");
    }

    // =========================================================================
    // 2. USER REGISTRATION (INSERT on users)
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("2. User registration operates successfully under runtime role")
    void userRegistrationSucceedsUnderRuntimeRole() {
        authService.register(new RegisterRequest("runtime_user_a@example.com", "Password123!"));
        authService.register(new RegisterRequest("runtime_user_b@example.com", "Password123!"));

        customerA = userRepository.findByEmailIgnoreCase("runtime_user_a@example.com").orElseThrow();
        customerB = userRepository.findByEmailIgnoreCase("runtime_user_b@example.com").orElseThrow();

        assertNotNull(customerA.getId());
        assertEquals(UserRole.CUSTOMER, customerA.getRole());
        assertEquals(UserStatus.ACTIVE, customerA.getStatus());
        assertEquals(UserRole.CUSTOMER, customerB.getRole());
    }

    // =========================================================================
    // 3. ACCOUNT CREATION & RETRIEVAL (INSERT & SELECT on accounts)
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("3. Account creation and balance retrieval operate under runtime role")
    void accountCreationAndRetrievalSucceedUnderRuntimeRole() {
        AccountResponse respA = accountService.createAccount(customerA, new CreateAccountRequest("INR"));
        accountAId = respA.id();
        accountANumber = respA.accountNumber();

        AccountResponse respB = accountService.createAccount(customerB, new CreateAccountRequest("INR"));
        accountBId = respB.id();
        accountBNumber = respB.accountNumber();

        assertNotNull(accountAId);
        assertTrue(accountANumber.startsWith("VLTR"));
        assertEquals(new BigDecimal("0.00"), respA.balance());

        // Verify retrieval by ID
        AccountResponse fetchedA = accountService.getAccountById(accountAId, customerA.getId());
        assertEquals(accountAId, fetchedA.id());

        // Verify balance retrieval
        AccountBalanceResponse balA = accountService.getAccountBalance(accountAId, customerA.getId());
        assertEquals(new BigDecimal("0.00"), balA.balance());

        // Verify user account listing
        List<AccountResponse> listA = accountService.getUserAccounts(customerA.getId());
        assertEquals(1, listA.size());
        assertEquals(accountAId, listA.get(0).id());
    }

    // =========================================================================
    // 4. SIMULATED DEPOSIT (Cash In)
    // =========================================================================

    @Test
    @Order(4)
    @DisplayName("4. Deposit operation operates under runtime role")
    void depositOperatesUnderRuntimeRole() {
        String idempotencyKey = UUID.randomUUID().toString();
        DepositRequest req = new DepositRequest(accountAId, new BigDecimal("1000.00"), "INR");

        TransactionResponse tx = transactionService.deposit(customerA, idempotencyKey, req);

        assertNotNull(tx.id());
        assertEquals(TransactionStatus.COMPLETED, tx.status());
        assertEquals("DEPOSIT", tx.transactionType());

        // Verify updated account balance projection
        AccountBalanceResponse balA = accountService.getAccountBalance(accountAId, customerA.getId());
        assertEquals(new BigDecimal("1000.00"), balA.balance());
    }

    // =========================================================================
    // 5. SIMULATED WITHDRAWAL (Cash Out)
    // =========================================================================

    @Test
    @Order(5)
    @DisplayName("5. Withdrawal operation operates under runtime role")
    void withdrawalOperatesUnderRuntimeRole() {
        String idempotencyKey = UUID.randomUUID().toString();
        WithdrawRequest req = new WithdrawRequest(accountAId, new BigDecimal("400.00"), "INR");

        TransactionResponse tx = transactionService.withdraw(customerA, idempotencyKey, req);

        assertNotNull(tx.id());
        assertEquals(TransactionStatus.COMPLETED, tx.status());
        assertEquals("WITHDRAWAL", tx.transactionType());

        // Verify updated balance (1000 - 400 = 600)
        AccountBalanceResponse balA = accountService.getAccountBalance(accountAId, customerA.getId());
        assertEquals(new BigDecimal("600.00"), balA.balance());
    }

    // =========================================================================
    // 6. CUSTOMER-TO-CUSTOMER TRANSFER
    // =========================================================================

    @Test
    @Order(6)
    @DisplayName("6. Peer-to-peer customer transfer operates under runtime role")
    void customerTransferOperatesUnderRuntimeRole() {
        String idempotencyKey = UUID.randomUUID().toString();
        TransferRequest req = new TransferRequest(accountAId, accountBNumber, new BigDecimal("250.00"), "INR");

        TransactionResponse tx = transactionService.transfer(customerA, idempotencyKey, req);

        assertNotNull(tx.id());
        assertEquals(TransactionStatus.COMPLETED, tx.status());
        assertEquals("TRANSFER", tx.transactionType());

        // Sender balance: 600 - 250 = 350
        AccountBalanceResponse balA = accountService.getAccountBalance(accountAId, customerA.getId());
        assertEquals(new BigDecimal("350.00"), balA.balance());

        // Receiver balance: 0 + 250 = 250
        AccountBalanceResponse balB = accountService.getAccountBalance(accountBId, customerB.getId());
        assertEquals(new BigDecimal("250.00"), balB.balance());
    }

    // =========================================================================
    // 7. TRANSACTION HISTORY RETRIEVAL
    // =========================================================================

    @Test
    @Order(7)
    @DisplayName("7. Transaction history retrieval with viewer-aware masking operates under runtime role")
    void transactionHistoryRetrievalOperatesUnderRuntimeRole() {
        Page<TransactionResponse> historyA = transactionService.getTransactions(customerA.getId(), null, PageRequest.of(0, 10));

        // Customer A was involved in 3 transactions: 1 deposit, 1 withdrawal, 1 transfer
        assertEquals(3, historyA.getTotalElements());

        // Verify entries in transfer response are masked for counterpart
        TransactionResponse latestTransfer = historyA.getContent().get(0);
        assertEquals("TRANSFER", latestTransfer.transactionType());
        List<TransactionEntryResponse> entries = latestTransfer.entries();
        assertEquals(2, entries.size());

        // Customer A's entry has their accountId, counterparty's entry has masked account number and null accountId
        boolean hasOwnEntry = entries.stream().anyMatch(e -> accountAId.equals(e.accountId()));
        boolean hasMaskedCounterpart = entries.stream().anyMatch(e -> e.accountId() == null && e.accountNumber().startsWith("****"));
        assertTrue(hasOwnEntry, "Viewer should see own account details");
        assertTrue(hasMaskedCounterpart, "Viewer should see masked counterparty details");
    }

    // =========================================================================
    // 8. SECURITY CONTROLS: PROHIBITED DATABASE ACTIONS ARE REJECTED
    // =========================================================================

    @Test
    @Order(8)
    @DisplayName("8. Prohibited database operations fail under runtime identity")
    void prohibitedOperationsAreDeniedUnderRuntimeRole() {
        // Direct balance update is forbidden (privilege + call-stack trigger guard)
        assertThrows(DataAccessException.class, () ->
            applicationJdbcTemplate.update("UPDATE accounts SET balance = 999999.00 WHERE id = ?", accountAId));

        // Direct ledger entry deletion is forbidden (privilege REVOKE DELETE)
        assertThrows(DataAccessException.class, () ->
            applicationJdbcTemplate.update("DELETE FROM ledger_entries"));

        // Direct audit log deletion is forbidden (privilege REVOKE DELETE)
        assertThrows(DataAccessException.class, () ->
            applicationJdbcTemplate.update("DELETE FROM audit_logs"));

        // Direct table truncation is forbidden (privilege REVOKE TRUNCATE)
        assertThrows(DataAccessException.class, () ->
            applicationJdbcTemplate.execute("TRUNCATE TABLE accounts CASCADE"));

        // Direct role escalation via INSERT is forbidden (Migration V9 trigger)
        assertThrows(DataAccessException.class, () ->
            applicationJdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) "
                        + "VALUES (?, 'hacked_admin@example.com', 'hash', 'ADMIN', 'ACTIVE', NOW(), NOW())",
                UUID.randomUUID()));

        // Direct non-zero opening balance via INSERT is forbidden (Migration V9 trigger)
        assertThrows(DataAccessException.class, () ->
            applicationJdbcTemplate.update(
                "INSERT INTO accounts (id, account_number, owner_user_id, account_type, normal_balance, allow_negative, currency, status, balance, created_at, updated_at) "
                        + "VALUES (?, 'VLTR999999999999', ?, 'CUSTOMER', 'CREDIT', FALSE, 'INR', 'ACTIVE', 500.00, NOW(), NOW())",
                UUID.randomUUID(), customerA.getId()));
    }
}
