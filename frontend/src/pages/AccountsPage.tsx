import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { accountsApi } from '../api/accounts';
import { accountRequestsApi } from '../api/accountRequests';
import type { AccountResponse, AccountRequest } from '../api/types';
import { formatCurrency, maskAccountNumber, formatDate } from '../utils/formatters';
import { ApiException } from '../api/client';
import { ChangePasswordModal } from '../components/ChangePasswordModal';
import { DownloadStatementModal } from '../components/DownloadStatementModal';

export const AccountsPage: React.FC = () => {
  const { user } = useAuth();
  const navigate = useNavigate();

  const [accounts, setAccounts] = useState<AccountResponse[]>([]);
  const [accountRequests, setAccountRequests] = useState<AccountRequest[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  // Account Opening Request Modal state
  const [isSubmittingRequest, setIsSubmittingRequest] = useState(false);
  const [showRequestModal, setShowRequestModal] = useState(false);

  // Account closure / deletion state
  const [accountToClose, setAccountToClose] = useState<AccountResponse | null>(null);
  const [isClosingAccount, setIsClosingAccount] = useState(false);
  const [closeError, setCloseError] = useState<string | null>(null);

  // Filter state
  const [passwordModalOpen, setPasswordModalOpen] = useState(false);
  const [statementAccount, setStatementAccount] = useState<AccountResponse | null>(null);
  const [statusFilter, setStatusFilter] = useState<'ALL' | 'ACTIVE' | 'CLOSED'>('ALL');

  // Reveal/mask account number toggle state
  const [revealedAccounts, setRevealedAccounts] = useState<Record<string, boolean>>({});

  const toggleAccountReveal = (accountId: string) => {
    setRevealedAccounts((prev) => ({
      ...prev,
      [accountId]: !prev[accountId],
    }));
  };

  const fetchData = async () => {
    try {
      setLoading(true);
      setError(null);
      const [accountsData, requestsData] = await Promise.all([
        accountsApi.getAccounts(),
        accountRequestsApi.getRequests(),
      ]);
      setAccounts(accountsData);
      setAccountRequests(requestsData);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to load accounts';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
    const interval = setInterval(fetchData, 8000);
    return () => clearInterval(interval);
  }, []);

  const pendingRequests = accountRequests.filter((r) => r.status === 'PENDING');
  const hasPendingRequest = pendingRequests.length > 0;

  const handleRequestAccount = async () => {
    try {
      setIsSubmittingRequest(true);
      setError(null);
      setSuccessMessage(null);
      await accountRequestsApi.createRequest('INR');
      setShowRequestModal(false);
      setSuccessMessage(
        'Account opening request submitted successfully. An administrator must approve your request before the account is activated.'
      );
      await fetchData();
    } catch (err: unknown) {
      if (err instanceof ApiException) {
        setError(err.message || 'Failed to submit account request.');
      } else {
        setError('Network error while submitting account request.');
      }
    } finally {
      setIsSubmittingRequest(false);
    }
  };

  const handleCloseAccount = async () => {
    if (!accountToClose) return;

    try {
      setIsClosingAccount(true);
      setError(null);
      setCloseError(null);
      setSuccessMessage(null);

      const closed = await accountsApi.closeAccount(accountToClose.id);
      setAccountToClose(null);
      setSuccessMessage(`Account ${maskAccountNumber(closed.accountNumber)} has been closed successfully.`);
      await fetchData();
    } catch (err: unknown) {
      const msg = err instanceof ApiException
        ? err.message || 'Failed to close account.'
        : err instanceof Error
          ? err.message
          : 'Failed to close account.';
      setCloseError(msg);
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
        <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
          <button
            type="button"
            className="btn-outline"
            onClick={() => fetchData()}
            disabled={loading}
            style={{ fontSize: '13px', padding: '8px 14px' }}
            title="Refresh accounts and request status"
          >
            ↻ Refresh
          </button>
          <button
            className="btn-primary"
            onClick={() => {
              setError(null);
              setCloseError(null);
              setShowRequestModal(true);
            }}
            disabled={hasPendingRequest}
            title={hasPendingRequest ? 'You already have an account opening request pending administrator review' : undefined}
          >
            + Open new account
          </button>
        </div>
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

      {/* Pending Account Opening Requests Section */}
      {pendingRequests.length > 0 && (
        <div style={{ marginBottom: '28px' }}>
          {pendingRequests.map((req) => (
            <div
              key={req.id}
              className="card"
              style={{
                backgroundColor: '#FFFBEB',
                border: '1px solid #FCD34D',
                marginBottom: '12px',
                padding: '16px 20px',
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                flexWrap: 'wrap',
                gap: '12px'
              }}
            >
              <div>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '6px' }}>
                  <span
                    style={{
                      fontSize: '11px',
                      fontWeight: 700,
                      padding: '3px 10px',
                      borderRadius: '9999px',
                      backgroundColor: '#FEF3C7',
                      color: '#B45309',
                      border: '1px solid #FDE68A'
                    }}
                  >
                    Awaiting Administrator Approval
                  </span>
                  <span style={{ fontSize: '12px', color: '#92400E' }}>
                    Submitted {formatDate(req.createdAt)}
                  </span>
                </div>
                <div style={{ fontSize: '14px', color: '#78350F', lineHeight: 1.4 }}>
                  Your request to open a new <strong>{req.currency}</strong> bank account is under administrator review. Once accepted, your new account will be created and displayed below automatically.
                </div>
              </div>
              <div style={{ fontSize: '13px', fontWeight: 600, color: '#92400E' }}>
                Status: Pending Approval
              </div>
            </div>
          ))}
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
                    Account {revealedAccounts[account.id] ? account.accountNumber : maskAccountNumber(account.accountNumber)}
                  </div>

                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '13px', color: '#6B7280', marginBottom: '16px' }}>
                    <span>
                      Account number{' '}
                      <strong style={{ color: '#111827', fontFamily: revealedAccounts[account.id] ? 'monospace' : 'inherit' }}>
                        {revealedAccounts[account.id] ? account.accountNumber : maskAccountNumber(account.accountNumber)}
                      </strong>
                    </span>
                    <button
                      type="button"
                      onClick={() => toggleAccountReveal(account.id)}
                      title={revealedAccounts[account.id] ? 'Hide account number' : 'View full account number'}
                      aria-label={revealedAccounts[account.id] ? 'Hide account number' : 'View full account number'}
                      style={{
                        background: 'none',
                        border: 'none',
                        padding: '2px 4px',
                        cursor: 'pointer',
                        color: revealedAccounts[account.id] ? '#0D6E64' : '#6B7280',
                        display: 'inline-flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        borderRadius: '4px',
                        lineHeight: 1
                      }}
                    >
                      {revealedAccounts[account.id] ? (
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                          <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24" />
                          <line x1="1" y1="1" x2="23" y2="23" />
                        </svg>
                      ) : (
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                          <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8z" />
                          <circle cx="12" cy="12" r="3" />
                        </svg>
                      )}
                    </button>
                  </div>

                  <div style={{ fontSize: '28px', fontWeight: 700, color: isClosed ? '#6B7280' : '#111827', marginBottom: '20px' }}>
                    {formatCurrency(Number(account.balance || 0), account.currency)}
                  </div>

                  {/* Account Action Buttons */}
                  <div style={{ display: 'flex', gap: '8px', marginTop: 'auto', flexWrap: 'wrap' }}>
                    <button
                      className="btn-outline"
                      style={{ flex: 1, padding: '8px 10px', fontSize: '13px', minWidth: '80px' }}
                      onClick={() => setStatementAccount(account)}
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
                          setError(null);
                          setCloseError(null);
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
                    onClick={() => {
                      setError(null);
                      setCloseError(null);
                      setShowRequestModal(true);
                    }}
                  >
                    Request your first account
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
            <div style={{ marginTop: '16px', display: 'flex', justifyContent: 'flex-start' }}>
              <button
                type="button"
                className="btn-outline"
                onClick={() => setPasswordModalOpen(true)}
                style={{ fontSize: '13px', padding: '6px 12px' }}
              >
                🔒 Change Password
              </button>
            </div>
          </div>
        </>
      )}

      {/* Account Opening Request Modal */}
      {showRequestModal && (
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
          <div className="card" style={{ width: '100%', maxWidth: '460px', padding: '28px' }}>
            <h3 style={{ fontSize: '18px', fontWeight: 700, color: '#111827', marginBottom: '12px' }}>
              Request New Bank Account
            </h3>
            <p style={{ fontSize: '14px', color: '#4B5563', marginBottom: '16px', lineHeight: 1.5 }}>
              Submit a request to open a new Vaultora bank account denominated in <strong>INR (₹)</strong>.
            </p>

            <div style={{
              backgroundColor: '#F0FDF4',
              border: '1px solid #BBF7D0',
              borderRadius: '6px',
              padding: '12px 14px',
              marginBottom: '20px',
              fontSize: '13px',
              color: '#166534',
              lineHeight: 1.4
            }}>
              <strong>Administrator Approval Required:</strong> In accordance with bank governance, an administrator must review and accept your account opening request before your new account is created.
            </div>

            {hasPendingRequest && (
              <div style={{
                backgroundColor: '#FEF3C7',
                border: '1px solid #FCD34D',
                borderRadius: '6px',
                padding: '12px 14px',
                marginBottom: '20px',
                fontSize: '13px',
                color: '#92400E',
                lineHeight: 1.4
              }}>
                You currently already have an account opening request pending administrator review. Please wait for your pending request to be processed.
              </div>
            )}

            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '12px' }}>
              <button
                type="button"
                className="btn-outline"
                onClick={() => setShowRequestModal(false)}
                disabled={isSubmittingRequest}
              >
                Cancel
              </button>
              <button
                type="button"
                className="btn-primary"
                onClick={handleRequestAccount}
                disabled={isSubmittingRequest || hasPendingRequest}
              >
                {isSubmittingRequest ? 'Submitting Request...' : 'Submit Request for Approval'}
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

            {closeError && (
              <div className="alert alert-danger" role="alert" style={{ marginBottom: '16px', fontSize: '13px' }}>
                {closeError}
              </div>
            )}

            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '12px' }}>
              <button
                type="button"
                className="btn-outline"
                onClick={() => {
                  setAccountToClose(null);
                  setCloseError(null);
                  setError(null);
                }}
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
      <ChangePasswordModal isOpen={passwordModalOpen} onClose={() => setPasswordModalOpen(false)} />
      <DownloadStatementModal
        isOpen={!!statementAccount}
        onClose={() => setStatementAccount(null)}
        account={statementAccount}
      />
    </div>
  );
};
