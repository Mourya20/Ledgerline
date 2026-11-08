package com.ledgerline.ledgerline;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.ledgerline.ledgerline.domain.UserEntity;
import com.ledgerline.ledgerline.domain.UserStatus;
import com.ledgerline.ledgerline.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
class Phase1IntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void cleanTestData() {
        jdbcTemplate.update("DELETE FROM ledger_entries WHERE transaction_id IN "
                + "(SELECT id FROM ledger_transactions WHERE transaction_type = 'TEST')");
        jdbcTemplate.update("DELETE FROM ledger_transactions WHERE transaction_type = 'TEST'");
        jdbcTemplate.update("DELETE FROM accounts WHERE account_number LIKE 'SYS-%'");
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'phase1-%@example.com'");
    }

    @Test
    void registrationAssignsCustomerAndHashesPassword() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(post("/api/v1/auth/register")
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"CorrectHorseBattery12\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CUSTOMER"));

            String auditorAttemptEmail = uniqueEmail();
            mockMvc.perform(post("/api/v1/auth/register")
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"" + auditorAttemptEmail
                    + "\",\"password\":\"CorrectHorseBattery19\",\"role\":\"AUDITOR\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CUSTOMER"));

        UserEntity user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        org.junit.jupiter.api.Assertions.assertNotEquals("CorrectHorseBattery12", user.getPasswordHash());
        org.junit.jupiter.api.Assertions.assertTrue(passwordEncoder.matches("CorrectHorseBattery12", user.getPasswordHash()));
    }

    @Test
    void duplicateEmailIsCaseInsensitive() throws Exception {
        String email = uniqueEmail();
        register(email);

        mockMvc.perform(post("/api/v1/auth/register")
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"" + email.toUpperCase() + "\",\"password\":\"CorrectHorseBattery13\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void csrfIsRequiredForRegistration() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content("{\"email\":\"" + uniqueEmail() + "\",\"password\":\"CorrectHorseBattery14\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginSucceedsAndInvalidCredentialsFail() throws Exception {
        String email = uniqueEmail();
        register(email);

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"CorrectHorseBattery16\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andReturn();

        mockMvc.perform(get("/api/v1/auth/me").session((MockHttpSession) login.getRequest().getSession(false)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/login")
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointRejectsUnauthenticatedRequestAndCustomerCannotUseAdminRoute() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());

        String email = uniqueEmail();
        register(email);
        MvcResult login = login(email, "CorrectHorseBattery16");

        mockMvc.perform(get("/api/v1/admin/test").session((MockHttpSession) login.getRequest().getSession(false)))
                .andExpect(status().isForbidden());
    }

    @Test
    void disabledUserSessionIsInvalidated() throws Exception {
        String email = uniqueEmail();
        register(email);
        MvcResult login = login(email, "CorrectHorseBattery16");
        UserEntity user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.setStatus(UserStatus.DISABLED);
        userRepository.saveAndFlush(user);

        mockMvc.perform(get("/api/v1/auth/me").session((MockHttpSession) login.getRequest().getSession(false)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void auditLogsRejectUpdateAndDelete() {
        assertThrows(RuntimeException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            UUID auditId = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO audit_logs (id, action, details) VALUES (?, ?, '{}'::jsonb)", auditId,
                "TEST");
            jdbcTemplate.update("UPDATE audit_logs SET action = ? WHERE id = ?", "CHANGED", auditId);
        }));
        assertThrows(RuntimeException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            UUID auditId = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO audit_logs (id, action, details) VALUES (?, ?, '{}'::jsonb)", auditId,
                "TEST");
            jdbcTemplate.update("DELETE FROM audit_logs WHERE id = ?", auditId);
        }));
    }

    @Test
    void completedTransactionRequiresBalancedEntries() {
        assertThrows(RuntimeException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            UUID transactionId = insertPendingTransaction("USD");
            UUID debitAccount = insertSystemAccount("USD");
            UUID creditAccount = insertSystemAccount("USD");
            insertEntry(transactionId, debitAccount, "DEBIT", "10.00", "USD");
            insertEntry(transactionId, creditAccount, "CREDIT", "9.00", "USD");
            completeTransaction(transactionId);
        }));
    }

    @Test
    void completedTransactionRequiresAtLeastOneEntry() {
        assertThrows(RuntimeException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            UUID transactionId = insertPendingTransaction("USD");
            completeTransaction(transactionId);
        }));
    }

    @Test
    void ledgerEntryCurrencyMustMatchTransactionAndAccount() {
        assertThrows(RuntimeException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            UUID transactionId = insertPendingTransaction("EUR");
            UUID accountId = insertSystemAccount("USD");
            insertEntry(transactionId, accountId, "DEBIT", "10.00", "USD");
        }));
    }

    @Test
    void completedTransactionAndEntriesAreImmutable() {
        assertThrows(RuntimeException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            UUID transactionId = insertPendingTransaction("USD");
            UUID debitAccount = insertSystemAccount("USD");
            UUID creditAccount = insertSystemAccount("USD");
            insertEntry(transactionId, debitAccount, "DEBIT", "10.00", "USD");
            insertEntry(transactionId, creditAccount, "CREDIT", "10.00", "USD");
            completeTransaction(transactionId);
            jdbcTemplate.update("UPDATE ledger_transactions SET status = 'REVERSED' WHERE id = ?", transactionId);
        }));

        assertThrows(RuntimeException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            UUID transactionId = insertPendingTransaction("USD");
            UUID accountId = insertSystemAccount("USD");
            insertEntry(transactionId, accountId, "DEBIT", "10.00", "USD");
            completeTransaction(transactionId);
            jdbcTemplate.update("DELETE FROM ledger_entries WHERE transaction_id = ?", transactionId);
        }));
    }

    @Test
    void accountBalanceProjectionCannotBeChangedDirectly() {
        UUID accountId = insertSystemAccount("USD");
        assertThrows(RuntimeException.class, () -> transactionTemplate.executeWithoutResult(status -> jdbcTemplate
                .update("UPDATE accounts SET balance = ? WHERE id = ?", new BigDecimal("1.00"), accountId)));
    }

    @Test
    void transactionAndAccountCurrencyCannotDivergeFromExistingEntries() {
        assertThrows(RuntimeException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            UUID transactionId = insertPendingTransaction("USD");
            UUID accountId = insertSystemAccount("USD");
            insertEntry(transactionId, accountId, "DEBIT", "10.00", "USD");
            jdbcTemplate.update("UPDATE ledger_transactions SET currency = 'EUR' WHERE id = ?", transactionId);
        }));

        assertThrows(RuntimeException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            UUID transactionId = insertPendingTransaction("USD");
            UUID accountId = insertSystemAccount("USD");
            insertEntry(transactionId, accountId, "DEBIT", "10.00", "USD");
            jdbcTemplate.update("UPDATE accounts SET currency = 'EUR' WHERE id = ?", accountId);
        }));
    }

    private void register(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"CorrectHorseBattery16\"}"))
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

    private String uniqueEmail() {
        return "phase1-" + UUID.randomUUID() + "@example.com";
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

    private UUID insertSystemAccount(String currency) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO accounts
                    (id, account_number, account_type, currency, status, balance)
                VALUES (?, ?, 'SYSTEM', ?, 'ACTIVE', 0)
                """, id, "SYS-" + id.toString().replace("-", "").substring(0, 30), currency);
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
}
