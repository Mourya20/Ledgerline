import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { accountsApi } from '../api/accounts';
import type { AccountResponse } from '../api/types';
import { formatCurrency, maskAccountNumber, formatDate } from '../utils/formatters';
import { ApiException } from '../api/client';

export const AccountsPage: React.FC = () => {
  const { user } = useAuth();
  const navigate = useNavigate();

  const [accounts, setAccounts] = useState<AccountResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [isCreatingAccount, setIsCreatingAccount] = useState(false);
  const [showCreateModal, setShowCreateModal] = useState(false);

  // Account closure / deletion state
  const [accountToClose, setAccountToClose] = useState<AccountResponse | null>(null);
  const [isClosingAccount, setIsClosingAccount] = useState(false);

  // Filter state
  const [statusFilter, setStatusFilter] = useState<'ALL' | 'ACTIVE' | 'CLOSED'>('ALL');

  const fetchAccounts = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await accountsApi.getAccounts();
      setAccounts(data);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to load accounts';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchAccounts();
  }, []);

  const handleCreateAccount = async () => {
    try {
      setIsCreatingAccount(true);
      setError(null);
      setSuccessMessage(null);
      const newAcc = await accountsApi.createAccount({ currency: 'INR' });
      setShowCreateModal(false);
      setSuccessMessage(`New account ${maskAccountNumber(newAcc.accountNumber)} created successfully.`);
      await fetchAccounts();
    } catch (err: unknown) {
      if (err instanceof ApiException) {
        setError(err.message || 'Failed to create account.');
      } else {
        setError('Network error while creating account.');
      }
    } finally {
      setIsCreatingAccount(false);
    }
  };

  const handleCloseAccount = async () => {
    if (!accountToClose) return;

    try {
      setIsClosingAccount(true);
      setError(null);
      setSuccessMessage(null);

      const closed = await accountsApi.closeAccount(accountToClose.id);
      setAccountToClose(null);
      setSuccessMessage(`Account ${maskAccountNumber(closed.accountNumber)} has been closed successfully.`);
      await fetchAccounts();
    } catch (err: unknown) {
      if (err instanceof ApiException) {
        setError(err.message || 'Failed to close account.');
      } else {
        setError(err instanceof Error ? err.message : 'Failed to close account.');
      }
    } finally {
      setIsClosingAccount(false);
    }
  };

  const activeAccountsCount = accounts.filter((a) => a.status === 'ACTIVE').length;
  const closedAccountsCount = accounts.filter((a) => a.status === 'CLOSED').length;

  const filteredAccounts = accounts.filter((acc) => {
    if (statusFilter === 'ACTIVE') return acc.status === 'ACTIVE';
    if (statusFilter === 'CLOSED') return acc.status === 'CLOSED';
    return true;
  });

  return (
    <div>
      {/* Top Header Row */}
      <div style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: '28px',
        flexWrap: 'wrap',
        gap: '16px'
      }}>
        <div>
          <h1 style={{ fontSize: '26px', fontWeight: 700, color: '#111827', margin: 0 }}>
            Account Management
          </h1>
          <p style={{ fontSize: '14px', color: '#6B7280', margin: '4px 0 0 0' }}>
            Open, manage, monitor, and close your Vaultora bank accounts denominated in INR.
          </p>
        </div>
        <button
          className="btn-primary"
          onClick={() => setShowCreateModal(true)}
        >
          + Open new account
        </button>
      </div>

      {successMessage && (
        <div className="alert alert-success" role="alert" style={{ marginBottom: '20px' }}>
          {successMessage}
        </div>
      )}

      {error && (
        <div className="alert alert-danger" role="alert" style={{ marginBottom: '20px' }}>
          {error}
        </div>
      )}

      {/* Filter Tabs */}
      <div style={{
        display: 'flex',
        gap: '8px',
        marginBottom: '24px',
        borderBottom: '1px solid #E5E7EB',
        paddingBottom: '2px'
      }}>
        <button
          type="button"
          onClick={() => setStatusFilter('ALL')}
          style={{
            padding: '8px 16px',
            fontSize: '13px',
            fontWeight: statusFilter === 'ALL' ? 600 : 500,
            color: statusFilter === 'ALL' ? '#0D6E64' : '#6B7280',
            borderBottom: statusFilter === 'ALL' ? '2px solid #0D6E64' : '2px solid transparent',
            backgroundColor: 'transparent',
            borderTop: 'none',
            borderLeft: 'none',
            borderRight: 'none',
            cursor: 'pointer'
          }}
        >
          All Accounts ({accounts.length})
        </button>
        <button
          type="button"
          onClick={() => setStatusFilter('ACTIVE')}
          style={{
            padding: '8px 16px',
            fontSize: '13px',
            fontWeight: statusFilter === 'ACTIVE' ? 600 : 500,
            color: statusFilter === 'ACTIVE' ? '#0D6E64' : '#6B7280',
            borderBottom: statusFilter === 'ACTIVE' ? '2px solid #0D6E64' : '2px solid transparent',
            backgroundColor: 'transparent',
            borderTop: 'none',
            borderLeft: 'none',
            borderRight: 'none',
            cursor: 'pointer'
          }}
        >
          Active ({activeAccountsCount})
        </button>
        {closedAccountsCount > 0 && (
          <button
            type="button"
            onClick={() => setStatusFilter('CLOSED')}
            style={{
              padding: '8px 16px',
              fontSize: '13px',
              fontWeight: statusFilter === 'CLOSED' ? 600 : 500,
              color: statusFilter === 'CLOSED' ? '#0D6E64' : '#6B7280',
              borderBottom: statusFilter === 'CLOSED' ? '2px solid #0D6E64' : '2px solid transparent',
              backgroundColor: 'transparent',
              borderTop: 'none',
              borderLeft: 'none',
              borderRight: 'none',
              cursor: 'pointer'
            }}
          >
            Closed ({closedAccountsCount})
          </button>
        )}
      </div>

      {loading ? (
        <div style={{ padding: '32px 0', color: '#6B7280' }}>Loading accounts...</div>
      ) : (
        <>
          {/* Account Cards Grid */}
          <div style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))',
            gap: '20px',
            marginBottom: '40px'
          }}>
            {filteredAccounts.map((account) => {
              const isZeroBalance = Number(account.balance || 0) === 0;
              const isClosed = account.status === 'CLOSED';
              const isFrozen = account.status === 'FROZEN';

              return (
                <div
                  key={account.id}
                  className="card"
                  style={{
                    display: 'flex',
                    flexDirection: 'column',
                    opacity: isClosed ? 0.75 : 1,
                    backgroundColor: isClosed ? '#F9FAFB' : '#FFFFFF',
                    border: isClosed ? '1px dashed #D1D5DB' : '1px solid #E5E7EB',
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '14px' }}>
                    <span
                      style={{
                        fontSize: '11px',
                        fontWeight: 600,
                        padding: '2px 8px',
                        borderRadius: '9999px',
                        backgroundColor: isClosed ? '#F3F4F6' : isFrozen ? '#FEF3C7' : '#DCFCE7',
                        color: isClosed ? '#6B7280' : isFrozen ? '#92400E' : '#15803D',
                        border: isClosed ? '1px solid #E5E7EB' : isFrozen ? '1px solid #FCD34D' : '1px solid #BBF7D0',
                      }}
                    >
                      {account.status === 'ACTIVE' ? 'Active' : account.status === 'CLOSED' ? 'Closed' : account.status}
                    </span>
                    <span style={{ fontSize: '11px', color: '#9CA3AF' }}>
                      Opened {formatDate(account.createdAt, false)}
                    </span>
                  </div>

                  <div style={{ fontSize: '17px', fontWeight: 600, color: '#111827', marginBottom: '4px' }}>
                    Account {maskAccountNumber(account.accountNumber)}
                  </div>

                  <div style={{ fontSize: '13px', color: '#6B7280', marginBottom: '16px' }}>
                    Account number {maskAccountNumber(account.accountNumber)}
                  </div>

                  <div style={{ fontSize: '28px', fontWeight: 700, color: isClosed ? '#6B7280' : '#111827', marginBottom: '20px' }}>
                    {formatCurrency(Number(account.balance || 0), account.currency)}
                  </div>

                  {/* Account Action Buttons */}
                  <div style={{ display: 'flex', gap: '8px', marginTop: 'auto', flexWrap: 'wrap' }}>
                    <button
                      className="btn-outline"
                      style={{ flex: 1, padding: '8px 10px', fontSize: '13px', minWidth: '80px' }}
                      onClick={() => navigate(`/history?accountId=${account.id}`)}
                    >
                      Statement
                    </button>

                    {!isClosed && (
                      <button
                        className="btn-outline"
                        style={{
                          flex: 1,
                          padding: '8px 10px',
                          fontSize: '13px',
                          minWidth: '80px',
                          opacity: isFrozen ? 0.5 : 1,
                          cursor: isFrozen ? 'not-allowed' : 'pointer'
                        }}
                        disabled={isFrozen}
                        onClick={() => navigate(`/move-money?tab=transfer&sourceAccountId=${account.id}`)}
                      >
                        Transfer
                      </button>
                    )}

                    {!isClosed && (
                      <button
                        type="button"
                        className="btn-outline"
                        style={{
                          padding: '8px 10px',
                          fontSize: '13px',
                          color: isZeroBalance ? '#DC2626' : '#9CA3AF',
                          borderColor: isZeroBalance ? '#FECACA' : '#E5E7EB',
                          backgroundColor: isZeroBalance ? '#FEF2F2' : '#F9FAFB',
                          cursor: isZeroBalance ? 'pointer' : 'help'
                        }}
                        title={
                          isZeroBalance
                            ? 'Close this account'
                            : 'Account balance must be ₹0.00 to close. Please transfer or withdraw all funds first.'
                        }
                        onClick={() => {
                          if (isZeroBalance) {
                            setAccountToClose(account);
                          } else {
                            setError(
                              `Cannot close account ${maskAccountNumber(account.accountNumber)} with an active balance of ${formatCurrency(
                                Number(account.balance || 0),
                                account.currency
                              )}. Please transfer or withdraw all funds first.`
                            );
                          }
                        }}
                      >
                        Close
                      </button>
                    )}
                  </div>
                </div>
              );
            })}

            {filteredAccounts.length === 0 && (
              <div className="card" style={{ gridColumn: '1 / -1', textAlign: 'center', padding: '40px 20px' }}>
                <p style={{ color: '#4B5563', marginBottom: '16px' }}>
                  {statusFilter === 'CLOSED'
                    ? 'You do not have any closed accounts.'
                    : 'You currently do not have any active bank accounts.'}
                </p>
                {statusFilter !== 'CLOSED' && (
                  <button
                    className="btn-primary"
                    onClick={() => setShowCreateModal(true)}
                  >
                    Open your first account
                  </button>
                )}
              </div>
            )}
          </div>

          {/* Profile Card */}
          <div className="card" style={{ maxWidth: '600px' }}>
            <h2 style={{ fontSize: '18px', fontWeight: 600, color: '#111827', marginBottom: '20px' }}>
              Profile & Ownership Information
            </h2>

            <div className="form-group">
              <label className="form-label">Email</label>
              <input
                type="email"
                className="form-input"
                value={user?.email || ''}
                readOnly
                disabled
              />
            </div>

            <div className="form-group">
              <label className="form-label">User ID</label>
              <input
                type="text"
                className="form-input"
                value={user?.userId || ''}
                readOnly
                disabled
              />
            </div>

            <div style={{
              fontSize: '12px',
              color: '#6B7280',
              marginTop: '4px',
              borderTop: '1px solid #F3F4F6',
              paddingTop: '12px'
            }}>
              Role: <strong>{user?.role || 'CUSTOMER'}</strong> &bull; Total Accounts: <strong>{accounts.length}</strong> (Active: {activeAccountsCount}, Closed: {closedAccountsCount})
            </div>
          </div>
        </>
      )}

      {/* Account Creation Modal */}
      {showCreateModal && (
        <div style={{
          position: 'fixed',
          top: 0,
          left: 0,
          right: 0,
          bottom: 0,
          backgroundColor: 'rgba(0, 0, 0, 0.4)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          padding: '20px',
          zIndex: 1000
        }}>
          <div className="card" style={{ width: '100%', maxWidth: '420px', padding: '28px' }}>
            <h3 style={{ fontSize: '18px', fontWeight: 700, color: '#111827', marginBottom: '12px' }}>
              Open New Customer Account
            </h3>
            <p style={{ fontSize: '14px', color: '#4B5563', marginBottom: '20px', lineHeight: 1.5 }}>
              This will create a new standard customer account denominated in <strong>INR (₹)</strong> with an initial zero balance.
            </p>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '12px' }}>
              <button
                type="button"
                className="btn-outline"
                onClick={() => setShowCreateModal(false)}
                disabled={isCreatingAccount}
              >
                Cancel
              </button>
              <button
                type="button"
                className="btn-primary"
                onClick={handleCreateAccount}
                disabled={isCreatingAccount}
              >
                {isCreatingAccount ? 'Creating...' : 'Confirm & Open'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Account Closure / Deletion Confirmation Modal */}
      {accountToClose && (
        <div style={{
          position: 'fixed',
          top: 0,
          left: 0,
          right: 0,
          bottom: 0,
          backgroundColor: 'rgba(0, 0, 0, 0.5)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          padding: '20px',
          zIndex: 1000
        }}>
          <div className="card" style={{ width: '100%', maxWidth: '440px', padding: '28px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '14px' }}>
              <span style={{
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                width: '32px',
                height: '32px',
                borderRadius: '50%',
                backgroundColor: '#FEE2E2',
                color: '#DC2626',
                fontWeight: 700
              }}>
                !
              </span>
              <h3 style={{ fontSize: '18px', fontWeight: 700, color: '#111827', margin: 0 }}>
                Close Bank Account
              </h3>
            </div>

            <p style={{ fontSize: '14px', color: '#4B5563', marginBottom: '16px', lineHeight: 1.5 }}>
              Are you sure you want to close account <strong>{maskAccountNumber(accountToClose.accountNumber)}</strong>?
            </p>

            <div style={{
              backgroundColor: '#FEF2F2',
              border: '1px solid #FECACA',
              borderRadius: '6px',
              padding: '12px',
              marginBottom: '20px',
              fontSize: '13px',
              color: '#991B1B',
              lineHeight: 1.4
            }}>
              <strong>Important Notice:</strong> Once closed, this account can no longer receive or initiate deposits, transfers, or withdrawals. Account transaction history will remain accessible for statement inspection.
            </div>

            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '12px' }}>
              <button
                type="button"
                className="btn-outline"
                onClick={() => setAccountToClose(null)}
                disabled={isClosingAccount}
              >
                Cancel
              </button>
              <button
                type="button"
                style={{
                  backgroundColor: '#DC2626',
                  color: '#FFFFFF',
                  border: 'none',
                  borderRadius: '6px',
                  padding: '8px 16px',
                  fontWeight: 600,
                  fontSize: '14px',
                  cursor: isClosingAccount ? 'not-allowed' : 'pointer'
                }}
                onClick={handleCloseAccount}
                disabled={isClosingAccount}
              >
                {isClosingAccount ? 'Closing Account...' : 'Confirm & Close Account'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
