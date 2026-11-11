import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { accountsApi } from '../api/accounts';
import { transactionsApi } from '../api/transactions';
import type { AccountResponse, TransactionResponse } from '../api/types';
import { formatCurrency, formatDate, maskAccountNumber, parseCustomerTransaction } from '../utils/formatters';

export const DashboardPage: React.FC = () => {
  const { user } = useAuth();
  const navigate = useNavigate();

  const [accounts, setAccounts] = useState<AccountResponse[]>([]);
  const [recentTransactions, setRecentTransactions] = useState<TransactionResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [revealedAccounts, setRevealedAccounts] = useState<Record<string, boolean>>({});

  const toggleAccountReveal = (accountId: string) => {
    setRevealedAccounts((prev) => ({
      ...prev,
      [accountId]: !prev[accountId],
    }));
  };

  useEffect(() => {
    let isMounted = true;

    async function loadDashboardData() {
      try {
        setLoading(true);
        setError(null);

        const [fetchedAccounts, txPage] = await Promise.all([
          accountsApi.getAccounts(),
          transactionsApi.getTransactions({ page: 0, size: 5, sort: 'createdAt,desc' }),
        ]);

        if (isMounted) {
          setAccounts(fetchedAccounts);
          setRecentTransactions(txPage.content || []);
        }
      } catch (err: unknown) {
        if (isMounted) {
          const msg = err instanceof Error ? err.message : 'Failed to load dashboard data';
          setError(msg);
        }
      } finally {
        if (isMounted) {
          setLoading(false);
        }
      }
    }

    loadDashboardData();

    return () => {
      isMounted = false;
    };
  }, []);

  const totalBalance = accounts.reduce((sum, acc) => sum + Number(acc.balance || 0), 0);
  const userAccountIds = new Set(accounts.map((a) => a.id));

  return (
    <div>
      {/* Welcome Heading */}
      <h1 style={{ fontSize: '26px', fontWeight: 700, color: '#111827', marginBottom: '28px' }}>
        Welcome back, {user?.email || 'Customer'}
      </h1>

      {error && (
        <div className="alert alert-danger" role="alert">
          {error}
        </div>
      )}

      {loading ? (
        <div style={{ padding: '32px 0', color: '#6B7280' }}>Loading your dashboard...</div>
      ) : (
        <>
          {/* Summary Cards Row */}
          <div style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))',
            gap: '20px',
            marginBottom: '28px'
          }}>
            {/* Total Balance Card */}
            <div className="card">
              <div style={{ fontSize: '13px', color: '#6B7280', fontWeight: 500, marginBottom: '8px' }}>
                Total balance
              </div>
              <div style={{ fontSize: '28px', fontWeight: 700, color: '#111827' }}>
                {formatCurrency(totalBalance)}
              </div>
            </div>

            {/* Account Cards */}
            {accounts.map((account) => (
              <div key={account.id} className="card">
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '8px' }}>
                  <span style={{ fontSize: '13px', color: '#6B7280', fontWeight: 500 }}>
                    Account {revealedAccounts[account.id] ? account.accountNumber : maskAccountNumber(account.accountNumber)}
                  </span>
                  <button
                    type="button"
                    onClick={() => toggleAccountReveal(account.id)}
                    title={revealedAccounts[account.id] ? 'Hide account number' : 'View full account number'}
                    aria-label={revealedAccounts[account.id] ? 'Hide account number' : 'View full account number'}
                    style={{
                      background: 'none',
                      border: 'none',
                      padding: '2px',
                      cursor: 'pointer',
                      color: revealedAccounts[account.id] ? '#0D6E64' : '#9CA3AF',
                      display: 'inline-flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      borderRadius: '4px'
                    }}
                  >
                    {revealedAccounts[account.id] ? (
                      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24" />
                        <line x1="1" y1="1" x2="23" y2="23" />
                      </svg>
                    ) : (
                      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8z" />
                        <circle cx="12" cy="12" r="3" />
                      </svg>
                    )}
                  </button>
                </div>
                <div style={{ fontSize: '28px', fontWeight: 700, color: '#111827' }}>
                  {formatCurrency(Number(account.balance || 0), account.currency)}
                </div>
              </div>
            ))}

            {accounts.length === 0 && (
              <div className="card" style={{ display: 'flex', flexDirection: 'column', justifyContent: 'center' }}>
                <div style={{ fontSize: '13px', color: '#6B7280', marginBottom: '8px' }}>No accounts active</div>
                <button
                  className="btn-outline"
                  style={{ alignSelf: 'flex-start', padding: '6px 14px', fontSize: '13px' }}
                  onClick={() => navigate('/accounts')}
                >
                  Create an account
                </button>
              </div>
            )}
          </div>

          {/* Quick Action Buttons */}
          <div style={{ display: 'flex', gap: '12px', marginBottom: '32px' }}>
            <button
              className="btn-primary"
              onClick={() => navigate('/move-money?tab=deposit')}
            >
              Deposit
            </button>
            <button
              className="btn-outline"
              onClick={() => navigate('/move-money?tab=withdraw')}
            >
              Withdraw
            </button>
            <button
              className="btn-outline"
              onClick={() => navigate('/move-money?tab=transfer')}
            >
              Transfer
            </button>
          </div>

          {/* Recent Transactions Card */}
          <div className="card">
            <h2 style={{ fontSize: '16px', fontWeight: 600, color: '#111827', marginBottom: '20px' }}>
              Recent transactions
            </h2>

            {recentTransactions.length === 0 ? (
              <div style={{ padding: '24px 0', textAlign: 'center', color: '#6B7280', fontSize: '14px' }}>
                No recent transactions recorded. Use the action buttons above to simulate a deposit or transfer.
              </div>
            ) : (
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left', fontSize: '14px' }}>
                  <thead>
                    <tr style={{ borderBottom: '1px solid #E5E7EB' }}>
                      <th style={{ padding: '12px 16px', color: '#6B7280', fontWeight: 500, fontSize: '13px' }}>Date</th>
                      <th style={{ padding: '12px 16px', color: '#6B7280', fontWeight: 500, fontSize: '13px' }}>Description</th>
                      <th style={{ padding: '12px 16px', color: '#6B7280', fontWeight: 500, fontSize: '13px' }}>Account</th>
                      <th style={{ padding: '12px 16px', color: '#6B7280', fontWeight: 500, fontSize: '13px' }}>Type</th>
                      <th style={{ padding: '12px 16px', color: '#6B7280', fontWeight: 500, fontSize: '13px', textAlign: 'right' }}>Amount</th>
                    </tr>
                  </thead>
                  <tbody>
                    {recentTransactions.map((tx) => {
                      const parsed = parseCustomerTransaction(tx, userAccountIds);
                      return (
                        <tr key={tx.id} style={{ borderBottom: '1px solid #F3F4F6' }}>
                          <td style={{ padding: '14px 16px', color: '#4B5563', whiteSpace: 'nowrap' }}>
                            {formatDate(parsed.rawDate)}
                          </td>
                          <td style={{ padding: '14px 16px', fontWeight: 500, color: '#111827' }}>
                            {parsed.description}
                          </td>
                          <td style={{ padding: '14px 16px', color: '#4B5563' }}>
                            {parsed.accountLabel}
                          </td>
                          <td style={{ padding: '14px 16px', color: '#6B7280' }}>
                            <span style={{
                              display: 'inline-block',
                              padding: '2px 8px',
                              borderRadius: '4px',
                              fontSize: '12px',
                              backgroundColor: '#F3F4F6',
                              color: '#374151'
                            }}>
                              {parsed.typeLabel}
                            </span>
                          </td>
                          <td style={{
                            padding: '14px 16px',
                            textAlign: 'right',
                            fontWeight: 600,
                            color: parsed.isPositive ? '#0D6E64' : '#111827',
                            whiteSpace: 'nowrap'
                          }}>
                            {parsed.formattedAmount}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      )}
    </div>
  );
};
