package com.vaultora.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.vaultora.domain.AccountEntity;
import com.vaultora.domain.AccountRequestEntity;
import com.vaultora.admin.AdminService;
import com.vaultora.domain.AccountStatus;
import com.vaultora.domain.AccountType;
import com.vaultora.domain.UserEntity;
import com.vaultora.domain.UserRole;
import com.vaultora.domain.UserStatus;
import com.vaultora.repository.AccountRepository;
import com.vaultora.repository.AccountRequestRepository;
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
    private AccountRequestRepository accountRequestRepository;

    @Autowired
    private com.vaultora.admin.AdminService adminService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

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
        accountRequestRepository.deleteAll();
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
                ON CONFLICT (account_number) DO NOTHING
                """);
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

    @Test
    @DisplayName("Admin can open bank account for non-existing customer with auto-assigned ID")
    void adminCanOpenAccountForNonExistingCustomerWithAutoAssignedId() throws Exception {
        MockHttpSession session = createSession(admin);
        String newEmail = "new_auto_customer_" + System.nanoTime() + "@vaultora.com";

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/accounts")
                .session(session)
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(String.format("{\"currency\":\"INR\",\"customerEmail\":\"%s\",\"customerId\":\"%s\"}", newEmail, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerEmail").value(newEmail))
                .andExpect(jsonPath("$.ownerId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.balance").value(0.00));

        UserEntity createdUser = userRepository.findByEmailIgnoreCase(newEmail).orElseThrow();
        assertEquals(UserRole.CUSTOMER, createdUser.getRole());
        assertEquals(UserStatus.ACTIVE, createdUser.getStatus());
    }

    @Test
    @DisplayName("Admin can open bank account for non-existing customer with explicit custom ID")
    void adminCanOpenAccountForNonExistingCustomerWithExplicitCustomId() throws Exception {
        MockHttpSession session = createSession(admin);
        UUID customId = UUID.randomUUID();
        String newEmail = "new_custom_customer_" + System.nanoTime() + "@vaultora.com";

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/accounts")
                .session(session)
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(String.format("{\"currency\":\"INR\",\"customerEmail\":\"%s\",\"customerId\":\"%s\"}", newEmail, customId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerEmail").value(newEmail))
                .andExpect(jsonPath("$.ownerId").value(customId.toString()));

        UserEntity createdUser = userRepository.findById(customId).orElseThrow();
        assertEquals(newEmail.toLowerCase(java.util.Locale.ROOT), createdUser.getEmail());
    }

    @Test
    @DisplayName("Admin can open bank account and assign it to an AUDITOR")
    void adminCanOpenAccountAndAssignToAuditor() throws Exception {
        MockHttpSession session = createSession(admin);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/accounts")
                .session(session)
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(String.format("{\"currency\":\"INR\",\"customerEmail\":\"%s\",\"customerId\":\"%s\"}", auditor.getEmail(), auditor.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerEmail").value(auditor.getEmail()))
                .andExpect(jsonPath("$.ownerId").value(auditor.getId().toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("Admin can filter accounts by customerId")
    void adminCanFilterAccountsByCustomerId() throws Exception {
        MockHttpSession session = createSession(admin);

        mockMvc.perform(get("/api/v1/accounts?customerId=" + customer1.getId())
                .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].ownerEmail").value(customer1.getEmail()))
                .andExpect(jsonPath("$[0].ownerId").value(customer1.getId().toString()));
    }

    @Test
    @DisplayName("Admin can assign/reset password for an existing customer")
    void adminCanAssignPasswordToCustomer() throws Exception {
        MockHttpSession adminSession = createSession(admin);
        String newPass = "UpdatedCustomerPass123!";

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/users/" + customer1.getId() + "/password")
                .session(adminSession)
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(String.format("{\"password\":\"%s\"}", newPass)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password assigned successfully"));

        // Verify customer can login with new password
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(String.format("{\"email\":\"%s\",\"password\":\"%s\"}", customer1.getEmail(), newPass)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(customer1.getEmail()));
    }

    @Test
    @DisplayName("Admin can assign/reset password for an existing auditor")
    void adminCanAssignPasswordToAuditor() throws Exception {
        MockHttpSession adminSession = createSession(admin);
        String newPass = "UpdatedAuditorPass123!";

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/users/" + auditor.getId() + "/password")
                .session(adminSession)
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(String.format("{\"password\":\"%s\"}", newPass)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password assigned successfully"));

        // Verify auditor can login with new password
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(String.format("{\"email\":\"%s\",\"password\":\"%s\"}", auditor.getEmail(), newPass)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(auditor.getEmail()));
    }

    @Test
    @DisplayName("Admin cannot assign password to another ADMIN user")
    void adminCannotAssignPasswordToAnotherAdmin() throws Exception {
        MockHttpSession adminSession = createSession(admin);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/users/" + admin.getId() + "/password")
                .session(adminSession)
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"password\":\"AttemptAdminPass123!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Administrators can only assign passwords for CUSTOMER and AUDITOR users")));
    }

    @Test
    @DisplayName("Customer cannot access admin password assignment endpoint")
    void customerCannotAssignPasswords() throws Exception {
        MockHttpSession customerSession = createSession(customer1);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/users/" + customer2.getId() + "/password")
                .session(customerSession)
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"password\":\"HackedPassword123!\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Admin can open account for non-existing user with explicitly assigned AUDITOR role")
    void adminCanOpenAccountForNonExistingAuditorWithAssignedRole() throws Exception {
        MockHttpSession session = createSession(admin);
        UUID newAuditorId = UUID.randomUUID();
        String newAuditorEmail = "new_live_auditor_" + System.nanoTime() + "@vaultora.com";
        String initialPass = "AuditorInitialPass123!";

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/accounts")
                .session(session)
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(String.format("{\"currency\":\"INR\",\"customerEmail\":\"%s\",\"customerId\":\"%s\",\"customerPassword\":\"%s\",\"role\":\"AUDITOR\"}",
                        newAuditorEmail, newAuditorId, initialPass)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerEmail").value(newAuditorEmail))
                .andExpect(jsonPath("$.ownerId").value(newAuditorId.toString()));

        UserEntity created = userRepository.findById(newAuditorId).orElseThrow();
        assertEquals(UserRole.AUDITOR, created.getRole());

        // Verify login works with initial password
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(String.format("{\"email\":\"%s\",\"password\":\"%s\"}", newAuditorEmail, initialPass)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("AUDITOR"));
    }

    @Test
    @DisplayName("Admin cannot assign ADMIN role during dynamic onboarding")
    void adminCannotAssignAdminRoleDuringOnboarding() throws Exception {
        MockHttpSession session = createSession(admin);
        UUID newId = UUID.randomUUID();
        String newEmail = "attempt_admin_" + System.nanoTime() + "@vaultora.com";

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/accounts")
                .session(session)
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(String.format("{\"currency\":\"INR\",\"customerEmail\":\"%s\",\"customerId\":\"%s\",\"role\":\"ADMIN\"}",
                        newEmail, newId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Newly onboarded users can only be assigned CUSTOMER or AUDITOR role")));
    }

    @Test
    @DisplayName("Customer can submit account opening request")
    void customerCanSubmitAccountOpeningRequest() throws Exception {
        MockHttpSession session = createSession(customer1);

        mockMvc.perform(post("/api/v1/account-requests")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"INR\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.userEmail").value(customer1.getEmail()));
    }

    @Test
    @DisplayName("Customer cannot submit duplicate pending account opening request")
    void customerCannotSubmitDuplicatePendingRequest() throws Exception {
        MockHttpSession session = createSession(customer1);

        mockMvc.perform(post("/api/v1/account-requests")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"INR\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/account-requests")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"INR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("You already have a pending account opening request")));
    }

    @Test
    @DisplayName("Customer can view own account requests but not other customers'")
    void customerCanViewOwnAccountRequests() throws Exception {
        MockHttpSession session1 = createSession(customer1);
        MockHttpSession session2 = createSession(customer2);

        mockMvc.perform(post("/api/v1/account-requests")
                .session(session1)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"INR\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/account-requests")
                .session(session1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userEmail").value(customer1.getEmail()));

        mockMvc.perform(get("/api/v1/account-requests")
                .session(session2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("Auditor can view account requests but cannot approve or reject them")
    void auditorCanViewRequestsButCannotApprove() throws Exception {
        MockHttpSession custSession = createSession(customer1);
        MockHttpSession auditorSession = createSession(auditor);

        MvcResult result = mockMvc.perform(post("/api/v1/account-requests")
                .session(custSession)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"INR\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        String requestId = com.jayway.jsonpath.JsonPath.read(json, "$.id");

        mockMvc.perform(get("/api/v1/account-requests")
                .session(auditorSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mockMvc.perform(post("/api/v1/account-requests/" + requestId + "/approve")
                .session(auditorSession)
                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Admin can approve account request which creates bank account for customer")
    void adminCanApproveAccountRequest() throws Exception {
        MockHttpSession custSession = createSession(customer1);
        MockHttpSession adminSession = createSession(admin);

        MvcResult result = mockMvc.perform(post("/api/v1/account-requests")
                .session(custSession)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"INR\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        String requestId = com.jayway.jsonpath.JsonPath.read(json, "$.id");

        MvcResult approvedResult = mockMvc.perform(post("/api/v1/account-requests/" + requestId + "/approve")
                .session(adminSession)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"adminNotes\":\"Identity verified. Account opened.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.accountNumber").isNotEmpty())
                .andExpect(jsonPath("$.reviewedBy").value(admin.getEmail()))
                .andReturn();

        String approvedJson = approvedResult.getResponse().getContentAsString();
        String newAccNumber = com.jayway.jsonpath.JsonPath.read(approvedJson, "$.accountNumber");

        AccountEntity createdAcc = accountRepository.findByAccountNumber(newAccNumber).orElseThrow();
        assertEquals(customer1.getId(), createdAcc.getOwner().getId());
        assertEquals(AccountStatus.ACTIVE, createdAcc.getStatus());
        assertEquals(new BigDecimal("0.00"), createdAcc.getBalance());
    }

    @Test
    @DisplayName("Admin can reject account request")
    void adminCanRejectAccountRequest() throws Exception {
        MockHttpSession custSession = createSession(customer1);
        MockHttpSession adminSession = createSession(admin);

        MvcResult result = mockMvc.perform(post("/api/v1/account-requests")
                .session(custSession)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"INR\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        String requestId = com.jayway.jsonpath.JsonPath.read(json, "$.id");

        mockMvc.perform(post("/api/v1/account-requests/" + requestId + "/reject")
                .session(adminSession)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"adminNotes\":\"Incomplete KYC documents\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reviewedBy").value(admin.getEmail()))
                .andExpect(jsonPath("$.accountNumber").doesNotExist());
    }
    @Test
    @DisplayName("Customer can change password with correct current password")
    void customerCanChangePassword() throws Exception {
        UserEntity user = createOrGetUser("pwd_cust_" + System.nanoTime() + "@vaultora.com", UserRole.CUSTOMER);
        MockHttpSession session = createSession(user);

        mockMvc.perform(post("/api/v1/auth/password")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"ValidPass123!\",\"newPassword\":\"BrandNewPass456!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password changed successfully"));

        // Verify login succeeds with new password
        mockMvc.perform(post("/api/v1/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"BrandNewPass456!\"}"))
                .andExpect(status().isOk());

        // Verify login fails with old password
        mockMvc.perform(post("/api/v1/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"ValidPass123!\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Password change fails when current password is wrong")
    void passwordChangeFailsWithWrongCurrentPassword() throws Exception {
        UserEntity user = createOrGetUser("pwd_cust_err_" + System.nanoTime() + "@vaultora.com", UserRole.CUSTOMER);
        MockHttpSession session = createSession(user);

        mockMvc.perform(post("/api/v1/auth/password")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"IncorrectPass123!\",\"newPassword\":\"BrandNewPass456!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Current password does not match"));
    }

    @Test
    @DisplayName("Password change fails when new password is same as current password")
    void passwordChangeFailsWhenSamePassword() throws Exception {
        UserEntity user = createOrGetUser("pwd_cust_same_" + System.nanoTime() + "@vaultora.com", UserRole.CUSTOMER);
        MockHttpSession session = createSession(user);

        mockMvc.perform(post("/api/v1/auth/password")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"ValidPass123!\",\"newPassword\":\"ValidPass123!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("New password cannot be identical to current password"));
    }

    @Test
    @DisplayName("Auditor and Admin can also change their own passwords")
    void auditorAndAdminCanChangePassword() throws Exception {
        UserEntity testAuditor = createOrGetUser("pwd_aud_" + System.nanoTime() + "@vaultora.com", UserRole.AUDITOR);
        MockHttpSession auditorSession = createSession(testAuditor);
        mockMvc.perform(post("/api/v1/auth/password")
                .session(auditorSession)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"ValidPass123!\",\"newPassword\":\"AuditorNewPass123!\"}"))
                .andExpect(status().isOk());

        UserEntity testAdmin = createOrGetUser("pwd_adm_" + System.nanoTime() + "@vaultora.com", UserRole.ADMIN);
        MockHttpSession adminSession = createSession(testAdmin);
        mockMvc.perform(post("/api/v1/auth/password")
                .session(adminSession)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"ValidPass123!\",\"newPassword\":\"AdminNewPass123!\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Customer cannot directly create account via accounts endpoint")
    void customerCannotDirectlyCreateAccountViaAccountsEndpoint() throws Exception {
        MockHttpSession session = createSession(customer1);

        mockMvc.perform(post("/api/v1/accounts")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"INR\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Administrator cannot close an account with positive balance")
    void adminCannotCloseAccountWithPositiveBalance() throws Exception {
        // Deposit funds into customer2's account
        transactionService.deposit(
                customer2,
                UUID.randomUUID().toString(),
                new DepositRequest(customer2Account.getId(), new BigDecimal("300.00"), "INR")
        );

        MockHttpSession session = createSession(admin);

        mockMvc.perform(patch("/api/v1/admin/accounts/" + customer2Account.getId() + "/status")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Closed account cannot be reopened or modified")
    void closedAccountCannotBeReopened() throws Exception {
        MockHttpSession session = createSession(admin);
        // customer1ZeroBalanceAccount is zero balance, close it first
        adminService.updateAccountStatus(customer1ZeroBalanceAccount.getId(), AccountStatus.CLOSED, admin);

        AccountEntity closed = accountRepository.findById(customer1ZeroBalanceAccount.getId()).orElseThrow();
        assertEquals(AccountStatus.CLOSED, closed.getStatus());

        // Attempting to reopen as ACTIVE must fail
        mockMvc.perform(patch("/api/v1/admin/accounts/" + closed.getId() + "/status")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Database enforces at most one pending request per customer")
    void duplicatePendingRequestEnforcedByPartialUniqueIndex() throws Exception {
        UserEntity user = createOrGetUser("single_pend_" + System.nanoTime() + "@vaultora.com", UserRole.CUSTOMER);
        MockHttpSession session = createSession(user);

        // First request succeeds
        mockMvc.perform(post("/api/v1/account-requests")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"INR\"}"))
                .andExpect(status().isCreated());

        // Second pending request must be rejected
        mockMvc.perform(post("/api/v1/account-requests")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"INR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("You already have a pending account opening request awaiting administrator approval"));
    }

    @Test
    @DisplayName("Reviewing an already reviewed request fails")
    void reviewingAlreadyReviewedRequestFails() throws Exception {
        UserEntity user = createOrGetUser("dup_rev_" + System.nanoTime() + "@vaultora.com", UserRole.CUSTOMER);
        AccountRequestEntity req = accountRequestRepository.save(new AccountRequestEntity(user, "INR"));

        MockHttpSession session = createSession(admin);

        // First review: approve
        mockMvc.perform(post("/api/v1/account-requests/" + req.getId() + "/approve")
                .session(session)
                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // Second review attempt must fail
        mockMvc.perform(post("/api/v1/account-requests/" + req.getId() + "/approve")
                .session(session)
                .with(csrf()))
                .andExpect(status().isBadRequest());
    }
}
