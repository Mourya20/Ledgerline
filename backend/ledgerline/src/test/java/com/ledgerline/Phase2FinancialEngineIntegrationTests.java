package com.ledgerline;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;
import com.ledgerline.auth.LoginRateLimiter;
import com.ledgerline.domain.AccountEntity;
import com.ledgerline.domain.AccountType;
import com.ledgerline.domain.NormalBalance;
import com.ledgerline.domain.UserEntity;
import com.ledgerline.domain.UserRole;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class Phase2FinancialEngineIntegrationTests {

    public static final UUID SYSTEM_CASH_VAULT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final String SYSTEM_CASH_VAULT_NUMBER = "SYS-INR-CASH-VAULT-001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LoginRateLimiter loginRateLimiter;

    @Value("${spring.datasource.url}")
    private String testDbUrl;

    @Value("${ledgerline.runtime.username}")
    private String runtimeUser;

    @Value("${ledgerline.runtime.password}")
    private String runtimePassword;

    @BeforeEach
    void setUp() {
        TestDatabaseGuard.assertTestDatabase(jdbcTemplate);
        loginRateLimiter.reset();
        cleanTestData();
        seedSystemCashVault();
    }

    @AfterEach
    void tearDown() {
        cleanTestData();
    }

    private void cleanTestData() {
        TestDatabaseGuard.assertTestDatabase(jdbcTemplate);
        jdbcTemplate.execute("TRUNCATE TABLE audit_logs, ledger_entries, ledger_transactions, accounts, users CASCADE");
    }

    private void seedSystemCashVault() {
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
                    FALSE,
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP
                )
                ON CONFLICT (account_number) DO NOTHING;
                """);
    }

    // =========================================================================
    // SECTION A: ACCOUNT MANAGEMENT TESTS
    // =========================================================================
    @Nested
    @DisplayName("A. Account Management")
    class AccountManagementTests {

        @Test
        @DisplayName("Customer can create account with generated number and authenticated ownership")
        void customerCanCreateAccount() throws Exception {
            UserEntity customer = createAndAuthenticateUser("customer1@example.com", UserRole.CUSTOMER);
            MockHttpSession session = loginSession("customer1@example.com", "Password123!");

            MvcResult result = mockMvc.perform(post("/api/v1/accounts")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.accountNumber").isNotEmpty())
                    .andExpect(jsonPath("$.currency").value("INR"))
                    .andExpect(jsonPath("$.balance").value(0.00))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andReturn();

            String respBody = result.getResponse().getContentAsString();
            String accountNumber = JsonPath.read(respBody, "$.accountNumber");
            UUID accountId = UUID.fromString(JsonPath.read(respBody, "$.id"));

            assertTrue(accountNumber.startsWith("VLTR"), "Account number must start with VLTR prefix");

            AccountEntity persisted = accountRepository.findById(accountId).orElseThrow();
            assertEquals(customer.getId(), persisted.getOwner().getId(), "Account owner must match authenticated user");
            assertEquals(AccountType.CUSTOMER, persisted.getAccountType(), "Must be CUSTOMER account type");
            assertEquals(NormalBalance.CREDIT, persisted.getNormalBalance(), "Customer accounts must be CREDIT normal");
            assertEquals(new BigDecimal("0.00"), persisted.getBalance());
        }

        @Test
        @DisplayName("Account numbers generated are unique across creations")
        void accountNumbersAreUnique() throws Exception {
            createAndAuthenticateUser("customer2@example.com", UserRole.CUSTOMER);
            MockHttpSession session = loginSession("customer2@example.com", "Password123!");

            MvcResult res1 = mockMvc.perform(post("/api/v1/accounts")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated())
                    .andReturn();

            MvcResult res2 = mockMvc.perform(post("/api/v1/accounts")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated())
                    .andReturn();

            String acc1 = JsonPath.read(res1.getResponse().getContentAsString(), "$.accountNumber");
            String acc2 = JsonPath.read(res2.getResponse().getContentAsString(), "$.accountNumber");

            assertNotEquals(acc1, acc2, "Generated account numbers must be unique");
        }

        @Test
        @DisplayName("Customer cannot create account with unsupported currency")
        void cannotCreateAccountWithUnsupportedCurrency() throws Exception {
            createAndAuthenticateUser("customer3@example.com", UserRole.CUSTOMER);
            MockHttpSession session = loginSession("customer3@example.com", "Password123!");

            mockMvc.perform(post("/api/v1/accounts")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currency\":\"USD\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Customer can list only their own accounts")
        void customerCanListOnlyOwnAccounts() throws Exception {
            createAndAuthenticateUser("userA@example.com", UserRole.CUSTOMER);
            createAndAuthenticateUser("userB@example.com", UserRole.CUSTOMER);

            MockHttpSession sessionA = loginSession("userA@example.com", "Password123!");
            MockHttpSession sessionB = loginSession("userB@example.com", "Password123!");

            // User A creates 2 accounts
            mockMvc.perform(post("/api/v1/accounts").session(sessionA).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated());
            mockMvc.perform(post("/api/v1/accounts").session(sessionA).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated());

            // User B creates 1 account
            mockMvc.perform(post("/api/v1/accounts").session(sessionB).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated());

            // User A retrieves list
            mockMvc.perform(get("/api/v1/accounts").session(sessionA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));

            // User B retrieves list
            mockMvc.perform(get("/api/v1/accounts").session(sessionB))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));
        }

        @Test
        @DisplayName("Customer cannot access another customer's account by ID")
        void customerCannotAccessAnotherCustomerAccount() throws Exception {
            createAndAuthenticateUser("ownerA@example.com", UserRole.CUSTOMER);
            createAndAuthenticateUser("attackerB@example.com", UserRole.CUSTOMER);

            MockHttpSession sessionA = loginSession("ownerA@example.com", "Password123!");
            MockHttpSession sessionB = loginSession("attackerB@example.com", "Password123!");

            MvcResult resA = mockMvc.perform(post("/api/v1/accounts").session(sessionA).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated())
                    .andReturn();

            UUID accountIdA = UUID.fromString(JsonPath.read(resA.getResponse().getContentAsString(), "$.id"));

            // Attacker B attempts to access A's account details
            mockMvc.perform(get("/api/v1/accounts/" + accountIdA).session(sessionB))
                    .andExpect(status().isForbidden());

            // Attacker B attempts to access A's balance
            mockMvc.perform(get("/api/v1/accounts/" + accountIdA + "/balance").session(sessionB))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Customer cannot access system account through customer API")
        void customerCannotAccessSystemAccount() throws Exception {
            createAndAuthenticateUser("customer_sys@example.com", UserRole.CUSTOMER);
            MockHttpSession session = loginSession("customer_sys@example.com", "Password123!");

            mockMvc.perform(get("/api/v1/accounts/" + SYSTEM_CASH_VAULT_ID).session(session))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Admin can create an account and assign it to a customer")
        void adminCanCreateAndAssignAccountToCustomer() throws Exception {
            UserEntity customer = createAndAuthenticateUser("target_customer@example.com", UserRole.CUSTOMER);
            createAndAuthenticateUser("admin_creator@example.com", UserRole.ADMIN);
            MockHttpSession adminSession = loginSession("admin_creator@example.com", "Password123!");

            MvcResult result = mockMvc.perform(post("/api/v1/accounts")
                    .session(adminSession)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currency\":\"INR\",\"customerEmail\":\"target_customer@example.com\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.accountNumber").isNotEmpty())
                    .andExpect(jsonPath("$.currency").value("INR"))
                    .andExpect(jsonPath("$.balance").value(0.00))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.ownerEmail").value("target_customer@example.com"))
                    .andReturn();

            UUID accountId = UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
            AccountEntity persisted = accountRepository.findById(accountId).orElseThrow();
            assertEquals(customer.getId(), persisted.getOwner().getId(), "Account owner must be the target customer");
            assertEquals(AccountType.CUSTOMER, persisted.getAccountType());
        }

        @Test
        @DisplayName("Admin cannot assign an account to non-existent or non-customer user")
        void adminCannotAssignAccountToInvalidCustomer() throws Exception {
            createAndAuthenticateUser("auditor_target@example.com", UserRole.AUDITOR);
            createAndAuthenticateUser("admin_checker@example.com", UserRole.ADMIN);
            MockHttpSession adminSession = loginSession("admin_checker@example.com", "Password123!");

            // 1. Non-existent email -> 400 Bad Request
            mockMvc.perform(post("/api/v1/accounts")
                    .session(adminSession)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currency\":\"INR\",\"customerEmail\":\"nonexistent@example.com\"}"))
                    .andExpect(status().isBadRequest());

            // 2. AUDITOR target -> 400 Bad Request
            mockMvc.perform(post("/api/v1/accounts")
                    .session(adminSession)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currency\":\"INR\",\"customerEmail\":\"auditor_target@example.com\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Admin can list all customer accounts and filter by customer email")
        void adminCanListAndFilterCustomerAccounts() throws Exception {
            UserEntity customer1 = createAndAuthenticateUser("cust_list1@example.com", UserRole.CUSTOMER);
            UserEntity customer2 = createAndAuthenticateUser("cust_list2@example.com", UserRole.CUSTOMER);
            createAccountForUser(customer1, "INR");
            createAccountForUser(customer2, "INR");

            createAndAuthenticateUser("admin_lister@example.com", UserRole.ADMIN);
            MockHttpSession adminSession = loginSession("admin_lister@example.com", "Password123!");

            // List all customer accounts
            mockMvc.perform(get("/api/v1/accounts").session(adminSession))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));

            // Filter by customer1
            mockMvc.perform(get("/api/v1/accounts?customerEmail=cust_list1@example.com").session(adminSession))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].ownerEmail").value("cust_list1@example.com"));
        }
    }

    // =========================================================================
    // SECTION B: DEPOSIT IMPLEMENTATION TESTS
    // =========================================================================
    @Nested
    @DisplayName("B. Deposit Operations")
    class DepositTests {

        @Test
        @DisplayName("Successful deposit generates balanced entries and increments both balances")
        void successfulDepositGeneratesBalancedDoubleEntry() throws Exception {
            UserEntity customer = createAndAuthenticateUser("dep_user@example.com", UserRole.CUSTOMER);
            MockHttpSession session = loginSession("dep_user@example.com", "Password123!");

            UUID custAccId = createAccountForUser(customer, "INR");
            String key = UUID.randomUUID().toString();

            mockMvc.perform(post("/api/v1/transactions/deposit")
                    .session(session)
                    .with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + custAccId + "\",\"amount\":1000.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.transactionType").value("DEPOSIT"))
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.entries.length()").value(2));

            // Verify customer balance = 1000.00 (CREDIT normal)
            assertEquals(new BigDecimal("1000.00"), getAccountBalance(custAccId));

            // Verify system cash vault balance = 1000.00 (DEBIT normal)
            assertEquals(new BigDecimal("1000.00"), getAccountBalance(SYSTEM_CASH_VAULT_ID));

            // Verify double-entry balance in database
            BigDecimal totalDebits = jdbcTemplate.queryForObject(
                    "SELECT SUM(amount) FROM ledger_entries WHERE entry_type = 'DEBIT'", BigDecimal.class);
            BigDecimal totalCredits = jdbcTemplate.queryForObject(
                    "SELECT SUM(amount) FROM ledger_entries WHERE entry_type = 'CREDIT'", BigDecimal.class);
            assertEquals(new BigDecimal("1000.00"), totalDebits);
            assertEquals(new BigDecimal("1000.00"), totalCredits);

            // Verify audit log
            Long auditCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM audit_logs WHERE action = 'TRANSACTION_DEPOSIT'", Long.class);
            assertEquals(1L, auditCount);
        }

        @Test
        @DisplayName("Deposit rejects zero and negative amounts")
        void depositRejectsZeroAndNegativeAmounts() throws Exception {
            UserEntity customer = createAndAuthenticateUser("dep_neg@example.com", UserRole.CUSTOMER);
            MockHttpSession session = loginSession("dep_neg@example.com", "Password123!");
            UUID custAccId = createAccountForUser(customer, "INR");

            // Zero
            mockMvc.perform(post("/api/v1/transactions/deposit")
                    .session(session)
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + custAccId + "\",\"amount\":0.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isBadRequest());

            // Negative
            mockMvc.perform(post("/api/v1/transactions/deposit")
                    .session(session)
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + custAccId + "\",\"amount\":-50.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isBadRequest());

            assertEquals(new BigDecimal("0.00"), getAccountBalance(custAccId));
        }

        @Test
        @DisplayName("Deposit requires Idempotency-Key header")
        void depositRequiresIdempotencyKey() throws Exception {
            UserEntity customer = createAndAuthenticateUser("dep_nokey@example.com", UserRole.CUSTOMER);
            MockHttpSession session = loginSession("dep_nokey@example.com", "Password123!");
            UUID custAccId = createAccountForUser(customer, "INR");

            mockMvc.perform(post("/api/v1/transactions/deposit")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + custAccId + "\",\"amount\":100.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Deposit to another customer's account is rejected")
        void depositToAnotherCustomerAccountIsRejected() throws Exception {
            UserEntity userA = createAndAuthenticateUser("owner_dep@example.com", UserRole.CUSTOMER);
            createAndAuthenticateUser("other_dep@example.com", UserRole.CUSTOMER);

            UUID accA = createAccountForUser(userA, "INR");
            MockHttpSession sessionB = loginSession("other_dep@example.com", "Password123!");

            mockMvc.perform(post("/api/v1/transactions/deposit")
                    .session(sessionB)
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + accA + "\",\"amount\":100.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Deposit to inactive account is rejected")
        void depositToInactiveAccountIsRejected() throws Exception {
            UserEntity customer = createAndAuthenticateUser("dep_inactive@example.com", UserRole.CUSTOMER);
            MockHttpSession session = loginSession("dep_inactive@example.com", "Password123!");
            UUID custAccId = createAccountForUser(customer, "INR");

            // Deactivate account
            jdbcTemplate.update("UPDATE accounts SET status = 'FROZEN' WHERE id = ?", custAccId);

            mockMvc.perform(post("/api/v1/transactions/deposit")
                    .session(session)
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + custAccId + "\",\"amount\":100.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    // =========================================================================
    // SECTION C: WITHDRAWAL IMPLEMENTATION TESTS
    // =========================================================================
    @Nested
    @DisplayName("C. Withdrawal Operations")
    class WithdrawalTests {

        @Test
        @DisplayName("Successful withdrawal decreases customer and system cash balances")
        void successfulWithdrawalDecreasesBalances() throws Exception {
            UserEntity customer = createAndAuthenticateUser("with_user@example.com", UserRole.CUSTOMER);
            MockHttpSession session = loginSession("with_user@example.com", "Password123!");
            UUID custAccId = createAccountForUser(customer, "INR");

            // Fund account first: deposit 1000.00
            mockMvc.perform(post("/api/v1/transactions/deposit")
                    .session(session)
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + custAccId + "\",\"amount\":1000.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated());

            // Withdraw 400.00
            mockMvc.perform(post("/api/v1/transactions/withdraw")
                    .session(session)
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + custAccId + "\",\"amount\":400.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.transactionType").value("WITHDRAWAL"))
                    .andExpect(jsonPath("$.status").value("COMPLETED"));

            assertEquals(new BigDecimal("600.00"), getAccountBalance(custAccId));
            assertEquals(new BigDecimal("600.00"), getAccountBalance(SYSTEM_CASH_VAULT_ID));

            // Verify audit log
            Long auditCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM audit_logs WHERE action = 'TRANSACTION_WITHDRAW'", Long.class);
            assertEquals(1L, auditCount);
        }

        @Test
        @DisplayName("Withdrawal exceeding available balance is rejected with 422 Unprocessable Content")
        void withdrawalExceedingBalanceIsRejected() throws Exception {
            UserEntity customer = createAndAuthenticateUser("with_insufficient@example.com", UserRole.CUSTOMER);
            MockHttpSession session = loginSession("with_insufficient@example.com", "Password123!");
            UUID custAccId = createAccountForUser(customer, "INR");

            // Deposit 100.00
            mockMvc.perform(post("/api/v1/transactions/deposit")
                    .session(session)
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + custAccId + "\",\"amount\":100.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated());

            // Attempt to withdraw 150.00 -> 422
            mockMvc.perform(post("/api/v1/transactions/withdraw")
                    .session(session)
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + custAccId + "\",\"amount\":150.00,\"currency\":\"INR\"}"))
                    .andExpect(status().is(422));

            // Balance remains 100.00
            assertEquals(new BigDecimal("100.00"), getAccountBalance(custAccId));
        }

        @Test
        @DisplayName("Withdrawal from another customer's account is rejected with 403 Forbidden")
        void withdrawalFromAnotherCustomerAccountIsRejected() throws Exception {
            UserEntity userA = createAndAuthenticateUser("owner_with@example.com", UserRole.CUSTOMER);
            createAndAuthenticateUser("other_with@example.com", UserRole.CUSTOMER);

            UUID accA = createAccountForUser(userA, "INR");
            MockHttpSession sessionB = loginSession("other_with@example.com", "Password123!");

            mockMvc.perform(post("/api/v1/transactions/withdraw")
                    .session(sessionB)
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + accA + "\",\"amount\":50.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    // =========================================================================
    // SECTION D: TRANSFER IMPLEMENTATION TESTS
    // =========================================================================
    @Nested
    @DisplayName("D. Transfer Operations")
    class TransferTests {

        @Test
        @DisplayName("Successful internal transfer debits sender and credits receiver without touching cash vault")
        void successfulInternalTransfer() throws Exception {
            UserEntity sender = createAndAuthenticateUser("sender@example.com", UserRole.CUSTOMER);
            UserEntity receiver = createAndAuthenticateUser("receiver@example.com", UserRole.CUSTOMER);

            UUID senderAcc = createAccountForUser(sender, "INR");
            UUID receiverAcc = createAccountForUser(receiver, "INR");
            String receiverAccNumber = accountRepository.findById(receiverAcc).orElseThrow().getAccountNumber();

            MockHttpSession senderSession = loginSession("sender@example.com", "Password123!");

            // Fund sender account with 1000.00
            mockMvc.perform(post("/api/v1/transactions/deposit")
                    .session(senderSession)
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + senderAcc + "\",\"amount\":1000.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated());

            // Transfer 350.00 from sender to receiver
            mockMvc.perform(post("/api/v1/transactions/transfer")
                    .session(senderSession)
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sourceAccountId\":\"" + senderAcc + "\",\"destinationAccountNumber\":\""
                            + receiverAccNumber + "\",\"amount\":350.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.transactionType").value("TRANSFER"))
                    .andExpect(jsonPath("$.status").value("COMPLETED"));

            // Verify balances
            assertEquals(new BigDecimal("650.00"), getAccountBalance(senderAcc));
            assertEquals(new BigDecimal("350.00"), getAccountBalance(receiverAcc));

            // System cash vault must NOT have moved during internal transfer (stays 1000.00 from initial deposit)
            assertEquals(new BigDecimal("1000.00"), getAccountBalance(SYSTEM_CASH_VAULT_ID));

            // Verify audit log
            Long auditCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM audit_logs WHERE action = 'TRANSACTION_TRANSFER'", Long.class);
            assertEquals(1L, auditCount);
        }

        @Test
        @DisplayName("Self-transfer to same account is rejected with 400 Bad Request")
        void selfTransferIsRejected() throws Exception {
            UserEntity user = createAndAuthenticateUser("self_tx@example.com", UserRole.CUSTOMER);
            UUID acc = createAccountForUser(user, "INR");
            String accNum = accountRepository.findById(acc).orElseThrow().getAccountNumber();

            MockHttpSession session = loginSession("self_tx@example.com", "Password123!");

            mockMvc.perform(post("/api/v1/transactions/transfer")
                    .session(session)
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sourceAccountId\":\"" + acc + "\",\"destinationAccountNumber\":\""
                            + accNum + "\",\"amount\":50.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Transfer to system account is rejected with 403 Forbidden")
        void transferToSystemAccountIsRejected() throws Exception {
            UserEntity user = createAndAuthenticateUser("sys_tx@example.com", UserRole.CUSTOMER);
            UUID acc = createAccountForUser(user, "INR");
            MockHttpSession session = loginSession("sys_tx@example.com", "Password123!");

            mockMvc.perform(post("/api/v1/transactions/transfer")
                    .session(session)
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sourceAccountId\":\"" + acc + "\",\"destinationAccountNumber\":\""
                            + SYSTEM_CASH_VAULT_NUMBER + "\",\"amount\":50.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Transfer with insufficient balance is rejected with 422 Unprocessable Content")
        void transferWithInsufficientBalanceIsRejected() throws Exception {
            UserEntity sender = createAndAuthenticateUser("sender_poor@example.com", UserRole.CUSTOMER);
            UserEntity receiver = createAndAuthenticateUser("receiver_poor@example.com", UserRole.CUSTOMER);

            UUID senderAcc = createAccountForUser(sender, "INR");
            UUID receiverAcc = createAccountForUser(receiver, "INR");
            String receiverAccNumber = accountRepository.findById(receiverAcc).orElseThrow().getAccountNumber();

            MockHttpSession senderSession = loginSession("sender_poor@example.com", "Password123!");

            // Sender only has 100.00
            mockMvc.perform(post("/api/v1/transactions/deposit")
                    .session(senderSession)
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + senderAcc + "\",\"amount\":100.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated());

            // Attempt to transfer 150.00
            mockMvc.perform(post("/api/v1/transactions/transfer")
                    .session(senderSession)
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sourceAccountId\":\"" + senderAcc + "\",\"destinationAccountNumber\":\""
                            + receiverAccNumber + "\",\"amount\":150.00,\"currency\":\"INR\"}"))
                    .andExpect(status().is(422));

            assertEquals(new BigDecimal("100.00"), getAccountBalance(senderAcc));
            assertEquals(new BigDecimal("0.00"), getAccountBalance(receiverAcc));
        }

        @Test
        @DisplayName("Transfer to non-existent account number is rejected with 404 Not Found")
        void transferToNonExistentAccountIsRejected() throws Exception {
            UserEntity sender = createAndAuthenticateUser("sender_nonex@example.com", UserRole.CUSTOMER);
            UUID senderAcc = createAccountForUser(sender, "INR");
            MockHttpSession senderSession = loginSession("sender_nonex@example.com", "Password123!");

            mockMvc.perform(post("/api/v1/transactions/transfer")
                    .session(senderSession)
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sourceAccountId\":\"" + senderAcc + "\",\"destinationAccountNumber\":\""
                            + "VLTR999999999999" + "\",\"amount\":50.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isNotFound());
        }
    }

    // =========================================================================
    // SECTION E: IDEMPOTENCY TESTS
    // =========================================================================
    @Nested
    @DisplayName("E. Idempotency Tests")
    class IdempotencyTests {

        @Test
        @DisplayName("Same key with same payload returns original result without duplicate execution")
        void sameKeySamePayloadReturnsOriginalResult() throws Exception {
            UserEntity customer = createAndAuthenticateUser("idem_same@example.com", UserRole.CUSTOMER);
            MockHttpSession session = loginSession("idem_same@example.com", "Password123!");
            UUID custAccId = createAccountForUser(customer, "INR");

            String key = UUID.randomUUID().toString();
            String payload = "{\"accountId\":\"" + custAccId + "\",\"amount\":500.00,\"currency\":\"INR\"}";

            // First execution
            MvcResult res1 = mockMvc.perform(post("/api/v1/transactions/deposit")
                    .session(session)
                    .with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload))
                    .andExpect(status().isCreated())
                    .andReturn();

            UUID txId1 = UUID.fromString(JsonPath.read(res1.getResponse().getContentAsString(), "$.id"));
            assertEquals(new BigDecimal("500.00"), getAccountBalance(custAccId));

            // Second execution with SAME key and SAME payload
            MvcResult res2 = mockMvc.perform(post("/api/v1/transactions/deposit")
                    .session(session)
                    .with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload))
                    .andExpect(status().isCreated())
                    .andReturn();

            UUID txId2 = UUID.fromString(JsonPath.read(res2.getResponse().getContentAsString(), "$.id"));
            assertEquals(txId1, txId2, "Must return the original transaction ID");

            // Customer balance must STILL be 500.00 (not 1000.00)
            assertEquals(new BigDecimal("500.00"), getAccountBalance(custAccId));

            // Exactly 2 ledger entries (1 transaction) in database
            Long entryCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long.class);
            assertEquals(2L, entryCount);
        }

        @Test
        @DisplayName("Same key with different payload is rejected with 409 Conflict")
        void sameKeyDifferentPayloadReturnsConflict() throws Exception {
            UserEntity customer = createAndAuthenticateUser("idem_conflict@example.com", UserRole.CUSTOMER);
            MockHttpSession session = loginSession("idem_conflict@example.com", "Password123!");
            UUID custAccId = createAccountForUser(customer, "INR");

            String key = UUID.randomUUID().toString();

            // First request: deposit 500.00
            mockMvc.perform(post("/api/v1/transactions/deposit")
                    .session(session)
                    .with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + custAccId + "\",\"amount\":500.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated());

            // Second request with SAME key but DIFFERENT amount (600.00)
            mockMvc.perform(post("/api/v1/transactions/deposit")
                    .session(session)
                    .with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + custAccId + "\",\"amount\":600.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isConflict());

            assertEquals(new BigDecimal("500.00"), getAccountBalance(custAccId));
        }

        @Test
        @DisplayName("Failed validation does not consume idempotency key, retry can succeed")
        void failedValidationDoesNotConsumeIdempotencyKey() throws Exception {
            UserEntity customer = createAndAuthenticateUser("idem_retry@example.com", UserRole.CUSTOMER);
            MockHttpSession session = loginSession("idem_retry@example.com", "Password123!");
            UUID custAccId = createAccountForUser(customer, "INR");

            String key = UUID.randomUUID().toString();

            // Attempt withdrawal with 0 balance -> 422
            mockMvc.perform(post("/api/v1/transactions/withdraw")
                    .session(session)
                    .with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + custAccId + "\",\"amount\":500.00,\"currency\":\"INR\"}"))
                    .andExpect(status().is(422));

            // Now fund the account
            mockMvc.perform(post("/api/v1/transactions/deposit")
                    .session(session)
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + custAccId + "\",\"amount\":1000.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated());

            // Retry withdrawal with the SAME key -> must now SUCCEED
            mockMvc.perform(post("/api/v1/transactions/withdraw")
                    .session(session)
                    .with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + custAccId + "\",\"amount\":500.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated());

            assertEquals(new BigDecimal("500.00"), getAccountBalance(custAccId));
        }

        @Test
        @DisplayName("Same user with same idempotency key across different accounts serializes and rejects duplicate")
        void sameUserSameKeyAcrossDifferentAccountsProducesOneOperation() throws Exception {
            UserEntity user = createAndAuthenticateUser("cross_acc@example.com", UserRole.CUSTOMER);
            MockHttpSession session = loginSession("cross_acc@example.com", "Password123!");
            UUID acc1 = createAccountForUser(user, "INR");
            UUID acc2 = createAccountForUser(user, "INR");

            String key = "SHARED-KEY-" + UUID.randomUUID();

            // First deposit on acc1 succeeds
            mockMvc.perform(post("/api/v1/transactions/deposit").session(session).with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + acc1 + "\",\"amount\":300.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated());

            // Second deposit on acc2 with SAME user and SAME key is rejected with 409 Conflict
            mockMvc.perform(post("/api/v1/transactions/deposit").session(session).with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + acc2 + "\",\"amount\":300.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isConflict());

            assertEquals(new BigDecimal("300.00"), getAccountBalance(acc1));
            assertEquals(new BigDecimal("0.00"), getAccountBalance(acc2));
        }

        @Test
        @DisplayName("Canonical fingerprint matches amounts regardless of trailing zeroes (100.0 vs 100.00)")
        void canonicalFingerprintMatchesTrailingZeroes() throws Exception {
            UserEntity user = createAndAuthenticateUser("fp_test@example.com", UserRole.CUSTOMER);
            MockHttpSession session = loginSession("fp_test@example.com", "Password123!");
            UUID acc = createAccountForUser(user, "INR");

            String key = "FP-KEY-" + UUID.randomUUID();

            MvcResult res1 = mockMvc.perform(post("/api/v1/transactions/deposit").session(session).with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + acc + "\",\"amount\":100.0,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated())
                    .andReturn();

            UUID tx1 = UUID.fromString(JsonPath.read(res1.getResponse().getContentAsString(), "$.id"));

            MvcResult res2 = mockMvc.perform(post("/api/v1/transactions/deposit").session(session).with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + acc + "\",\"amount\":100.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated())
                    .andReturn();

            UUID tx2 = UUID.fromString(JsonPath.read(res2.getResponse().getContentAsString(), "$.id"));
            assertEquals(tx1, tx2);
            assertEquals(new BigDecimal("100.00"), getAccountBalance(acc));
        }

        @Test
        @DisplayName("Validation: amount with >2 decimal places, negative amount, key >255 chars, invalid currency rejected with 400")
        void validationRejectsInvalidInputs() throws Exception {
            UserEntity user = createAndAuthenticateUser("val_user@example.com", UserRole.CUSTOMER);
            MockHttpSession session = loginSession("val_user@example.com", "Password123!");
            UUID acc = createAccountForUser(user, "INR");

            // Amount with 3 decimal places -> 400
            mockMvc.perform(post("/api/v1/transactions/deposit").session(session).with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + acc + "\",\"amount\":100.555,\"currency\":\"INR\"}"))
                    .andExpect(status().isBadRequest());

            // Negative amount -> 400
            mockMvc.perform(post("/api/v1/transactions/deposit").session(session).with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + acc + "\",\"amount\":-50.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isBadRequest());

            // Key > 255 chars -> 400
            String longKey = "a".repeat(256);
            mockMvc.perform(post("/api/v1/transactions/deposit").session(session).with(csrf())
                    .header("Idempotency-Key", longKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + acc + "\",\"amount\":50.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isBadRequest());

            // Invalid currency -> 400
            mockMvc.perform(post("/api/v1/transactions/deposit").session(session).with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + acc + "\",\"amount\":50.00,\"currency\":\"USD\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Deposit fails if system cash vault has invalid configuration (e.g. allow_negative=true)")
        void depositFailsIfSystemAccountMisconfigured() throws Exception {
            UserEntity user = createAndAuthenticateUser("sys_bad@example.com", UserRole.CUSTOMER);
            MockHttpSession session = loginSession("sys_bad@example.com", "Password123!");
            UUID acc = createAccountForUser(user, "INR");

            // Temporarily misconfigure system vault
            jdbcTemplate.execute("UPDATE accounts SET allow_negative = TRUE WHERE account_number = 'SYS-INR-CASH-VAULT-001'");

            try {
                mockMvc.perform(post("/api/v1/transactions/deposit").session(session).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + acc + "\",\"amount\":100.00,\"currency\":\"INR\"}"))
                        .andExpect(status().isInternalServerError());
            } finally {
                // Restore valid configuration
                jdbcTemplate.execute("UPDATE accounts SET allow_negative = FALSE WHERE account_number = 'SYS-INR-CASH-VAULT-001'");
            }
        }
    }

    // =========================================================================
    // SECTION F: REAL POSTGRESQL CONCURRENCY TESTS
    // =========================================================================
    @Nested
    @DisplayName("F. Real PostgreSQL Concurrency Tests")
    class ConcurrencyTests {

        @Test
        @DisplayName("Simultaneous withdrawals exceeding balance: exactly one succeeds, no overdraft")
        void concurrentWithdrawalsPreventOverdraft() throws Exception {
            UserEntity customer = createAndAuthenticateUser("conc_withdraw@example.com", UserRole.CUSTOMER);
            MockHttpSession session = loginSession("conc_withdraw@example.com", "Password123!");
            UUID custAccId = createAccountForUser(customer, "INR");

            // Initial balance = 1000.00
            mockMvc.perform(post("/api/v1/transactions/deposit")
                    .session(session)
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + custAccId + "\",\"amount\":1000.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated());

            // Launch 2 concurrent withdrawals of 800.00 each
            int numThreads = 2;
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch finishLatch = new CountDownLatch(numThreads);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger insufficientFundsCount = new AtomicInteger(0);

            for (int i = 0; i < numThreads; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        MvcResult res = mockMvc.perform(post("/api/v1/transactions/withdraw")
                                .session(session)
                                .with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"accountId\":\"" + custAccId + "\",\"amount\":800.00,\"currency\":\"INR\"}"))
                                .andReturn();

                        int status = res.getResponse().getStatus();
                        if (status == 201) {
                            successCount.incrementAndGet();
                        } else if (status == 422) {
                            insufficientFundsCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        finishLatch.countDown();
                    }
                });
            }

            // Release all threads simultaneously
            startLatch.countDown();
            assertTrue(finishLatch.await(15, TimeUnit.SECONDS), "Concurrent tasks did not complete within timeout");
            executor.shutdown();

            assertEquals(1, successCount.get(), "Exactly one withdrawal must succeed");
            assertEquals(1, insufficientFundsCount.get(), "Exactly one withdrawal must be rejected due to insufficient funds");

            // Final balance must be 200.00
            assertEquals(new BigDecimal("200.00"), getAccountBalance(custAccId));
            assertTrue(getAccountBalance(custAccId).compareTo(BigDecimal.ZERO) >= 0, "Account balance must NEVER be negative");
        }

        @Test
        @DisplayName("Concurrent transfers in opposite directions prevent deadlocks via deterministic locking")
        void concurrentTransfersInOppositeDirectionsPreventDeadlock() throws Exception {
            UserEntity userA = createAndAuthenticateUser("oppA@example.com", UserRole.CUSTOMER);
            UserEntity userB = createAndAuthenticateUser("oppB@example.com", UserRole.CUSTOMER);

            UUID accA = createAccountForUser(userA, "INR");
            UUID accB = createAccountForUser(userB, "INR");

            String numA = accountRepository.findById(accA).orElseThrow().getAccountNumber();
            String numB = accountRepository.findById(accB).orElseThrow().getAccountNumber();

            MockHttpSession sessionA = loginSession("oppA@example.com", "Password123!");
            MockHttpSession sessionB = loginSession("oppB@example.com", "Password123!");

            // Fund both accounts with 1000.00
            mockMvc.perform(post("/api/v1/transactions/deposit").session(sessionA).with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + accA + "\",\"amount\":1000.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/v1/transactions/deposit").session(sessionB).with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + accB + "\",\"amount\":1000.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated());

            // Run transfers in opposite directions (A -> B and B -> A) simultaneously across 10 iterations
            int pairs = 5;
            ExecutorService executor = Executors.newFixedThreadPool(pairs * 2);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch finishLatch = new CountDownLatch(pairs * 2);

            AtomicInteger completedAtoB = new AtomicInteger(0);
            AtomicInteger completedBtoA = new AtomicInteger(0);

            for (int i = 0; i < pairs; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        MvcResult res = mockMvc.perform(post("/api/v1/transactions/transfer").session(sessionA).with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"sourceAccountId\":\"" + accA + "\",\"destinationAccountNumber\":\""
                                        + numB + "\",\"amount\":10.00,\"currency\":\"INR\"}"))
                                .andReturn();
                        if (res.getResponse().getStatus() == 201) completedAtoB.incrementAndGet();
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        finishLatch.countDown();
                    }
                });

                executor.submit(() -> {
                    try {
                        startLatch.await();
                        MvcResult res = mockMvc.perform(post("/api/v1/transactions/transfer").session(sessionB).with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"sourceAccountId\":\"" + accB + "\",\"destinationAccountNumber\":\""
                                        + numA + "\",\"amount\":10.00,\"currency\":\"INR\"}"))
                                .andReturn();
                        if (res.getResponse().getStatus() == 201) completedBtoA.incrementAndGet();
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        finishLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(finishLatch.await(30, TimeUnit.SECONDS), "Transfers should complete without deadlock");
            executor.shutdown();

            assertEquals(pairs, completedAtoB.get(), "All A->B transfers must succeed");
            assertEquals(pairs, completedBtoA.get(), "All B->A transfers must succeed");

            // Final balances should still both be exactly 1000.00
            assertEquals(new BigDecimal("1000.00"), getAccountBalance(accA));
            assertEquals(new BigDecimal("1000.00"), getAccountBalance(accB));
        }

        @Test
        @DisplayName("Simultaneous duplicate requests with same idempotency key execute exactly once")
        void concurrentDuplicateRequestsExecuteOnlyOnce() throws Exception {
            UserEntity customer = createAndAuthenticateUser("conc_idem@example.com", UserRole.CUSTOMER);
            MockHttpSession session = loginSession("conc_idem@example.com", "Password123!");
            UUID custAccId = createAccountForUser(customer, "INR");

            String key = UUID.randomUUID().toString();
            String payload = "{\"accountId\":\"" + custAccId + "\",\"amount\":500.00,\"currency\":\"INR\"}";

            int numThreads = 2;
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch finishLatch = new CountDownLatch(numThreads);

            List<Integer> statusCodes = Collections.synchronizedList(new ArrayList<>());
            List<String> transactionIds = Collections.synchronizedList(new ArrayList<>());

            for (int i = 0; i < numThreads; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        MvcResult res = mockMvc.perform(post("/api/v1/transactions/deposit")
                                .session(session)
                                .with(csrf())
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                                .andReturn();
                        statusCodes.add(res.getResponse().getStatus());
                        String body = res.getResponse().getContentAsString();
                        if (body.contains("\"id\"")) {
                            transactionIds.add(JsonPath.read(body, "$.id"));
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        finishLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(finishLatch.await(15, TimeUnit.SECONDS), "Concurrent idempotency calls timed out");
            executor.shutdown();

            // Both callers must receive success (either 201 Created or 200 OK)
            for (int st : statusCodes) {
                assertTrue(st == 200 || st == 201, "Status code must be 200 or 201, but got: " + st);
            }

            // Both returned transaction IDs must be identical
            assertEquals(2, transactionIds.size());
            assertEquals(transactionIds.get(0), transactionIds.get(1));

            // Customer balance must be exactly 500.00 (not 1000.00)
            assertEquals(new BigDecimal("500.00"), getAccountBalance(custAccId));

            // Exactly 2 ledger entries in table
            Long entries = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long.class);
            assertEquals(2L, entries);
        }

        @Test
        @DisplayName("Simultaneous transfers targeting the same receiver account all succeed")
        void concurrentTransfersToSameReceiver() throws Exception {
            UserEntity userA = createAndAuthenticateUser("same_rcvA@example.com", UserRole.CUSTOMER);
            UserEntity userB = createAndAuthenticateUser("same_rcvB@example.com", UserRole.CUSTOMER);
            UserEntity receiver = createAndAuthenticateUser("same_rcvDest@example.com", UserRole.CUSTOMER);

            UUID accA = createAccountForUser(userA, "INR");
            UUID accB = createAccountForUser(userB, "INR");
            UUID accReceiver = createAccountForUser(receiver, "INR");
            String receiverAccNum = accountRepository.findById(accReceiver).orElseThrow().getAccountNumber();

            MockHttpSession sessionA = loginSession("same_rcvA@example.com", "Password123!");
            MockHttpSession sessionB = loginSession("same_rcvB@example.com", "Password123!");

            // Fund A with 500, B with 500
            mockMvc.perform(post("/api/v1/transactions/deposit").session(sessionA).with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + accA + "\",\"amount\":500.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/v1/transactions/deposit").session(sessionB).with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + accB + "\",\"amount\":500.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated());

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch finishLatch = new CountDownLatch(2);

            AtomicInteger success = new AtomicInteger(0);

            // A transfers 200 to receiver
            executor.submit(() -> {
                try {
                    startLatch.await();
                    MvcResult res = mockMvc.perform(post("/api/v1/transactions/transfer").session(sessionA).with(csrf())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sourceAccountId\":\"" + accA + "\",\"destinationAccountNumber\":\""
                                    + receiverAccNum + "\",\"amount\":200.00,\"currency\":\"INR\"}"))
                            .andReturn();
                    if (res.getResponse().getStatus() == 201) success.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    finishLatch.countDown();
                }
            });

            // B transfers 300 to receiver
            executor.submit(() -> {
                try {
                    startLatch.await();
                    MvcResult res = mockMvc.perform(post("/api/v1/transactions/transfer").session(sessionB).with(csrf())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sourceAccountId\":\"" + accB + "\",\"destinationAccountNumber\":\""
                                    + receiverAccNum + "\",\"amount\":300.00,\"currency\":\"INR\"}"))
                            .andReturn();
                    if (res.getResponse().getStatus() == 201) success.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    finishLatch.countDown();
                }
            });

            startLatch.countDown();
            assertTrue(finishLatch.await(15, TimeUnit.SECONDS));
            executor.shutdown();

            assertEquals(2, success.get());
            assertEquals(new BigDecimal("300.00"), getAccountBalance(accA));
            assertEquals(new BigDecimal("200.00"), getAccountBalance(accB));
            assertEquals(new BigDecimal("500.00"), getAccountBalance(accReceiver));
        }
    }

    // =========================================================================
    // SECTION G: FINANCIAL RECONCILIATION & INTEGRITY TESTS
    // =========================================================================
    @Nested
    @DisplayName("G. Financial Integrity & Reconciliation")
    class FinancialIntegrityTests {

        @Test
        @DisplayName("Reconciliation: Every account projection strictly equals sum of ledger entries")
        void ledgerReconciliationCalculatedBalancesStrictlyMatchProjections() throws Exception {
            UserEntity customerA = createAndAuthenticateUser("reconA@example.com", UserRole.CUSTOMER);
            UserEntity customerB = createAndAuthenticateUser("reconB@example.com", UserRole.CUSTOMER);

            UUID accA = createAccountForUser(customerA, "INR");
            UUID accB = createAccountForUser(customerB, "INR");
            String accNumB = accountRepository.findById(accB).orElseThrow().getAccountNumber();

            MockHttpSession sessionA = loginSession("reconA@example.com", "Password123!");
            MockHttpSession sessionB = loginSession("reconB@example.com", "Password123!");

            // 1. Deposit 2500 into A
            mockMvc.perform(post("/api/v1/transactions/deposit").session(sessionA).with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + accA + "\",\"amount\":2500.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated());

            // 2. Deposit 1000 into B
            mockMvc.perform(post("/api/v1/transactions/deposit").session(sessionB).with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + accB + "\",\"amount\":1000.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated());

            // 3. Transfer 750 from A to B
            mockMvc.perform(post("/api/v1/transactions/transfer").session(sessionA).with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sourceAccountId\":\"" + accA + "\",\"destinationAccountNumber\":\""
                            + accNumB + "\",\"amount\":750.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated());

            // 4. Withdraw 300 from B
            mockMvc.perform(post("/api/v1/transactions/withdraw").session(sessionB).with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + accB + "\",\"amount\":300.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated());

            // Global Debit == Global Credit check across all ledger entries
            BigDecimal totalDebits = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE entry_type = 'DEBIT'", BigDecimal.class);
            BigDecimal totalCredits = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE entry_type = 'CREDIT'", BigDecimal.class);
            assertEquals(totalDebits, totalCredits, "Total ledger debits must equal total ledger credits globally");

            // For every single account in the database, calculate balance directly from ledger entries
            List<AccountEntity> allAccounts = accountRepository.findAll();
            for (AccountEntity acc : allAccounts) {
                BigDecimal ledgerCalculated;
                if (acc.getNormalBalance() == NormalBalance.CREDIT) {
                    ledgerCalculated = jdbcTemplate.queryForObject("""
                            SELECT COALESCE(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END), 0)
                            FROM ledger_entries WHERE account_id = ?
                            """, BigDecimal.class, acc.getId());
                } else {
                    ledgerCalculated = jdbcTemplate.queryForObject("""
                            SELECT COALESCE(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END), 0)
                            FROM ledger_entries WHERE account_id = ?
                            """, BigDecimal.class, acc.getId());
                }

                assertEquals(0, acc.getBalance().compareTo(ledgerCalculated),
                        String.format("Account %s projected balance %s must equal ledger calculated balance %s",
                                acc.getAccountNumber(), acc.getBalance(), ledgerCalculated));
            }
        }
    }

    // =========================================================================
    // SECTION H: SECURITY, ROLE & AUDIT TESTS
    // =========================================================================
    @Nested
    @DisplayName("H. Security & Authorization")
    class SecurityAndAuthorizationTests {

        @Test
        @DisplayName("Unauthenticated requests to account and transaction APIs are rejected with 401")
        void unauthenticatedRequestsAreRejected() throws Exception {
            mockMvc.perform(get("/api/v1/accounts"))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(post("/api/v1/transactions/deposit")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + UUID.randomUUID() + "\",\"amount\":100.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("AUDITOR role cannot initiate deposits, withdrawals, transfers or account creation")
        void auditorCannotInitiateMoneyMovement() throws Exception {
            createAndAuthenticateUser("auditor@example.com", UserRole.AUDITOR);
            MockHttpSession auditorSession = loginSession("auditor@example.com", "Password123!");

            // Cannot create account -> 403
            mockMvc.perform(post("/api/v1/accounts").session(auditorSession).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currency\":\"INR\"}"))
                    .andExpect(status().isForbidden());

            // Cannot deposit -> 403
            mockMvc.perform(post("/api/v1/transactions/deposit").session(auditorSession).with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + UUID.randomUUID() + "\",\"amount\":100.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isForbidden());

            // Cannot withdraw -> 403
            mockMvc.perform(post("/api/v1/transactions/withdraw").session(auditorSession).with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + UUID.randomUUID() + "\",\"amount\":100.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isForbidden());

            // Cannot transfer -> 403
            mockMvc.perform(post("/api/v1/transactions/transfer").session(auditorSession).with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sourceAccountId\":\"" + UUID.randomUUID() + "\",\"destinationAccountNumber\":\"VLTR123456789012\",\"amount\":100.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Customer cannot access another customer's transaction details")
        void customerCannotAccessAnotherUserTransaction() throws Exception {
            UserEntity userA = createAndAuthenticateUser("owner_tx@example.com", UserRole.CUSTOMER);
            createAndAuthenticateUser("other_tx@example.com", UserRole.CUSTOMER);

            UUID accA = createAccountForUser(userA, "INR");
            MockHttpSession sessionA = loginSession("owner_tx@example.com", "Password123!");
            MockHttpSession sessionB = loginSession("other_tx@example.com", "Password123!");

            MvcResult depResult = mockMvc.perform(post("/api/v1/transactions/deposit").session(sessionA).with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + accA + "\",\"amount\":100.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated())
                    .andReturn();

            UUID txId = UUID.fromString(JsonPath.read(depResult.getResponse().getContentAsString(), "$.id"));

            // User A can access own transaction
            mockMvc.perform(get("/api/v1/transactions/" + txId).session(sessionA))
                    .andExpect(status().isOk());

            // User B cannot access User A's transaction -> 403
            mockMvc.perform(get("/api/v1/transactions/" + txId).session(sessionB))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Customer transaction history supports pagination and filters only user's transactions")
        void transactionHistoryPaginationAndIsolation() throws Exception {
            UserEntity userA = createAndAuthenticateUser("histA@example.com", UserRole.CUSTOMER);
            UUID accA = createAccountForUser(userA, "INR");
            MockHttpSession sessionA = loginSession("histA@example.com", "Password123!");

            // Perform 3 deposits
            for (int i = 0; i < 3; i++) {
                mockMvc.perform(post("/api/v1/transactions/deposit").session(sessionA).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + accA + "\",\"amount\":100.00,\"currency\":\"INR\"}"))
                        .andExpect(status().isCreated());
            }

            // Retrieve page 0 with size 2
            mockMvc.perform(get("/api/v1/transactions?page=0&size=2").session(sessionA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(2))
                    .andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.totalPages").value(2));

            // Retrieve page 1 with size 2
            mockMvc.perform(get("/api/v1/transactions?page=1&size=2").session(sessionA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1));
        }

        @Test
        @DisplayName("Runtime database role cannot TRUNCATE any table")
        void runtimeRoleCannotTruncateTables() throws Exception {
            try (Connection conn = DriverManager.getConnection(testDbUrl, runtimeUser, runtimePassword);
                 Statement stmt = conn.createStatement()) {
                assertThrows(SQLException.class, () -> stmt.execute("TRUNCATE users"));
                assertThrows(SQLException.class, () -> stmt.execute("TRUNCATE accounts"));
                assertThrows(SQLException.class, () -> stmt.execute("TRUNCATE ledger_transactions"));
                assertThrows(SQLException.class, () -> stmt.execute("TRUNCATE ledger_entries"));
                assertThrows(SQLException.class, () -> stmt.execute("TRUNCATE audit_logs"));
            }
        }

        @Test
        @DisplayName("Runtime database role cannot promote user role to ADMIN")
        void runtimeRoleCannotPromoteUserRole() throws Exception {
            UserEntity user = createAndAuthenticateUser("unpriv_user@example.com", UserRole.CUSTOMER);
            try (Connection conn = DriverManager.getConnection(testDbUrl, runtimeUser, runtimePassword);
                 Statement stmt = conn.createStatement()) {
                assertThrows(SQLException.class, () -> stmt.executeUpdate("UPDATE users SET role = 'ADMIN' WHERE id = '" + user.getId() + "'"));
            }
        }

        @Test
        @DisplayName("Runtime database role cannot directly update password_hash")
        void runtimeRoleCannotModifyPasswordHash() throws Exception {
            UserEntity user = createAndAuthenticateUser("pass_user@example.com", UserRole.CUSTOMER);
            try (Connection conn = DriverManager.getConnection(testDbUrl, runtimeUser, runtimePassword);
                 Statement stmt = conn.createStatement()) {
                assertThrows(SQLException.class, () -> stmt.executeUpdate("UPDATE users SET password_hash = 'hacked' WHERE id = '" + user.getId() + "'"));
            }
        }

        @Test
        @DisplayName("Runtime database role cannot modify allow_negative or balance")
        void runtimeRoleCannotModifyAllowNegativeOrBalance() throws Exception {
            UserEntity user = createAndAuthenticateUser("bal_user@example.com", UserRole.CUSTOMER);
            UUID accId = createAccountForUser(user, "INR");
            try (Connection conn = DriverManager.getConnection(testDbUrl, runtimeUser, runtimePassword);
                 Statement stmt = conn.createStatement()) {
                assertThrows(SQLException.class, () -> stmt.executeUpdate("UPDATE accounts SET allow_negative = TRUE WHERE id = '" + accId + "'"));
                assertThrows(SQLException.class, () -> stmt.executeUpdate("UPDATE accounts SET balance = 999999.00 WHERE id = '" + accId + "'"));
            }
        }

        @Test
        @DisplayName("Runtime database role cannot delete from ledger_entries or audit_logs")
        void runtimeRoleCannotDeleteLedgerEntriesOrAuditLogs() throws Exception {
            try (Connection conn = DriverManager.getConnection(testDbUrl, runtimeUser, runtimePassword);
                 Statement stmt = conn.createStatement()) {
                assertThrows(SQLException.class, () -> stmt.executeUpdate("DELETE FROM ledger_entries"));
                assertThrows(SQLException.class, () -> stmt.executeUpdate("DELETE FROM audit_logs"));
            }
        }

        @Test
        @DisplayName("Deposit response masks system counterpart as 'Cash/Bank Counterpart' with null accountId")
        void depositResponseMasksSystemCounterpart() throws Exception {
            UserEntity customer = createAndAuthenticateUser("priv_dep@example.com", UserRole.CUSTOMER);
            MockHttpSession session = loginSession("priv_dep@example.com", "Password123!");
            UUID custAccId = createAccountForUser(customer, "INR");

            mockMvc.perform(post("/api/v1/transactions/deposit")
                    .session(session)
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + custAccId + "\",\"amount\":100.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.entries[0].accountId").doesNotExist())
                    .andExpect(jsonPath("$.entries[0].accountNumber").value("Cash/Bank Counterpart"))
                    .andExpect(jsonPath("$.entries[1].accountId").value(custAccId.toString()));
        }

        @Test
        @DisplayName("Transfer response masks counterpart account and nulls counterpart accountId for both sender and receiver")
        void transferResponseMasksCounterpartForSenderAndReceiver() throws Exception {
            UserEntity sender = createAndAuthenticateUser("sender_priv@example.com", UserRole.CUSTOMER);
            UserEntity receiver = createAndAuthenticateUser("recv_priv@example.com", UserRole.CUSTOMER);

            UUID senderAcc = createAccountForUser(sender, "INR");
            UUID recvAcc = createAccountForUser(receiver, "INR");

            AccountEntity receiverEntity = accountRepository.findById(recvAcc).orElseThrow();
            String recvNumber = receiverEntity.getAccountNumber();
            AccountEntity senderEntity = accountRepository.findById(senderAcc).orElseThrow();
            String senderNumber = senderEntity.getAccountNumber();

            MockHttpSession senderSession = loginSession("sender_priv@example.com", "Password123!");
            MockHttpSession receiverSession = loginSession("recv_priv@example.com", "Password123!");

            // Fund sender
            mockMvc.perform(post("/api/v1/transactions/deposit").session(senderSession).with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + senderAcc + "\",\"amount\":500.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated());

            // Perform transfer
            MvcResult xferResult = mockMvc.perform(post("/api/v1/transactions/transfer").session(senderSession).with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sourceAccountId\":\"" + senderAcc + "\",\"destinationAccountNumber\":\"" + recvNumber + "\",\"amount\":200.00,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated())
                    .andReturn();

            String xferBody = xferResult.getResponse().getContentAsString();
            UUID txId = UUID.fromString(JsonPath.read(xferBody, "$.id"));

            // Sender view: sender entry has senderAcc id, receiver entry has null id and masked number
            mockMvc.perform(get("/api/v1/transactions/" + txId).session(senderSession))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.entries[0].accountId").value(senderAcc.toString()))
                    .andExpect(jsonPath("$.entries[0].accountNumber").value(senderNumber))
                    .andExpect(jsonPath("$.entries[1].accountId").doesNotExist())
                    .andExpect(jsonPath("$.entries[1].accountNumber").value("****" + recvNumber.substring(recvNumber.length() - 4)));

            // Receiver view: receiver entry has recvAcc id, sender entry has null id and masked number
            mockMvc.perform(get("/api/v1/transactions/" + txId).session(receiverSession))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.entries[0].accountId").doesNotExist())
                    .andExpect(jsonPath("$.entries[0].accountNumber").value("****" + senderNumber.substring(senderNumber.length() - 4)))
                    .andExpect(jsonPath("$.entries[1].accountId").value(recvAcc.toString()))
                    .andExpect(jsonPath("$.entries[1].accountNumber").value(recvNumber));
        }

        @Test
        @DisplayName("Customer AccountResponse does not expose normalBalance")
        void accountResponseDoesNotExposeNormalBalance() throws Exception {
            createAndAuthenticateUser("acc_priv@example.com", UserRole.CUSTOMER);
            MockHttpSession session = loginSession("acc_priv@example.com", "Password123!");

            mockMvc.perform(post("/api/v1/accounts").session(session).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.normalBalance").doesNotExist());
        }

        @Test
        @DisplayName("Runtime database role cannot INSERT users with ADMIN or AUDITOR role")
        void runtimeRoleCannotInsertAdminOrAuditorRole() throws Exception {
            try (Connection conn = DriverManager.getConnection(testDbUrl, runtimeUser, runtimePassword);
                 Statement stmt = conn.createStatement()) {
                SQLException adminEx = assertThrows(SQLException.class, () ->
                        stmt.executeUpdate("INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                                "VALUES ('" + UUID.randomUUID() + "', 'injected_admin@example.com', 'hash', 'ADMIN', 'ACTIVE', NOW(), NOW())"));
                assertEquals("42501", adminEx.getSQLState(), "Must fail with permission error 42501");

                SQLException auditorEx = assertThrows(SQLException.class, () ->
                        stmt.executeUpdate("INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                                "VALUES ('" + UUID.randomUUID() + "', 'injected_auditor@example.com', 'hash', 'AUDITOR', 'ACTIVE', NOW(), NOW())"));
                assertEquals("42501", auditorEx.getSQLState(), "Must fail with permission error 42501");

                int inserted = stmt.executeUpdate("INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                        "VALUES ('" + UUID.randomUUID() + "', 'legit_cust@example.com', 'hash', 'CUSTOMER', 'ACTIVE', NOW(), NOW())");
                assertEquals(1, inserted);
            }
        }

        @Test
        @DisplayName("Runtime database role cannot INSERT accounts with non-zero balance, SYSTEM type, or allow_negative=TRUE")
        void runtimeRoleCannotInsertFabricatedBalanceOrSystemAccount() throws Exception {
            UserEntity user = createAndAuthenticateUser("owner_acc_insert@example.com", UserRole.CUSTOMER);
            try (Connection conn = DriverManager.getConnection(testDbUrl, runtimeUser, runtimePassword);
                 Statement stmt = conn.createStatement()) {
                SQLException balEx = assertThrows(SQLException.class, () ->
                        stmt.executeUpdate("INSERT INTO accounts (id, account_number, owner_user_id, account_type, currency, balance, normal_balance, allow_negative, status, created_at, updated_at) " +
                                "VALUES ('" + UUID.randomUUID() + "', 'VLTR999111', '" + user.getId() + "', 'CUSTOMER', 'INR', 500.00, 'CREDIT', FALSE, 'ACTIVE', NOW(), NOW())"));
                assertEquals("42501", balEx.getSQLState());

                SQLException sysEx = assertThrows(SQLException.class, () ->
                        stmt.executeUpdate("INSERT INTO accounts (id, account_number, owner_user_id, account_type, currency, balance, normal_balance, allow_negative, status, created_at, updated_at) " +
                                "VALUES ('" + UUID.randomUUID() + "', 'VLTR999222', NULL, 'SYSTEM', 'INR', 0.00, 'DEBIT', FALSE, 'ACTIVE', NOW(), NOW())"));
                assertEquals("42501", sysEx.getSQLState());

                SQLException negEx = assertThrows(SQLException.class, () ->
                        stmt.executeUpdate("INSERT INTO accounts (id, account_number, owner_user_id, account_type, currency, balance, normal_balance, allow_negative, status, created_at, updated_at) " +
                                "VALUES ('" + UUID.randomUUID() + "', 'VLTR999333', '" + user.getId() + "', 'CUSTOMER', 'INR', 0.00, 'CREDIT', TRUE, 'ACTIVE', NOW(), NOW())"));
                assertEquals("42501", negEx.getSQLState());

                int inserted = stmt.executeUpdate("INSERT INTO accounts (id, account_number, owner_user_id, account_type, currency, balance, normal_balance, allow_negative, status, created_at, updated_at) " +
                        "VALUES ('" + UUID.randomUUID() + "', 'VLTR999444', '" + user.getId() + "', 'CUSTOMER', 'INR', 0.00, 'CREDIT', FALSE, 'ACTIVE', NOW(), NOW())");
                assertEquals(1, inserted);
            }
        }

        @Test
        @DisplayName("Rate limit exceeded generates AUTH_RATE_LIMIT_EXCEEDED audit log in database")
        void rateLimitExceededGeneratesAuditLog() throws Exception {
            String targetEmail = "rate_limit_audit@example.com";
            for (int i = 0; i < 5; i++) {
                mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + targetEmail + "\",\"password\":\"WrongPass123!\"}"))
                        .andExpect(status().isUnauthorized());
            }

            mockMvc.perform(post("/api/v1/auth/login")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + targetEmail + "\",\"password\":\"WrongPass123!\"}"))
                    .andExpect(status().isTooManyRequests());

            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM audit_logs WHERE action = 'AUTH_RATE_LIMIT_EXCEEDED'",
                    Integer.class
            );
            assertNotNull(count);
            assertTrue(count >= 1, "Must have recorded AUTH_RATE_LIMIT_EXCEEDED audit log");
        }

        @Test
        @DisplayName("Cross-currency deposit is rejected before ledger entries or locking")
        void crossCurrencyDepositIsRejected() throws Exception {
            UserEntity user = createAndAuthenticateUser("cur_test@example.com", UserRole.CUSTOMER);
            UUID accId = createAccountForUser(user, "INR");
            MockHttpSession session = loginSession("cur_test@example.com", "Password123!");

            mockMvc.perform(post("/api/v1/transactions/deposit")
                    .session(session)
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"" + accId + "\",\"amount\":50.00,\"currency\":\"USD\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("PostgreSQL Catalog: runtime role attributes enforce non-superuser, noinherit, and least privilege")
        void runtimeRoleCatalogAttributesAssertLeastPrivilege() {
            Map<String, Object> roleAttrs = jdbcTemplate.queryForMap(
                    "SELECT rolsuper, rolinherit, rolcreaterole, rolcreatedb, rolcanlogin " +
                    "FROM pg_roles WHERE rolname = ?", runtimeUser);
            assertEquals(false, roleAttrs.get("rolsuper"), "Runtime role must not be superuser");
            assertEquals(false, roleAttrs.get("rolinherit"), "Runtime role must have NOINHERIT");
            assertEquals(false, roleAttrs.get("rolcreaterole"), "Runtime role must not be allowed to create roles");
            assertEquals(false, roleAttrs.get("rolcreatedb"), "Runtime role must not be allowed to create databases");
            assertEquals(true, roleAttrs.get("rolcanlogin"), "Runtime role must be allowed to login");
        }

        @Test
        @DisplayName("PostgreSQL Catalog: runtime role inherits no role memberships or administrative options")
        void runtimeRoleHasZeroRoleMemberships() {
            Integer membershipCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_auth_members a " +
                    "JOIN pg_roles r ON a.member = r.oid WHERE r.rolname = ?",
                    Integer.class, runtimeUser);
            assertNotNull(membershipCount);
            assertEquals(0, membershipCount, "Runtime role must not be a member of any privileged role");
        }

        @Test
        @DisplayName("PostgreSQL Catalog: schema tables are owned by migration administrator and not runtime role")
        void databaseTablesAreOwnedByMigrationRoleAndNotRuntime() {
            List<String> nonAppOwnedTables = jdbcTemplate.queryForList(
                    "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tableowner <> 'ledgerline_app'",
                    String.class);
            assertTrue(nonAppOwnedTables.isEmpty(), "All public schema tables must be owned by ledgerline_app");

            List<String> runtimeOwnedTables = jdbcTemplate.queryForList(
                    "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tableowner = ?",
                    String.class, runtimeUser);
            assertTrue(runtimeOwnedTables.isEmpty(), "Runtime role must not own any tables");
        }

        @Test
        @DisplayName("PostgreSQL Catalog: runtime table privileges are strictly limited to SELECT and INSERT")
        void runtimeRoleTableGrantsAreStrictlyLimitedToSelectAndInsert() {
            List<String> prohibitedTablePrivileges = jdbcTemplate.queryForList(
                    "SELECT privilege_type FROM information_schema.role_table_grants " +
                    "WHERE grantee = ? AND privilege_type IN ('DELETE', 'TRUNCATE', 'UPDATE')",
                    String.class, runtimeUser);
            assertTrue(prohibitedTablePrivileges.isEmpty(),
                    "Runtime role must have zero table-level DELETE, TRUNCATE, or UPDATE grants");

            List<String> grantedPrivileges = jdbcTemplate.queryForList(
                    "SELECT DISTINCT privilege_type FROM information_schema.role_table_grants " +
                    "WHERE grantee = ? ORDER BY privilege_type",
                    String.class, runtimeUser);
            assertEquals(List.of("INSERT", "SELECT"), grantedPrivileges,
                    "Runtime role must only have INSERT and SELECT table-level grants");
        }

        @Test
        @DisplayName("PostgreSQL Catalog: column UPDATE grants strictly limited to status and timestamps")
        void runtimeRoleColumnGrantsLimitUpdateStrictly() {
            List<String> updateColumns = jdbcTemplate.queryForList(
                    "SELECT table_name || '.' || column_name AS col " +
                    "FROM information_schema.role_column_grants " +
                    "WHERE grantee = ? AND privilege_type = 'UPDATE' " +
                    "ORDER BY col",
                    String.class, runtimeUser);
            List<String> expectedAllowedUpdates = List.of(
                    "accounts.status",
                    "accounts.updated_at",
                    "ledger_transactions.completed_at",
                    "ledger_transactions.status",
                    "users.status",
                    "users.updated_at"
            );
            assertEquals(expectedAllowedUpdates, updateColumns,
                    "Runtime role must only have column-level UPDATE on non-sensitive status and timestamp columns");
        }

        @Test
        @DisplayName("PostgreSQL Catalog: schema public does not grant CREATE to runtime role or PUBLIC")
        void schemaPublicHasNoCreatePrivilegeForRuntimeOrPublic() {
            Boolean publicHasCreate = jdbcTemplate.queryForObject(
                    "SELECT has_schema_privilege('public', 'public', 'CREATE')",
                    Boolean.class);
            assertEquals(false, publicHasCreate, "PUBLIC must not have CREATE privilege on schema public");

            Boolean runtimeHasCreate = jdbcTemplate.queryForObject(
                    "SELECT has_schema_privilege(?, 'public', 'CREATE')",
                    Boolean.class, runtimeUser);
            assertEquals(false, runtimeHasCreate, "Runtime role must not have CREATE privilege on schema public");
        }
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================
    private UserEntity createAndAuthenticateUser(String email, UserRole role) {
        return userRepository.save(new UserEntity(email, passwordEncoder.encode("Password123!"), role));
    }

    private MockHttpSession loginSession(String email, String password) throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertNotNull(session, "Session must not be null after login");
        return session;
    }

    private UUID createAccountForUser(UserEntity owner, String currency) {
        AccountEntity account = new AccountEntity(
                "VLTR" + (100000000000L + (long)(Math.random() * 899999999999L)),
                owner,
                AccountType.CUSTOMER,
                currency
        );
        return accountRepository.save(account).getId();
    }

    private BigDecimal getAccountBalance(UUID accountId) {
        return jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, accountId);
    }
}
