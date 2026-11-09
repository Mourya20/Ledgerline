package com.vaultora.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.vaultora.domain.AccountEntity;
import com.vaultora.domain.AccountStatus;
import com.vaultora.domain.AccountType;
import com.vaultora.domain.UserEntity;
import com.vaultora.domain.UserRole;
import com.vaultora.domain.UserStatus;
import com.vaultora.repository.AccountRepository;
import com.vaultora.repository.UserRepository;
import com.vaultora.transaction.TransactionService;
import com.vaultora.transaction.dto.DepositRequest;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class AccountManagementIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UserEntity customer1;
    private UserEntity customer2;
    private UserEntity admin;
    private UserEntity auditor;

    private AccountEntity customer1ZeroBalanceAccount;
    private AccountEntity customer2Account;

    @BeforeEach
    void setUp() {
        customer1 = createOrGetUser("cust_mgmt_1@vaultora.com", UserRole.CUSTOMER);
        customer2 = createOrGetUser("cust_mgmt_2@vaultora.com", UserRole.CUSTOMER);
        admin = createOrGetUser("admin_mgmt@vaultora.com", UserRole.ADMIN);
        auditor = createOrGetUser("auditor_mgmt@vaultora.com", UserRole.AUDITOR);

        customer1ZeroBalanceAccount = createAccount("VLTRTEST001" + System.nanoTime() % 100000000L, customer1);
        customer2Account = createAccount("VLTRTEST002" + System.nanoTime() % 100000000L, customer2);
    }

    private UserEntity createOrGetUser(String email, UserRole role) {
        return userRepository.findByEmailIgnoreCase(email).orElseGet(() -> {
            UserEntity user = new UserEntity(email, passwordEncoder.encode("ValidPass123!"), role);
            user.setStatus(UserStatus.ACTIVE);
            return userRepository.save(user);
        });
    }

    private AccountEntity createAccount(String accountNumber, UserEntity owner) {
        AccountEntity account = new AccountEntity(accountNumber, owner, AccountType.CUSTOMER, "INR");
        return accountRepository.save(account);
    }

    private MockHttpSession createSession(UserEntity user) {
        MockHttpSession session = new MockHttpSession();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                user.getEmail(),
                null,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
        );
        context.setAuthentication(auth);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    @Test
    @DisplayName("Customer can close own zero-balance account successfully")
    void customerCanCloseOwnZeroBalanceAccount() throws Exception {
        MockHttpSession session = createSession(customer1);

        mockMvc.perform(delete("/api/v1/accounts/" + customer1ZeroBalanceAccount.getId())
                .session(session)
                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.accountNumber").value(customer1ZeroBalanceAccount.getAccountNumber()));

        AccountEntity refreshed = accountRepository.findById(customer1ZeroBalanceAccount.getId()).orElseThrow();
        assertEquals(AccountStatus.CLOSED, refreshed.getStatus());
    }

    @Test
    @DisplayName("Customer cannot close an account with a positive balance")
    void customerCannotCloseAccountWithPositiveBalance() throws Exception {
        // Deposit funds into customer1's account
        transactionService.deposit(
                customer1,
                UUID.randomUUID().toString(),
                new DepositRequest(customer1ZeroBalanceAccount.getId(), new BigDecimal("500.00"), "INR")
        );

        MockHttpSession session = createSession(customer1);

        mockMvc.perform(delete("/api/v1/accounts/" + customer1ZeroBalanceAccount.getId())
                .session(session)
                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Account balance must be 0.00 to close")));
    }

    @Test
    @DisplayName("Customer cannot close another customer's account")
    void customerCannotCloseAnotherCustomersAccount() throws Exception {
        MockHttpSession session = createSession(customer1);

        mockMvc.perform(delete("/api/v1/accounts/" + customer2Account.getId())
                .session(session)
                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Auditor cannot close any account (read-only role)")
    void auditorCannotCloseAccount() throws Exception {
        MockHttpSession session = createSession(auditor);

        mockMvc.perform(delete("/api/v1/accounts/" + customer1ZeroBalanceAccount.getId())
                .session(session)
                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Admin can view accounts of a specific customer")
    void adminCanViewAccountsOfSpecificCustomer() throws Exception {
        MockHttpSession session = createSession(admin);

        mockMvc.perform(get("/api/v1/admin/users/" + customer1.getId() + "/accounts")
                .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].ownerEmail").value(customer1.getEmail()));
    }

    @Test
    @DisplayName("Admin can close a customer account")
    void adminCanCloseCustomerAccount() throws Exception {
        MockHttpSession session = createSession(admin);

        mockMvc.perform(delete("/api/v1/accounts/" + customer2Account.getId())
                .session(session)
                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        AccountEntity refreshed = accountRepository.findById(customer2Account.getId()).orElseThrow();
        assertEquals(AccountStatus.CLOSED, refreshed.getStatus());
    }

    @Test
    @DisplayName("Customer cannot access admin user accounts endpoint")
    void customerCannotAccessAdminUserAccountsEndpoint() throws Exception {
        MockHttpSession session = createSession(customer1);

        mockMvc.perform(get("/api/v1/admin/users/" + customer2.getId() + "/accounts")
                .session(session))
                .andExpect(status().isForbidden());
    }
}
