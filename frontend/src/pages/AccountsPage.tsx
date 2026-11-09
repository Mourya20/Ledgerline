import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { accountsApi } from '../api/accounts';
import type { AccountResponse } from '../api/types';
import { formatCurrency, maskAccountNumber } from '../utils/formatters';
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
        <h1 style={{ fontSize: '26px', fontWeight: 700, color: '#111827' }}>
          Accounts
        </h1>
        <button
          className="btn-primary"
          onClick={() => setShowCreateModal(true)}
        >
          Open new account
        </button>
      </div>

      {successMessage && (
        <div className="alert alert-success" role="alert">
          {successMessage}
        </div>
      )}

      {error && (
        <div className="alert alert-danger" role="alert">
          {error}
        </div>
      )}

      {loading ? (
        <div style={{ padding: '32px 0', color: '#6B7280' }}>Loading accounts...</div>
      ) : (
        <>
          {/* Account Cards Grid */}
          <div style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))',
            gap: '20px',
            marginBottom: '40px'
          }}>
            {accounts.map((account) => (
              <div key={account.id} className="card" style={{ display: 'flex', flexDirection: 'column' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '14px' }}>
                  <span className="badge-active">
                    {account.status === 'ACTIVE' ? 'Active' : account.status}
                  </span>
                </div>

                <div style={{ fontSize: '17px', fontWeight: 600, color: '#111827', marginBottom: '4px' }}>
                  Account {maskAccountNumber(account.accountNumber)}
                </div>

                <div style={{ fontSize: '13px', color: '#6B7280', marginBottom: '16px' }}>
                  Account number {maskAccountNumber(account.accountNumber)}
                </div>

                <div style={{ fontSize: '28px', fontWeight: 700, color: '#111827', marginBottom: '24px' }}>
                  {formatCurrency(Number(account.balance || 0), account.currency)}
                </div>

                <div style={{ display: 'flex', gap: '10px', marginTop: 'auto' }}>
                  <button
                    className="btn-outline"
                    style={{ flex: 1, padding: '8px 12px', fontSize: '13px' }}
                    onClick={() => navigate(`/history?accountId=${account.id}`)}
                  >
                    Statement
                  </button>
                  <button
                    className="btn-outline"
                    style={{ flex: 1, padding: '8px 12px', fontSize: '13px' }}
                    onClick={() => navigate(`/move-money?tab=transfer&sourceAccountId=${account.id}`)}
                  >
                    Transfer
                  </button>
                </div>
              </div>
            ))}

            {accounts.length === 0 && (
              <div className="card" style={{ gridColumn: '1 / -1', textAlign: 'center', padding: '40px 20px' }}>
                <p style={{ color: '#4B5563', marginBottom: '16px' }}>
                  You currently do not have any active bank accounts.
                </p>
                <button
                  className="btn-primary"
                  onClick={() => setShowCreateModal(true)}
                >
                  Open your first account
                </button>
              </div>
            )}
          </div>

          {/* Profile Card */}
          <div className="card" style={{ maxWidth: '600px' }}>
            <h2 style={{ fontSize: '18px', fontWeight: 600, color: '#111827', marginBottom: '20px' }}>
              Profile
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
              Role: <strong>{user?.role || 'CUSTOMER'}</strong> &bull; Authenticated by Vaultora banking security.
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
    </div>
  );
};
