import React, { useEffect, useState, useCallback, useId } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { healthApi } from '../api/health';
import { accountsApi } from '../api/accounts';
import { adminApi } from '../api/admin';
import { transactionsApi } from '../api/transactions';
import type {
  HealthResponse,
  AccountResponse,
  UserSummaryResponse,
  AdminStatsResponse,
  TransactionResponse,
  AccountStatus,
  UserStatus,
} from '../api/types';
import { formatCurrency, formatDate } from '../utils/formatters';
import { PortalSwitcher } from '../components/PortalSwitcher';

const DEMO_CUSTOMERS = [
  'customer1@ledgerline.com',
  'customer2@ledgerline.com',
  'customer3@ledgerline.com',
  'customer4@ledgerline.com',
  'customer5@ledgerline.com',
  'customer6@ledgerline.com',
  'customer7@ledgerline.com',
  'customer8@ledgerline.com',
  'customer9@ledgerline.com',
  'customer10@ledgerline.com',
  'customer11@ledgerline.com',
  'customer12@ledgerline.com',
];

type AdminTab = 'overview' | 'accounts' | 'users' | 'transactions' | 'deposit';

export const AdminPage: React.FC = () => {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const customerListId = useId();

  // Active Tab
  const [activeTab, setActiveTab] = useState<AdminTab>('overview');

  // Overview / Health state
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [healthLoading, setHealthLoading] = useState<boolean>(true);
  const [healthError, setHealthError] = useState<string | null>(null);
  const [stats, setStats] = useState<AdminStatsResponse | null>(null);

  // Accounts state
  const [accounts, setAccounts] = useState<AccountResponse[]>([]);
  const [accountsLoading, setAccountsLoading] = useState<boolean>(true);
  const [accountsError, setAccountsError] = useState<string | null>(null);
  const [searchAccountQuery, setSearchAccountQuery] = useState<string>('');
  const [statusUpdatingAccountId, setStatusUpdatingAccountId] = useState<string | null>(null);

  // Account creation form state
  const [targetEmail, setTargetEmail] = useState<string>('');
  const [creatingAccount, setCreatingAccount] = useState<boolean>(false);
  const [createAccountSuccess, setCreateAccountSuccess] = useState<AccountResponse | null>(null);
  const [createAccountError, setCreateAccountError] = useState<string | null>(null);

  // Users state
  const [users, setUsers] = useState<UserSummaryResponse[]>([]);
  const [usersLoading, setUsersLoading] = useState<boolean>(true);
  const [usersError, setUsersError] = useState<string | null>(null);
  const [searchUserQuery, setSearchUserQuery] = useState<string>('');
  const [statusUpdatingUserId, setStatusUpdatingUserId] = useState<string | null>(null);

  // Transactions state
  const [transactions, setTransactions] = useState<TransactionResponse[]>([]);
  const [transactionsLoading, setTransactionsLoading] = useState<boolean>(true);
  const [transactionsError, setTransactionsError] = useState<string | null>(null);
  const [filterAccountId, setFilterAccountId] = useState<string>('');

  // Administrative Deposit form state
  const [depositAccountId, setDepositAccountId] = useState<string>('');
  const [depositAmount, setDepositAmount] = useState<string>('');
  const [depositing, setDepositing] = useState<boolean>(false);
  const [depositSuccess, setDepositSuccess] = useState<TransactionResponse | null>(null);
  const [depositError, setDepositError] = useState<string | null>(null);

  // Demonstration vs Pre-Existing / Dev scopes
  const [accountScope, setAccountScope] = useState<'all' | 'demo' | 'dev'>('all');
  const [userScope, setUserScope] = useState<'all' | 'demo' | 'dev'>('all');

  const isDemoUser = (email: string) => /^(customer([1-9]|1[0-2])|auditor[1-3]|admin1)@ledgerline\.com$/i.test(email);
  const isDemoAccount = (ownerEmail?: string | null) => !!ownerEmail && /^(customer([1-9]|1[0-2]))@ledgerline\.com$/i.test(ownerEmail);

  const demoUsersCount = users.filter((u) => isDemoUser(u.email)).length;
  const demoAccountsCount = accounts.filter((a) => isDemoAccount(a.ownerEmail)).length;

  const fetchHealth = useCallback(async () => {
    try {
      setHealthLoading(true);
      setHealthError(null);
      const res = await healthApi.getHealth();
      setHealth(res);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to query system health.';
      setHealthError(msg);
    } finally {
      setHealthLoading(false);
    }
  }, []);

  const fetchStats = useCallback(async () => {
    try {
      const res = await adminApi.getStats();
      setStats(res);
    } catch (err) {
      console.warn('Failed to fetch admin stats:', err);
    }
  }, []);

  const fetchAccounts = useCallback(async () => {
    try {
      setAccountsLoading(true);
      setAccountsError(null);
      const res = await accountsApi.getAccounts();
      setAccounts(res);
      if (res.length > 0 && !depositAccountId) {
        setDepositAccountId(res[0].id);
      }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to retrieve accounts.';
      setAccountsError(msg);
    } finally {
      setAccountsLoading(false);
    }
  }, [depositAccountId]);

  const fetchUsers = useCallback(async () => {
    try {
      setUsersLoading(true);
      setUsersError(null);
      const res = await adminApi.getUsers();
      setUsers(res);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to retrieve users.';
      setUsersError(msg);
    } finally {
      setUsersLoading(false);
    }
  }, []);

  const fetchTransactions = useCallback(async (accId?: string) => {
    try {
      setTransactionsLoading(true);
      setTransactionsError(null);
      const res = await transactionsApi.getTransactions({
        accountId: accId || undefined,
        size: 25,
      });
      setTransactions(res.content);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to retrieve transactions.';
      setTransactionsError(msg);
    } finally {
      setTransactionsLoading(false);
    }
  }, []);

  // Initial load
  useEffect(() => {
    fetchHealth();
    fetchStats();
    fetchAccounts();
    fetchUsers();
    fetchTransactions();
  }, [fetchHealth, fetchStats, fetchAccounts, fetchUsers, fetchTransactions]);

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  // Create & Assign Customer Account
  const handleCreateAccount = async (e: React.FormEvent) => {
    e.preventDefault();
    const email = targetEmail.trim();
    if (!email) {
      setCreateAccountError('Please provide a target customer email address.');
      return;
    }

    setCreatingAccount(true);
    setCreateAccountError(null);
    setCreateAccountSuccess(null);

    try {
      const created = await accountsApi.createAccount({
        currency: 'INR',
        customerEmail: email,
      });
      setCreateAccountSuccess(created);
      setTargetEmail('');
      await fetchAccounts();
      await fetchStats();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to create and assign account.';
      setCreateAccountError(msg);
    } finally {
      setCreatingAccount(false);
    }
  };

  // Update Account Status (Freeze / Unfreeze / Close)
  const handleUpdateAccountStatus = async (accountId: string, newStatus: AccountStatus) => {
    try {
      setStatusUpdatingAccountId(accountId);
      await adminApi.updateAccountStatus(accountId, newStatus);
      await fetchAccounts();
    } catch (err: unknown) {
      alert(err instanceof Error ? err.message : 'Failed to update account status');
    } finally {
      setStatusUpdatingAccountId(null);
    }
  };

  // Update User Status (Suspend / Activate)
  const handleUpdateUserStatus = async (userId: string, newStatus: UserStatus) => {
    try {
      setStatusUpdatingUserId(userId);
      await adminApi.updateUserStatus(userId, newStatus);
      await fetchUsers();
      await fetchStats();
    } catch (err: unknown) {
      alert(err instanceof Error ? err.message : 'Failed to update user status');
    } finally {
      setStatusUpdatingUserId(null);
    }
  };

  // Administrative Deposit
  const handleAdminDeposit = async (e: React.FormEvent) => {
    e.preventDefault();
    const amt = parseFloat(depositAmount);
    if (isNaN(amt) || amt <= 0) {
      setDepositError('Please enter a valid amount greater than zero.');
      return;
    }
    if (!depositAccountId) {
      setDepositError('Please select a target account.');
      return;
    }

    setDepositing(true);
    setDepositError(null);
    setDepositSuccess(null);

    try {
      const key = `admin-dep-${Date.now()}-${Math.random().toString(36).substring(2, 7)}`;
      const res = await transactionsApi.deposit(
        {
          accountId: depositAccountId,
          amount: amt,
          currency: 'INR',
        },
        key
      );
      setDepositSuccess(res);
      setDepositAmount('');
      await fetchAccounts();
      await fetchStats();
      await fetchTransactions(filterAccountId);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to execute deposit.';
      setDepositError(msg);
    } finally {
      setDepositing(false);
    }
  };

  const getStatusColor = (status?: string) => {
    switch (status?.toUpperCase()) {
      case 'UP':
      case 'ACTIVE':
      case 'COMPLETED':
        return '#10B981';
      case 'DOWN':
      case 'DISABLED':
      case 'FAILED':
        return '#EF4444';
      case 'FROZEN':
      case 'UNKNOWN':
      case 'UNAVAILABLE':
        return '#F59E0B';
      default:
        return '#9CA3AF';
    }
  };

  const filteredAccounts = accounts.filter((acc) => {
    if (accountScope === 'demo' && !isDemoAccount(acc.ownerEmail)) return false;
    if (accountScope === 'dev' && isDemoAccount(acc.ownerEmail)) return false;
    if (!searchAccountQuery.trim()) return true;
    const q = searchAccountQuery.toLowerCase();
    return (
      acc.accountNumber.toLowerCase().includes(q) ||
      (acc.ownerEmail && acc.ownerEmail.toLowerCase().includes(q)) ||
      acc.id.toLowerCase().includes(q)
    );
  });

  const filteredUsers = users.filter((u) => {
    if (userScope === 'demo' && !isDemoUser(u.email)) return false;
    if (userScope === 'dev' && isDemoUser(u.email)) return false;
    if (!searchUserQuery.trim()) return true;
    const q = searchUserQuery.toLowerCase();
    return (
      u.email.toLowerCase().includes(q) ||
      u.role.toLowerCase().includes(q) ||
      u.status.toLowerCase().includes(q) ||
      u.id.toLowerCase().includes(q)
    );
  });

  return (
    <div style={{ minHeight: '100vh', backgroundColor: '#F8FAFC', display: 'flex', flexDirection: 'column' }}>
      {/* Top Navigation Bar */}
      <header style={{
        backgroundColor: '#FFFFFF',
        borderBottom: '1px solid #E5E7EB',
        padding: '0 24px',
        height: '64px',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        position: 'sticky',
        top: 0,
        zIndex: 20
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <Link
            to="/"
            style={{
              fontWeight: 700,
              fontSize: '18px',
              color: '#111827',
              letterSpacing: '-0.02em',
              textDecoration: 'none'
            }}
          >
            LedgerLine
          </Link>
          <span style={{
            fontSize: '11px',
            fontWeight: 700,
            textTransform: 'uppercase',
            letterSpacing: '0.05em',
            padding: '3px 8px',
            borderRadius: '4px',
            backgroundColor: '#EEF2FF',
            color: '#4338CA',
            border: '1px solid #C7D2FE'
          }}>
            Administration Console
          </span>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
          <PortalSwitcher />
          {user && (
            <span style={{ fontSize: '13px', color: '#4B5563' }}>
              {user.email}
            </span>
          )}
          <button
            type="button"
            onClick={handleLogout}
            className="btn btn-secondary"
            style={{ padding: '6px 12px', fontSize: '13px' }}
          >
            Sign out
          </button>
        </div>
      </header>

      {/* Main Container */}
      <main style={{ flex: 1, padding: '24px', maxWidth: '1200px', width: '100%', margin: '0 auto' }}>
        {/* Header Title */}
        <div style={{ marginBottom: '20px' }}>
          <h1 style={{ fontSize: '24px', fontWeight: 700, color: '#111827', margin: 0, letterSpacing: '-0.02em' }}>
            System Administration & Control Center
          </h1>
          <p style={{ fontSize: '14px', color: '#6B7280', marginTop: '4px', margin: 0 }}>
            Comprehensive administrative authority: identity management, account provisioning, lifecycle controls, and bank-wide transaction oversight.
          </p>
        </div>

        {/* Tab Navigation Navigation Bar */}
        <div style={{
          display: 'flex',
          gap: '8px',
          borderBottom: '1px solid #E5E7EB',
          marginBottom: '24px',
          overflowX: 'auto',
          paddingBottom: '2px'
        }}>
          {[
            { id: 'overview', label: 'Overview & Diagnostics' },
            { id: 'accounts', label: `Accounts Directory (${accounts.length})` },
            { id: 'users', label: `Users & Roles (${users.length})` },
            { id: 'transactions', label: `Bank-Wide Transactions (${transactions.length})` },
            { id: 'deposit', label: 'Administrative Credit / Deposit' },
          ].map((tab) => (
            <button
              key={tab.id}
              type="button"
              onClick={() => setActiveTab(tab.id as AdminTab)}
              style={{
                padding: '10px 16px',
                fontSize: '14px',
                fontWeight: activeTab === tab.id ? 600 : 500,
                color: activeTab === tab.id ? '#0D6E64' : '#6B7280',
                borderBottom: activeTab === tab.id ? '2px solid #0D6E64' : '2px solid transparent',
                backgroundColor: 'transparent',
                borderTop: 'none',
                borderLeft: 'none',
                borderRight: 'none',
                cursor: 'pointer',
                whiteSpace: 'nowrap'
              }}
            >
              {tab.label}
            </button>
          ))}
        </div>

        {/* TAB 1: OVERVIEW & DIAGNOSTICS */}
        {activeTab === 'overview' && (
          <div>
            {/* Quick Metrics Grid */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '16px', marginBottom: '24px' }}>
              <div className="card" style={{ padding: '16px' }}>
                <div style={{ fontSize: '12px', color: '#6B7280', fontWeight: 500 }}>Total Registered Users</div>
                <div style={{ fontSize: '24px', fontWeight: 700, color: '#111827', marginTop: '4px' }}>
                  {stats ? stats.totalUsers : users.length}
                </div>
                <div style={{ fontSize: '11px', color: '#059669', marginTop: '2px' }}>
                  {demoUsersCount} Demonstration · {users.length - demoUsersCount} Pre-Existing/Dev
                </div>
              </div>

              <div className="card" style={{ padding: '16px' }}>
                <div style={{ fontSize: '12px', color: '#6B7280', fontWeight: 500 }}>Total Customer Accounts</div>
                <div style={{ fontSize: '24px', fontWeight: 700, color: '#111827', marginTop: '4px' }}>
                  {stats ? stats.totalAccounts : accounts.length}
                </div>
                <div style={{ fontSize: '11px', color: '#0369A1', marginTop: '2px' }}>
                  {demoAccountsCount} Demonstration · {accounts.length - demoAccountsCount} Pre-Existing/Dev
                </div>
              </div>

              <div className="card" style={{ padding: '16px' }}>
                <div style={{ fontSize: '12px', color: '#6B7280', fontWeight: 500 }}>Total Customer Deposits</div>
                <div style={{ fontSize: '24px', fontWeight: 700, color: '#0D6E64', marginTop: '4px' }}>
                  {stats ? formatCurrency(stats.totalCustomerBalance) : '—'}
                </div>
                <div style={{ fontSize: '11px', color: '#6B7280', marginTop: '2px' }}>Core Ledger Liability Projections</div>
              </div>

              <div className="card" style={{ padding: '16px' }}>
                <div style={{ fontSize: '12px', color: '#6B7280', fontWeight: 500 }}>Recorded Transactions</div>
                <div style={{ fontSize: '24px', fontWeight: 700, color: '#111827', marginTop: '4px' }}>
                  {stats ? stats.totalTransactions : transactions.length}
                </div>
                <div style={{ fontSize: '11px', color: '#6B7280', marginTop: '2px' }}>Zero-Sum Debits=Credits</div>
              </div>
            </div>

            {/* Live System Health Card */}
            <div className="card" style={{ marginBottom: '24px' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
                <h2 style={{ fontSize: '16px', fontWeight: 600, color: '#111827', margin: 0 }}>
                  Live Platform Health & Readiness
                </h2>
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={fetchHealth}
                  disabled={healthLoading}
                  style={{ fontSize: '12px', padding: '4px 10px' }}
                >
                  {healthLoading ? 'Checking...' : 'Refresh Health'}
                </button>
              </div>

              {healthLoading && !health ? (
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '16px', fontSize: '13px', color: '#6B7280' }}>
                  <span style={{ display: 'inline-block', width: '12px', height: '12px', border: '2px solid #0F766E', borderTopColor: 'transparent', borderRadius: '50%', animation: 'spin 1s linear infinite' }} />
                  Connecting to /actuator/health...
                </div>
              ) : healthError ? (
                <div style={{ backgroundColor: '#FEF2F2', border: '1px solid #FECACA', borderRadius: '8px', padding: '14px 16px' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '6px' }}>
                    <span style={{ width: '10px', height: '10px', borderRadius: '50%', backgroundColor: '#EF4444' }} />
                    <strong style={{ color: '#991B1B', fontSize: '14px' }}>UNAVAILABLE</strong>
                  </div>
                  <p style={{ margin: 0, fontSize: '13px', color: '#B91C1C' }}>
                    Unable to query platform health endpoint: {healthError}
                  </p>
                </div>
              ) : health ? (
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '16px' }}>
                  <div style={{ backgroundColor: '#F9FAFB', padding: '16px', borderRadius: '8px', border: '1px solid #E5E7EB' }}>
                    <div style={{ fontSize: '12px', color: '#6B7280', marginBottom: '4px' }}>Application Status</div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                      <span style={{
                        width: '10px',
                        height: '10px',
                        borderRadius: '50%',
                        backgroundColor: getStatusColor(health.status)
                      }} />
                      <span style={{ fontSize: '18px', fontWeight: 700, color: '#111827' }}>
                        {health.status || 'UNKNOWN'}
                      </span>
                    </div>
                  </div>

                  <div style={{ backgroundColor: '#F9FAFB', padding: '16px', borderRadius: '8px', border: '1px solid #E5E7EB' }}>
                    <div style={{ fontSize: '12px', color: '#6B7280', marginBottom: '4px' }}>Probes & Groups</div>
                    <div style={{ fontSize: '14px', fontWeight: 600, color: '#374151' }}>
                      {health.groups && health.groups.length > 0 ? health.groups.join(', ') : 'Liveness, Readiness'}
                    </div>
                  </div>

                  <div style={{ backgroundColor: '#F9FAFB', padding: '16px', borderRadius: '8px', border: '1px solid #E5E7EB' }}>
                    <div style={{ fontSize: '12px', color: '#6B7280', marginBottom: '4px' }}>Active Session Identity</div>
                    <div style={{ fontSize: '14px', fontWeight: 600, color: '#374151' }}>
                      {user?.role} ({user?.email})
                    </div>
                  </div>
                </div>
              ) : null}
            </div>

            {/* Audit & Security Grid */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: '20px' }}>
              <div className="card" style={{ display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
                <div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '12px' }}>
                    <span className="badge badge-amber" style={{ fontSize: '11px', textTransform: 'uppercase' }}>
                      Authorized
                    </span>
                    <h3 style={{ fontSize: '16px', fontWeight: 600, color: '#111827', margin: 0 }}>
                      Audit Trail Inspection
                    </h3>
                  </div>
                  <p style={{ fontSize: '13px', color: '#6B7280', lineHeight: 1.5, margin: 0, marginBottom: '16px' }}>
                    Inspect the complete immutable audit trail of logins, status adjustments, administrative provisioning, and ledger transactions.
                  </p>
                </div>
                <Link to="/auditor" className="btn btn-primary" style={{ textAlign: 'center', textDecoration: 'none' }}>
                  Open Audit Portal
                </Link>
              </div>

              <div className="card">
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '12px' }}>
                  <span className="badge badge-green" style={{ fontSize: '11px', textTransform: 'uppercase' }}>
                    Enforced in SQL
                  </span>
                  <h3 style={{ fontSize: '16px', fontWeight: 600, color: '#111827', margin: 0 }}>
                    Security Boundaries
                  </h3>
                </div>
                <ul style={{ fontSize: '12px', color: '#4B5563', paddingLeft: '18px', margin: 0, lineHeight: 1.6 }}>
                  <li><strong>trg_protect_user_role_assignment</strong>: Database trigger aborts unauthorized elevation to ADMIN/AUDITOR.</li>
                  <li><strong>trg_protect_account_insertion</strong>: Enforces opening balance of 0.00 and CREDIT normal balance.</li>
                  <li><strong>Least Privilege</strong>: Runtime role has zero TRUNCATE or DELETE privileges.</li>
                </ul>
              </div>
            </div>
          </div>
        )}

        {/* TAB 2: ACCOUNTS DIRECTORY & LIFECYCLE MANAGEMENT */}
        {activeTab === 'accounts' && (
          <div>
            {/* Create & Assign Account Card */}
            <div className="card" style={{ marginBottom: '24px' }}>
              <div style={{ marginBottom: '16px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '4px' }}>
                  <span className="badge badge-amber" style={{ fontSize: '11px', textTransform: 'uppercase' }}>
                    Privileged Action
                  </span>
                  <h2 style={{ fontSize: '16px', fontWeight: 600, color: '#111827', margin: 0 }}>
                    Create & Assign Customer Account
                  </h2>
                </div>
                <p style={{ fontSize: '13px', color: '#6B7280', margin: 0 }}>
                  Open a new deposit account in the double-entry ledger and assign it directly to any customer.
                </p>
              </div>

              <form onSubmit={handleCreateAccount} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
                <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
                  <input
                    type="email"
                    list={customerListId}
                    value={targetEmail}
                    onChange={(e) => setTargetEmail(e.target.value)}
                    placeholder="Enter customer email (e.g. customer1@ledgerline.com)"
                    className="input-field"
                    style={{ flex: 1, minWidth: '260px' }}
                    required
                    disabled={creatingAccount}
                  />
                  <datalist id={customerListId}>
                    {DEMO_CUSTOMERS.map((email) => (
                      <option key={email} value={email} />
                    ))}
                  </datalist>

                  <div style={{ width: '130px' }}>
                    <input
                      type="text"
                      value="INR (₹)"
                      readOnly
                      aria-label="Account Currency"
                      className="input-field"
                      style={{ backgroundColor: '#F3F4F6', color: '#6B7280', cursor: 'not-allowed', textAlign: 'center' }}
                    />
                  </div>

                  <button
                    type="submit"
                    className="btn btn-primary"
                    disabled={creatingAccount || !targetEmail.trim()}
                    style={{ minWidth: '180px' }}
                  >
                    {creatingAccount ? 'Creating Account...' : 'Create & Assign'}
                  </button>
                </div>

                {/* Quick Select demo customer chips */}
                <div>
                  <span style={{ fontSize: '12px', color: '#6B7280', marginRight: '6px' }}>Quick select:</span>
                  <div style={{ display: 'inline-flex', flexWrap: 'wrap', gap: '6px' }}>
                    {DEMO_CUSTOMERS.slice(0, 8).map((email) => (
                      <button
                        key={email}
                        type="button"
                        onClick={() => setTargetEmail(email)}
                        disabled={creatingAccount}
                        style={{
                          padding: '2px 8px',
                          fontSize: '11px',
                          borderRadius: '4px',
                          border: '1px solid #D1D5DB',
                          backgroundColor: targetEmail === email ? '#E0F2FE' : '#FFFFFF',
                          color: targetEmail === email ? '#0369A1' : '#374151',
                          cursor: 'pointer'
                        }}
                      >
                        {email.split('@')[0]}
                      </button>
                    ))}
                  </div>
                </div>

                {/* Error Banner */}
                {createAccountError && (
                  <div style={{ backgroundColor: '#FEF2F2', border: '1px solid #FECACA', borderRadius: '6px', padding: '10px 14px', color: '#991B1B', fontSize: '13px' }}>
                    <strong>Error:</strong> {createAccountError}
                  </div>
                )}

                {/* Success Card */}
                {createAccountSuccess && (
                  <div style={{ backgroundColor: '#ECFDF5', border: '1px solid #A7F3D0', borderRadius: '8px', padding: '14px', color: '#065F46' }}>
                    <strong style={{ fontSize: '13px', color: '#047857' }}>
                      ✓ Account {createAccountSuccess.accountNumber} successfully assigned to {createAccountSuccess.ownerEmail}!
                    </strong>
                  </div>
                )}
              </form>
            </div>

            {/* Accounts Directory Table */}
            <div className="card">
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px', flexWrap: 'wrap', gap: '12px' }}>
                <div>
                  <h2 style={{ fontSize: '16px', fontWeight: 600, color: '#111827', margin: 0 }}>
                    Customer Accounts Directory & Lifecycle Control
                  </h2>
                  <p style={{ fontSize: '13px', color: '#6B7280', margin: '4px 0 0 0' }}>
                    Manage customer accounts: inspect balances, freeze accounts, or reactivate them.
                  </p>
                </div>

                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
                  <select
                    value={accountScope}
                    onChange={(e) => setAccountScope(e.target.value as 'all' | 'demo' | 'dev')}
                    aria-label="Filter accounts scope"
                    className="input-field"
                    style={{ fontSize: '13px', padding: '6px 10px', width: 'auto' }}
                  >
                    <option value="all">All Accounts ({accounts.length})</option>
                    <option value="demo">Demonstration Dataset ({demoAccountsCount})</option>
                    <option value="dev">Pre-Existing / Dev ({accounts.length - demoAccountsCount})</option>
                  </select>
                  <input
                    type="text"
                    value={searchAccountQuery}
                    onChange={(e) => setSearchAccountQuery(e.target.value)}
                    placeholder="Search accounts or owner..."
                    aria-label="Filter customer accounts by email or account number"
                    className="input-field"
                    style={{ fontSize: '13px', padding: '6px 10px', width: '200px' }}
                  />
                  <button
                    type="button"
                    className="btn btn-secondary"
                    onClick={fetchAccounts}
                    disabled={accountsLoading}
                    style={{ fontSize: '12px', padding: '6px 12px' }}
                  >
                    Refresh
                  </button>
                </div>
              </div>

              {accountsLoading && accounts.length === 0 ? (
                <div style={{ padding: '32px', textAlign: 'center', color: '#6B7280', fontSize: '14px' }}>Loading accounts...</div>
              ) : accountsError ? (
                <div style={{ backgroundColor: '#FEF2F2', border: '1px solid #FECACA', borderRadius: '6px', padding: '12px', color: '#991B1B', fontSize: '13px' }}>{accountsError}</div>
              ) : (
                <div style={{ overflowX: 'auto' }}>
                  <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '13px' }}>
                    <thead>
                      <tr style={{ borderBottom: '1px solid #E5E7EB', textAlign: 'left', color: '#4B5563', backgroundColor: '#F9FAFB' }}>
                        <th style={{ padding: '10px 14px', fontWeight: 600 }}>Account Number</th>
                        <th style={{ padding: '10px 14px', fontWeight: 600 }}>Assigned Owner</th>
                        <th style={{ padding: '10px 14px', fontWeight: 600 }}>Balance</th>
                        <th style={{ padding: '10px 14px', fontWeight: 600 }}>Status</th>
                        <th style={{ padding: '10px 14px', fontWeight: 600 }}>Opened Date</th>
                        <th style={{ padding: '10px 14px', fontWeight: 600 }}>Lifecycle Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {filteredAccounts.map((acc) => (
                        <tr key={acc.id} style={{ borderBottom: '1px solid #F3F4F6' }}>
                          <td style={{ padding: '10px 14px', fontFamily: 'monospace', fontWeight: 600, color: '#111827' }}>
                            {acc.accountNumber}
                          </td>
                          <td style={{ padding: '10px 14px', color: '#374151' }}>
                            {acc.ownerEmail || '—'}
                            {isDemoAccount(acc.ownerEmail) ? (
                              <span style={{ marginLeft: '6px', fontSize: '10px', backgroundColor: '#E0F2FE', color: '#0369A1', padding: '1px 6px', borderRadius: '4px', fontWeight: 600 }}>
                                Demo
                              </span>
                            ) : (
                              <span style={{ marginLeft: '6px', fontSize: '10px', backgroundColor: '#F3F4F6', color: '#6B7280', padding: '1px 6px', borderRadius: '4px' }}>
                                Dev
                              </span>
                            )}
                          </td>
                          <td style={{ padding: '10px 14px', fontWeight: 600, color: '#111827' }}>
                            {formatCurrency(acc.balance, acc.currency)}
                          </td>
                          <td style={{ padding: '10px 14px' }}>
                            <span
                              className={
                                acc.status === 'ACTIVE'
                                  ? 'badge badge-green'
                                  : acc.status === 'FROZEN'
                                  ? 'badge badge-amber'
                                  : 'badge badge-red'
                              }
                              style={{ fontSize: '11px' }}
                            >
                              {acc.status}
                            </span>
                          </td>
                          <td style={{ padding: '10px 14px', color: '#6B7280' }}>
                            {formatDate(acc.createdAt, true)}
                          </td>
                          <td style={{ padding: '10px 14px' }}>
                            <div style={{ display: 'flex', gap: '6px' }}>
                              {acc.status === 'ACTIVE' && (
                                <button
                                  type="button"
                                  onClick={() => handleUpdateAccountStatus(acc.id, 'FROZEN')}
                                  disabled={statusUpdatingAccountId === acc.id}
                                  style={{
                                    padding: '3px 8px',
                                    fontSize: '11px',
                                    borderRadius: '4px',
                                    border: '1px solid #FCD34D',
                                    backgroundColor: '#FEF3C7',
                                    color: '#92400E',
                                    cursor: 'pointer'
                                  }}
                                >
                                  {statusUpdatingAccountId === acc.id ? 'Updating...' : 'Freeze'}
                                </button>
                              )}
                              {acc.status === 'FROZEN' && (
                                <button
                                  type="button"
                                  onClick={() => handleUpdateAccountStatus(acc.id, 'ACTIVE')}
                                  disabled={statusUpdatingAccountId === acc.id}
                                  style={{
                                    padding: '3px 8px',
                                    fontSize: '11px',
                                    borderRadius: '4px',
                                    border: '1px solid #A7F3D0',
                                    backgroundColor: '#ECFDF5',
                                    color: '#065F46',
                                    cursor: 'pointer'
                                  }}
                                >
                                  {statusUpdatingAccountId === acc.id ? 'Updating...' : 'Unfreeze'}
                                </button>
                              )}
                              {acc.status !== 'CLOSED' && (
                                <button
                                  type="button"
                                  onClick={() => {
                                    if (confirm(`Are you sure you want to close account ${acc.accountNumber}?`)) {
                                      handleUpdateAccountStatus(acc.id, 'CLOSED');
                                    }
                                  }}
                                  disabled={statusUpdatingAccountId === acc.id}
                                  style={{
                                    padding: '3px 8px',
                                    fontSize: '11px',
                                    borderRadius: '4px',
                                    border: '1px solid #FECACA',
                                    backgroundColor: '#FEF2F2',
                                    color: '#991B1B',
                                    cursor: 'pointer'
                                  }}
                                >
                                  Close
                                </button>
                              )}
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </div>
        )}

        {/* TAB 3: USER & ROLE MANAGEMENT */}
        {activeTab === 'users' && (
          <div className="card">
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px', flexWrap: 'wrap', gap: '12px' }}>
              <div>
                <h2 style={{ fontSize: '16px', fontWeight: 600, color: '#111827', margin: 0 }}>
                  User Identity Management & Security Status
                </h2>
                <p style={{ fontSize: '13px', color: '#6B7280', margin: '4px 0 0 0' }}>
                  Inspect registered users across all roles, view assigned accounts count, and manage active status.
                </p>
              </div>

              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
                <select
                  value={userScope}
                  onChange={(e) => setUserScope(e.target.value as 'all' | 'demo' | 'dev')}
                  aria-label="Filter users scope"
                  className="input-field"
                  style={{ fontSize: '13px', padding: '6px 10px', width: 'auto' }}
                >
                  <option value="all">All Users ({users.length})</option>
                  <option value="demo">Demonstration Dataset ({demoUsersCount})</option>
                  <option value="dev">Pre-Existing / Dev ({users.length - demoUsersCount})</option>
                </select>
                <input
                  type="text"
                  value={searchUserQuery}
                  onChange={(e) => setSearchUserQuery(e.target.value)}
                  placeholder="Search user email or role..."
                  aria-label="Filter users by email or role"
                  className="input-field"
                  style={{ fontSize: '13px', padding: '6px 10px', width: '200px' }}
                />
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={fetchUsers}
                  disabled={usersLoading}
                  style={{ fontSize: '12px', padding: '6px 12px' }}
                >
                  Refresh
                </button>
              </div>
            </div>

            {usersLoading && users.length === 0 ? (
              <div style={{ padding: '32px', textAlign: 'center', color: '#6B7280', fontSize: '14px' }}>Loading users...</div>
            ) : usersError ? (
              <div style={{ backgroundColor: '#FEF2F2', border: '1px solid #FECACA', borderRadius: '6px', padding: '12px', color: '#991B1B', fontSize: '13px' }}>{usersError}</div>
            ) : (
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '13px' }}>
                  <thead>
                    <tr style={{ borderBottom: '1px solid #E5E7EB', textAlign: 'left', color: '#4B5563', backgroundColor: '#F9FAFB' }}>
                      <th style={{ padding: '10px 14px', fontWeight: 600 }}>Email Address</th>
                      <th style={{ padding: '10px 14px', fontWeight: 600 }}>System Role</th>
                      <th style={{ padding: '10px 14px', fontWeight: 600 }}>Accounts Owned</th>
                      <th style={{ padding: '10px 14px', fontWeight: 600 }}>Status</th>
                      <th style={{ padding: '10px 14px', fontWeight: 600 }}>Registered Date</th>
                      <th style={{ padding: '10px 14px', fontWeight: 600 }}>Status Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    {filteredUsers.map((u) => {
                      const isSelf = user?.email.toLowerCase() === u.email.toLowerCase();
                      return (
                        <tr key={u.id} style={{ borderBottom: '1px solid #F3F4F6' }}>
                          <td style={{ padding: '10px 14px', fontWeight: 600, color: '#111827' }}>
                            {u.email} {isSelf && <span style={{ fontSize: '11px', color: '#0D6E64' }}>(You)</span>}
                            {isDemoUser(u.email) ? (
                              <span style={{ marginLeft: '6px', fontSize: '10px', backgroundColor: '#E0F2FE', color: '#0369A1', padding: '1px 6px', borderRadius: '4px', fontWeight: 600 }}>
                                Demo
                              </span>
                            ) : (
                              <span style={{ marginLeft: '6px', fontSize: '10px', backgroundColor: '#F3F4F6', color: '#6B7280', padding: '1px 6px', borderRadius: '4px' }}>
                                Dev
                              </span>
                            )}
                          </td>
                          <td style={{ padding: '10px 14px' }}>
                            <span
                              className={
                                u.role === 'ADMIN'
                                  ? 'badge badge-purple'
                                  : u.role === 'AUDITOR'
                                  ? 'badge badge-amber'
                                  : 'badge badge-blue'
                              }
                              style={{ fontSize: '11px' }}
                            >
                              {u.role}
                            </span>
                          </td>
                          <td style={{ padding: '10px 14px', color: '#374151' }}>
                            {u.accountCount} {u.accountCount === 1 ? 'account' : 'accounts'}
                          </td>
                          <td style={{ padding: '10px 14px' }}>
                            <span
                              className={u.status === 'ACTIVE' ? 'badge badge-green' : 'badge badge-red'}
                              style={{ fontSize: '11px' }}
                            >
                              {u.status}
                            </span>
                          </td>
                          <td style={{ padding: '10px 14px', color: '#6B7280' }}>
                            {formatDate(u.createdAt, true)}
                          </td>
                          <td style={{ padding: '10px 14px' }}>
                            {!isSelf && (
                              <div>
                                {u.status === 'ACTIVE' ? (
                                  <button
                                    type="button"
                                    onClick={() => handleUpdateUserStatus(u.id, 'DISABLED')}
                                    disabled={statusUpdatingUserId === u.id}
                                    style={{
                                      padding: '3px 8px',
                                      fontSize: '11px',
                                      borderRadius: '4px',
                                      border: '1px solid #FECACA',
                                      backgroundColor: '#FEF2F2',
                                      color: '#991B1B',
                                      cursor: 'pointer'
                                    }}
                                  >
                                    {statusUpdatingUserId === u.id ? 'Updating...' : 'Suspend / Disable'}
                                  </button>
                                ) : (
                                  <button
                                    type="button"
                                    onClick={() => handleUpdateUserStatus(u.id, 'ACTIVE')}
                                    disabled={statusUpdatingUserId === u.id}
                                    style={{
                                      padding: '3px 8px',
                                      fontSize: '11px',
                                      borderRadius: '4px',
                                      border: '1px solid #A7F3D0',
                                      backgroundColor: '#ECFDF5',
                                      color: '#065F46',
                                      cursor: 'pointer'
                                    }}
                                  >
                                    {statusUpdatingUserId === u.id ? 'Updating...' : 'Activate User'}
                                  </button>
                                )}
                              </div>
                            )}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}

        {/* TAB 4: BANK-WIDE TRANSACTIONS */}
        {activeTab === 'transactions' && (
          <div className="card">
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px', flexWrap: 'wrap', gap: '12px' }}>
              <div>
                <h2 style={{ fontSize: '16px', fontWeight: 600, color: '#111827', margin: 0 }}>
                  Bank-Wide Ledger Transactions
                </h2>
                <p style={{ fontSize: '13px', color: '#6B7280', margin: '4px 0 0 0' }}>
                  Authoritative double-entry ledger transactions across all customers and bank accounts.
                </p>
              </div>

              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <select
                  value={filterAccountId}
                  onChange={(e) => {
                    const acc = e.target.value;
                    setFilterAccountId(acc);
                    fetchTransactions(acc);
                  }}
                  className="input-field"
                  aria-label="Filter by Customer Account"
                  style={{ fontSize: '13px', padding: '6px 10px', width: '220px' }}
                >
                  <option value="">All Accounts</option>
                  {accounts.map((a) => (
                    <option key={a.id} value={a.id}>
                      {a.accountNumber} ({a.ownerEmail})
                    </option>
                  ))}
                </select>
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={() => fetchTransactions(filterAccountId)}
                  disabled={transactionsLoading}
                  style={{ fontSize: '12px', padding: '6px 12px' }}
                >
                  Refresh
                </button>
              </div>
            </div>

            {transactionsLoading && transactions.length === 0 ? (
              <div style={{ padding: '32px', textAlign: 'center', color: '#6B7280', fontSize: '14px' }}>Loading transactions...</div>
            ) : transactionsError ? (
              <div style={{ backgroundColor: '#FEF2F2', border: '1px solid #FECACA', borderRadius: '6px', padding: '12px', color: '#991B1B', fontSize: '13px' }}>{transactionsError}</div>
            ) : transactions.length === 0 ? (
              <div style={{ padding: '32px', textAlign: 'center', color: '#6B7280', fontSize: '14px' }}>No transactions recorded yet.</div>
            ) : (
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '13px' }}>
                  <thead>
                    <tr style={{ borderBottom: '1px solid #E5E7EB', textAlign: 'left', color: '#4B5563', backgroundColor: '#F9FAFB' }}>
                      <th style={{ padding: '10px 14px', fontWeight: 600 }}>Type</th>
                      <th style={{ padding: '10px 14px', fontWeight: 600 }}>Status</th>
                      <th style={{ padding: '10px 14px', fontWeight: 600 }}>Date</th>
                      <th style={{ padding: '10px 14px', fontWeight: 600 }}>Debits (Asset / Source)</th>
                      <th style={{ padding: '10px 14px', fontWeight: 600 }}>Credits (Liability / Destination)</th>
                    </tr>
                  </thead>
                  <tbody>
                    {transactions.map((tx) => {
                      const debits = tx.entries.filter((e) => e.entryType === 'DEBIT');
                      const credits = tx.entries.filter((e) => e.entryType === 'CREDIT');
                      return (
                        <tr key={tx.id} style={{ borderBottom: '1px solid #F3F4F6' }}>
                          <td style={{ padding: '10px 14px', fontWeight: 600, color: '#111827' }}>
                            <span className="badge badge-blue" style={{ fontSize: '11px' }}>{tx.transactionType}</span>
                          </td>
                          <td style={{ padding: '10px 14px' }}>
                            <span className="badge badge-green" style={{ fontSize: '11px' }}>{tx.status}</span>
                          </td>
                          <td style={{ padding: '10px 14px', color: '#6B7280' }}>
                            {formatDate(tx.createdAt, true)}
                          </td>
                          <td style={{ padding: '10px 14px' }}>
                            {debits.map((d) => (
                              <div key={d.id} style={{ fontFamily: 'monospace', fontSize: '12px' }}>
                                {d.accountNumber}: <strong>{formatCurrency(d.amount, d.currency)}</strong>
                              </div>
                            ))}
                          </td>
                          <td style={{ padding: '10px 14px' }}>
                            {credits.map((c) => (
                              <div key={c.id} style={{ fontFamily: 'monospace', fontSize: '12px', color: '#047857' }}>
                                {c.accountNumber}: <strong>{formatCurrency(c.amount, c.currency)}</strong>
                              </div>
                            ))}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}

        {/* TAB 5: ADMINISTRATIVE CREDIT / DEPOSIT */}
        {activeTab === 'deposit' && (
          <div className="card" style={{ maxWidth: '640px', margin: '0 auto' }}>
            <div style={{ marginBottom: '16px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '4px' }}>
                <span className="badge badge-purple" style={{ fontSize: '11px', textTransform: 'uppercase' }}>
                  Administrative Tool
                </span>
                <h2 style={{ fontSize: '16px', fontWeight: 600, color: '#111827', margin: 0 }}>
                  Credit Customer Account (Administrative Deposit)
                </h2>
              </div>
              <p style={{ fontSize: '13px', color: '#6B7280', margin: 0 }}>
                Directly deposit/credit funds from the system cash vault into any active customer deposit account.
              </p>
            </div>

            <form onSubmit={handleAdminDeposit} style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
              <div>
                <label htmlFor="admin-target-account" style={{ display: 'block', fontSize: '13px', fontWeight: 500, color: '#374151', marginBottom: '6px' }}>
                  Target Customer Account
                </label>
                <select
                  id="admin-target-account"
                  value={depositAccountId}
                  onChange={(e) => setDepositAccountId(e.target.value)}
                  className="input-field"
                  style={{ width: '100%' }}
                  required
                  disabled={depositing}
                >
                  <option value="">Select an account...</option>
                  {accounts.map((a) => (
                    <option key={a.id} value={a.id}>
                      {a.accountNumber} — {a.ownerEmail} (Balance: {formatCurrency(a.balance, a.currency)}) [{a.status}]
                    </option>
                  ))}
                </select>
              </div>

              <div>
                <label htmlFor="admin-deposit-amount" style={{ display: 'block', fontSize: '13px', fontWeight: 500, color: '#374151', marginBottom: '6px' }}>
                  Deposit Amount (INR ₹)
                </label>
                <input
                  id="admin-deposit-amount"
                  type="number"
                  step="0.01"
                  min="0.01"
                  value={depositAmount}
                  onChange={(e) => setDepositAmount(e.target.value)}
                  placeholder="e.g. 1000.00"
                  className="input-field"
                  style={{ width: '100%' }}
                  required
                  disabled={depositing}
                />
              </div>

              {depositError && (
                <div style={{ backgroundColor: '#FEF2F2', border: '1px solid #FECACA', borderRadius: '6px', padding: '10px 14px', color: '#991B1B', fontSize: '13px' }}>
                  <strong>Error:</strong> {depositError}
                </div>
              )}

              {depositSuccess && (
                <div style={{ backgroundColor: '#ECFDF5', border: '1px solid #A7F3D0', borderRadius: '8px', padding: '14px', color: '#065F46' }}>
                  <strong style={{ fontSize: '13px', color: '#047857' }}>
                    ✓ Administrative deposit of {formatCurrency(depositSuccess.entries[0]?.amount || 0)} completed!
                  </strong>
                  <div style={{ fontSize: '12px', marginTop: '4px' }}>
                    Transaction ID: <span style={{ fontFamily: 'monospace' }}>{depositSuccess.id}</span>
                  </div>
                </div>
              )}

              <button
                type="submit"
                className="btn btn-primary"
                disabled={depositing || !depositAmount || !depositAccountId}
                style={{ alignSelf: 'flex-start' }}
              >
                {depositing ? 'Processing Deposit...' : 'Disburse / Deposit Funds'}
              </button>
            </form>
          </div>
        )}
      </main>
    </div>
  );
};
