import React, { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { accountsApi } from '../api/accounts';
import { transactionsApi } from '../api/transactions';
import type { AccountResponse } from '../api/types';
import { ApiException } from '../api/client';
import { formatCurrency, maskAccountNumber } from '../utils/formatters';
import {
  prepareTransactionIntent,
  markIntentUncertain,
  clearStoredIntent,
  getStoredIntent,
  type DepositPayload,
  type WithdrawPayload,
  type TransferPayload,
  type BaseTransactionIntent,
} from '../utils/idempotencyManager';

export const MoveMoneyPage: React.FC = () => {
  const [searchParams] = useSearchParams();
  const initialSourceId = searchParams.get('sourceAccountId');

  const [accounts, setAccounts] = useState<AccountResponse[]>([]);
  const [loading, setLoading] = useState(true);

  // Active / Uncertain Intents loaded from sessionStorage
  const [depositIntent, setDepositIntent] = useState<BaseTransactionIntent<DepositPayload> | null>(() =>
    getStoredIntent<DepositPayload>('deposit')
  );
  const [withdrawIntent, setWithdrawIntent] = useState<BaseTransactionIntent<WithdrawPayload> | null>(() =>
    getStoredIntent<WithdrawPayload>('withdraw')
  );
  const [transferIntent, setTransferIntent] = useState<BaseTransactionIntent<TransferPayload> | null>(() =>
    getStoredIntent<TransferPayload>('transfer')
  );

  // Deposit Form State
  const [depositAccountId, setDepositAccountId] = useState(() => {
    const dep = getStoredIntent<DepositPayload>('deposit');
    return dep?.status === 'UNCERTAIN' ? dep.payload.accountId : '';
  });
  const [depositAmount, setDepositAmount] = useState(() => {
    const dep = getStoredIntent<DepositPayload>('deposit');
    return dep?.status === 'UNCERTAIN' ? dep.payload.amount.toString() : '';
  });
  const [depositLoading, setDepositLoading] = useState(false);

  // Withdraw Form State
  const [withdrawAccountId, setWithdrawAccountId] = useState(() => {
    const w = getStoredIntent<WithdrawPayload>('withdraw');
    return w?.status === 'UNCERTAIN' ? w.payload.accountId : '';
  });
  const [withdrawAmount, setWithdrawAmount] = useState(() => {
    const w = getStoredIntent<WithdrawPayload>('withdraw');
    return w?.status === 'UNCERTAIN' ? w.payload.amount.toString() : '';
  });
  const [withdrawLoading, setWithdrawLoading] = useState(false);

  // Transfer Form State
  const [transferSourceId, setTransferSourceId] = useState(() => {
    const t = getStoredIntent<TransferPayload>('transfer');
    return t?.status === 'UNCERTAIN' ? t.payload.sourceAccountId : (initialSourceId || '');
  });
  const [transferDestNumber, setTransferDestNumber] = useState(() => {
    const t = getStoredIntent<TransferPayload>('transfer');
    return t?.status === 'UNCERTAIN' ? t.payload.destinationAccountNumber : '';
  });
  const [transferAmount, setTransferAmount] = useState(() => {
    const t = getStoredIntent<TransferPayload>('transfer');
    return t?.status === 'UNCERTAIN' ? t.payload.amount.toString() : '';
  });
  const [transferLoading, setTransferLoading] = useState(false);
  const [showTransferReview, setShowTransferReview] = useState(false);
  const [isTransferRetry, setIsTransferRetry] = useState(false);

  // Notifications
  const [notification, setNotification] = useState<{ type: 'success' | 'error'; message: string } | null>(null);

  // High-Visibility Floating Confirmation Alert
  interface TransactionReceiptAlert {
    type: 'success' | 'error';
    title: string;
    message: string;
    actionType: 'Deposit' | 'Withdrawal' | 'Transfer';
    amount?: number;
    sourceAccount?: string;
    destinationAccount?: string;
    timestamp: string;
  }
  const [activeAlert, setActiveAlert] = useState<TransactionReceiptAlert | null>(null);

  useEffect(() => {
    if (!activeAlert) return;
    const timer = setTimeout(() => {
      setActiveAlert(null);
    }, 8000);
    return () => clearTimeout(timer);
  }, [activeAlert]);

  const fetchAccounts = async () => {
    try {
      const data = await accountsApi.getAccounts();
      setAccounts(data);
      if (data.length > 0) {
        setDepositAccountId((prev) => prev || data[0].id);
        setWithdrawAccountId((prev) => prev || data[0].id);
        setTransferSourceId((prev) => {
          if (prev) return prev;
          const match = initialSourceId ? data.find((a) => a.id === initialSourceId) : null;
          return match ? match.id : data[0].id;
        });
      }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to load accounts';
      setNotification({ type: 'error', message: msg });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    let ignore = false;
    async function init() {
      try {
        const data = await accountsApi.getAccounts();
        if (!ignore) {
          setAccounts(data);
          if (data.length > 0) {
            setDepositAccountId((prev) => prev || data[0].id);
            setWithdrawAccountId((prev) => prev || data[0].id);
            setTransferSourceId((prev) => {
              if (prev) return prev;
              const match = initialSourceId ? data.find((a) => a.id === initialSourceId) : null;
              return match ? match.id : data[0].id;
            });
          }
        }
      } catch (err: unknown) {
        if (!ignore) {
          const msg = err instanceof Error ? err.message : 'Failed to load accounts';
          setNotification({ type: 'error', message: msg });
        }
      } finally {
        if (!ignore) {
          setLoading(false);
        }
      }
    }
    init();
    return () => {
      ignore = true;
    };
  }, [initialSourceId]);

  const selectedWithdrawAccount = accounts.find((a) => a.id === withdrawAccountId);
  const selectedTransferSourceAccount = accounts.find((a) => a.id === transferSourceId);

  // --- Handlers ---

  const handleDeposit = async (e: React.FormEvent) => {
    e.preventDefault();
    setNotification(null);

    const amt = parseFloat(depositAmount);
    if (!depositAccountId || isNaN(amt) || amt <= 0) {
      setNotification({ type: 'error', message: 'Please enter a valid positive deposit amount.' });
      return;
    }

    const payload: DepositPayload = {
      accountId: depositAccountId,
      amount: amt,
      currency: 'INR',
    };

    let preparedIntent: BaseTransactionIntent<DepositPayload>;
    try {
      const res = prepareTransactionIntent('deposit', payload);
      preparedIntent = res.intent;
    } catch (intentErr: unknown) {
      const msg = intentErr instanceof Error ? intentErr.message : 'Unresolved deposit transaction pending.';
      setNotification({ type: 'error', message: msg });
      return;
    }

    setDepositLoading(true);
    try {
      await transactionsApi.deposit(payload, preparedIntent.key);
      clearStoredIntent('deposit');
      setDepositIntent(null);
      setDepositAmount('');
      const targetAcc = accounts.find((a) => a.id === depositAccountId);
      const successMsg = `Successfully deposited ${formatCurrency(amt)} into account ${targetAcc ? targetAcc.accountNumber : ''}.`;
      setNotification({
        type: 'success',
        message: successMsg,
      });
      setActiveAlert({
        type: 'success',
        title: 'Deposit Successful',
        message: successMsg,
        actionType: 'Deposit',
        amount: amt,
        sourceAccount: targetAcc ? targetAcc.accountNumber : undefined,
        timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
      });
      window.scrollTo({ top: 0, behavior: 'smooth' });
      await fetchAccounts();
    } catch (err: unknown) {
      if (err instanceof ApiException) {
        if (err.status === 409) {
          const errMsg = 'Idempotency conflict: this operation was already processed with different parameters.';
          setNotification({
            type: 'error',
            message: errMsg,
          });
          setActiveAlert({
            type: 'error',
            title: 'Deposit Conflict',
            message: errMsg,
            actionType: 'Deposit',
            amount: amt,
            timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
          });
        } else if (err.status >= 500) {
          markIntentUncertain('deposit', preparedIntent, err.message);
          setDepositIntent(getStoredIntent('deposit'));
          const errMsg = `Server error encountered (${err.message}). The transaction intent has been preserved. You can safely retry.`;
          setNotification({
            type: 'error',
            message: errMsg,
          });
          setActiveAlert({
            type: 'error',
            title: 'Deposit Preserved (Server Error)',
            message: errMsg,
            actionType: 'Deposit',
            amount: amt,
            timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
          });
        } else {
          // Client errors 400, 422, etc. do not commit
          clearStoredIntent('deposit');
          setDepositIntent(null);
          const errMsg = err.message || 'Deposit failed.';
          setNotification({ type: 'error', message: errMsg });
          setActiveAlert({
            type: 'error',
            title: 'Deposit Rejected',
            message: errMsg,
            actionType: 'Deposit',
            amount: amt,
            timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
          });
        }
      } else {
        // Network timeout / connection lost: UNCERTAIN
        markIntentUncertain('deposit', preparedIntent, 'Network timeout / connection lost');
        setDepositIntent(getStoredIntent('deposit'));
        const errMsg = 'Network connection lost. The deposit intent has been preserved. Please retry to safely resolve.';
        setNotification({
          type: 'error',
          message: errMsg,
        });
        setActiveAlert({
          type: 'error',
          title: 'Deposit Network Disruption',
          message: errMsg,
          actionType: 'Deposit',
          amount: amt,
          timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
        });
      }
      window.scrollTo({ top: 0, behavior: 'smooth' });
    } finally {
      setDepositLoading(false);
    }
  };

  const handleWithdraw = async (e: React.FormEvent) => {
    e.preventDefault();
    setNotification(null);

    const amt = parseFloat(withdrawAmount);
    if (!withdrawAccountId || isNaN(amt) || amt <= 0) {
      setNotification({ type: 'error', message: 'Please enter a valid positive withdrawal amount.' });
      return;
    }

    if (selectedWithdrawAccount && amt > Number(selectedWithdrawAccount.balance)) {
      setNotification({
        type: 'error',
        message: `Insufficient funds. Available balance is ${formatCurrency(Number(selectedWithdrawAccount.balance))}.`,
      });
      return;
    }

    const payload: WithdrawPayload = {
      accountId: withdrawAccountId,
      amount: amt,
      currency: 'INR',
    };

    let preparedIntent: BaseTransactionIntent<WithdrawPayload>;
    try {
      const res = prepareTransactionIntent('withdraw', payload);
      preparedIntent = res.intent;
    } catch (intentErr: unknown) {
      const msg = intentErr instanceof Error ? intentErr.message : 'Unresolved withdrawal transaction pending.';
      setNotification({ type: 'error', message: msg });
      return;
    }

    setWithdrawLoading(true);
    try {
      await transactionsApi.withdraw(payload, preparedIntent.key);
      clearStoredIntent('withdraw');
      setWithdrawIntent(null);
      setWithdrawAmount('');
      const successMsg = `Successfully withdrew ${formatCurrency(amt)} from account ${selectedWithdrawAccount ? selectedWithdrawAccount.accountNumber : ''}.`;
      setNotification({
        type: 'success',
        message: successMsg,
      });
      setActiveAlert({
        type: 'success',
        title: 'Withdrawal Successful',
        message: successMsg,
        actionType: 'Withdrawal',
        amount: amt,
        sourceAccount: selectedWithdrawAccount ? selectedWithdrawAccount.accountNumber : undefined,
        timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
      });
      window.scrollTo({ top: 0, behavior: 'smooth' });
      await fetchAccounts();
    } catch (err: unknown) {
      if (err instanceof ApiException) {
        if (err.status === 409) {
          const errMsg = 'Idempotency conflict: this operation was already processed with different parameters.';
          setNotification({
            type: 'error',
            message: errMsg,
          });
          setActiveAlert({
            type: 'error',
            title: 'Withdrawal Conflict',
            message: errMsg,
            actionType: 'Withdrawal',
            amount: amt,
            timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
          });
        } else if (err.status >= 500) {
          markIntentUncertain('withdraw', preparedIntent, err.message);
          setWithdrawIntent(getStoredIntent('withdraw'));
          const errMsg = `Server error encountered (${err.message}). The transaction intent has been preserved. You can safely retry.`;
          setNotification({
            type: 'error',
            message: errMsg,
          });
          setActiveAlert({
            type: 'error',
            title: 'Withdrawal Preserved (Server Error)',
            message: errMsg,
            actionType: 'Withdrawal',
            amount: amt,
            timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
          });
        } else {
          clearStoredIntent('withdraw');
          setWithdrawIntent(null);
          const errMsg = err.message || 'Withdrawal failed.';
          setNotification({ type: 'error', message: errMsg });
          setActiveAlert({
            type: 'error',
            title: 'Withdrawal Rejected',
            message: errMsg,
            actionType: 'Withdrawal',
            amount: amt,
            timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
          });
        }
      } else {
        markIntentUncertain('withdraw', preparedIntent, 'Network timeout / connection lost');
        setWithdrawIntent(getStoredIntent('withdraw'));
        const errMsg = 'Network connection lost. The withdrawal intent has been preserved. Please retry to safely resolve.';
        setNotification({
          type: 'error',
          message: errMsg,
        });
        setActiveAlert({
          type: 'error',
          title: 'Withdrawal Network Disruption',
          message: errMsg,
          actionType: 'Withdrawal',
          amount: amt,
          timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
        });
      }
      window.scrollTo({ top: 0, behavior: 'smooth' });
    } finally {
      setWithdrawLoading(false);
    }
  };

  const handleOpenTransferReview = (e: React.FormEvent) => {
    e.preventDefault();
    setNotification(null);

    const amt = parseFloat(transferAmount);
    if (!transferSourceId || !transferDestNumber.trim() || isNaN(amt) || amt <= 0) {
      setNotification({
        type: 'error',
        message: 'Please fill in all transfer fields with a valid positive amount.',
      });
      return;
    }

    if (selectedTransferSourceAccount && amt > Number(selectedTransferSourceAccount.balance)) {
      setNotification({
        type: 'error',
        message: `Insufficient balance. Available is ${formatCurrency(Number(selectedTransferSourceAccount.balance))}.`,
      });
      return;
    }

    // Check if this matches an uncertain intent
    const existing = getStoredIntent<TransferPayload>('transfer');
    setIsTransferRetry(existing !== null && existing.status === 'UNCERTAIN');
    setShowTransferReview(true);
  };

  const handleConfirmTransfer = async () => {
    const amt = parseFloat(transferAmount);
    const payload: TransferPayload = {
      sourceAccountId: transferSourceId,
      destinationAccountNumber: transferDestNumber.trim(),
      amount: amt,
      currency: 'INR',
    };

    let preparedIntent: BaseTransactionIntent<TransferPayload>;
    try {
      const res = prepareTransactionIntent('transfer', payload);
      preparedIntent = res.intent;
    } catch (intentErr: unknown) {
      setShowTransferReview(false);
      const msg = intentErr instanceof Error ? intentErr.message : 'Unresolved transfer pending.';
      setNotification({ type: 'error', message: msg });
      return;
    }

    setTransferLoading(true);
    try {
      await transactionsApi.transfer(payload, preparedIntent.key);
      clearStoredIntent('transfer');
      setTransferIntent(null);
      setShowTransferReview(false);
      setTransferAmount('');
      setTransferDestNumber('');
      const successMsg = `Successfully transferred ${formatCurrency(amt)} to ${transferDestNumber.trim()}.`;
      setNotification({
        type: 'success',
        message: successMsg,
      });
      setActiveAlert({
        type: 'success',
        title: 'Transfer Completed',
        message: successMsg,
        actionType: 'Transfer',
        amount: amt,
        sourceAccount: selectedTransferSourceAccount ? selectedTransferSourceAccount.accountNumber : undefined,
        destinationAccount: transferDestNumber.trim(),
        timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
      });
      window.scrollTo({ top: 0, behavior: 'smooth' });
      await fetchAccounts();
    } catch (err: unknown) {
      setShowTransferReview(false);
      if (err instanceof ApiException) {
        if (err.status === 409) {
          const errMsg = 'Idempotency conflict: this transfer was already submitted with different parameters.';
          setNotification({
            type: 'error',
            message: errMsg,
          });
          setActiveAlert({
            type: 'error',
            title: 'Transfer Conflict',
            message: errMsg,
            actionType: 'Transfer',
            amount: amt,
            timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
          });
        } else if (err.status >= 500) {
          markIntentUncertain('transfer', preparedIntent, err.message);
          setTransferIntent(getStoredIntent('transfer'));
          const errMsg = `Server error (${err.message}). The transfer intent has been preserved. You can safely retry.`;
          setNotification({
            type: 'error',
            message: errMsg,
          });
          setActiveAlert({
            type: 'error',
            title: 'Transfer Preserved (Server Error)',
            message: errMsg,
            actionType: 'Transfer',
            amount: amt,
            timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
          });
        } else {
          clearStoredIntent('transfer');
          setTransferIntent(null);
          const errMsg = err.message || 'Transfer failed.';
          setNotification({ type: 'error', message: errMsg });
          setActiveAlert({
            type: 'error',
            title: 'Transfer Rejected',
            message: errMsg,
            actionType: 'Transfer',
            amount: amt,
            timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
          });
        }
      } else {
        markIntentUncertain('transfer', preparedIntent, 'Network timeout / connection lost');
        setTransferIntent(getStoredIntent('transfer'));
        const errMsg = 'Network connection lost during transfer. The intent has been preserved. You can safely retry.';
        setNotification({
          type: 'error',
          message: errMsg,
        });
        setActiveAlert({
          type: 'error',
          title: 'Transfer Network Disruption',
          message: errMsg,
          actionType: 'Transfer',
          amount: amt,
          timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
        });
      }
      window.scrollTo({ top: 0, behavior: 'smooth' });
    } finally {
      setTransferLoading(false);
    }
  };

  const handleDiscardIntent = (type: 'deposit' | 'withdraw' | 'transfer') => {
    clearStoredIntent(type);
    if (type === 'deposit') setDepositIntent(null);
    if (type === 'withdraw') setWithdrawIntent(null);
    if (type === 'transfer') setTransferIntent(null);
    setNotification({ type: 'success', message: `Pending ${type} intent discarded. You can start a new transaction.` });
  };

  return (
    <div>
      {/* High-Visibility Floating Transaction Confirmation Toast Alert */}
      {activeAlert && (
        <div
          role="alert"
          style={{
            position: 'fixed',
            top: '24px',
            right: '24px',
            zIndex: 10000,
            maxWidth: '440px',
            width: 'calc(100% - 48px)',
            backgroundColor: '#FFFFFF',
            borderRadius: '12px',
            border: activeAlert.type === 'success' ? '1px solid #10B981' : '1px solid #EF4444',
            boxShadow: '0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04)',
            overflow: 'hidden'
          }}
        >
          {/* Top colored indicator line */}
          <div style={{
            height: '4px',
            backgroundColor: activeAlert.type === 'success' ? '#10B981' : '#EF4444',
            width: '100%'
          }} />

          <div style={{ padding: '16px 20px' }}>
            <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: '12px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                <span style={{
                  fontSize: '20px',
                  display: 'inline-flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  width: '32px',
                  height: '32px',
                  borderRadius: '50%',
                  backgroundColor: activeAlert.type === 'success' ? '#ECFDF5' : '#FEF2F2',
                  color: activeAlert.type === 'success' ? '#059669' : '#DC2626',
                  fontWeight: 700
                }}>
                  {activeAlert.type === 'success' ? '✓' : '⚠️'}
                </span>
                <div>
                  <h4 style={{ margin: 0, fontSize: '15px', fontWeight: 700, color: '#0F172A' }}>
                    {activeAlert.title}
                  </h4>
                  <div style={{ fontSize: '12px', color: '#64748B' }}>
                    {activeAlert.timestamp} &bull; Status: <strong>{activeAlert.type === 'success' ? 'COMPLETED' : 'FAILED'}</strong>
                  </div>
                </div>
              </div>

              <button
                type="button"
                onClick={() => setActiveAlert(null)}
                aria-label="Close notification"
                style={{
                  background: 'none',
                  border: 'none',
                  fontSize: '20px',
                  color: '#94A3B8',
                  cursor: 'pointer',
                  padding: '2px 6px',
                  lineHeight: 1
                }}
              >
                &times;
              </button>
            </div>

            <p style={{
              margin: '12px 0 0',
              fontSize: '13px',
              color: '#334155',
              lineHeight: 1.5,
              fontWeight: 500
            }}>
              {activeAlert.message}
            </p>

            {activeAlert.type === 'success' && activeAlert.amount !== undefined && (
              <div style={{
                marginTop: '12px',
                padding: '10px 12px',
                backgroundColor: '#F8FAFC',
                borderRadius: '8px',
                border: '1px solid #E2E8F0',
                fontSize: '12px',
                display: 'grid',
                gridTemplateColumns: '1fr 1fr',
                gap: '8px'
              }}>
                <div>
                  <span style={{ color: '#64748B' }}>Amount:</span>{' '}
                  <strong style={{ color: '#0F172A', fontSize: '13px' }}>{formatCurrency(activeAlert.amount)}</strong>
                </div>
                <div>
                  <span style={{ color: '#64748B' }}>Operation:</span>{' '}
                  <strong style={{ color: '#0D6E64' }}>{activeAlert.actionType}</strong>
                </div>
                {activeAlert.sourceAccount && (
                  <div style={{ gridColumn: activeAlert.destinationAccount ? 'auto' : 'span 2' }}>
                    <span style={{ color: '#64748B' }}>Source Account:</span>{' '}
                    <code style={{ color: '#334155', fontWeight: 600 }}>{activeAlert.sourceAccount}</code>
                  </div>
                )}
                {activeAlert.destinationAccount && (
                  <div>
                    <span style={{ color: '#64748B' }}>Destination:</span>{' '}
                    <code style={{ color: '#334155', fontWeight: 600 }}>{activeAlert.destinationAccount}</code>
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      )}

      <h1 style={{ fontSize: '26px', fontWeight: 700, color: '#111827', marginBottom: '28px' }}>
        Move money
      </h1>

      {notification && (
        <div
          className={`alert ${notification.type === 'success' ? 'alert-success' : 'alert-danger'}`}
          role="alert"
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '14px 18px',
            fontSize: '14px',
            fontWeight: 500
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
            <span style={{ fontSize: '18px' }}>{notification.type === 'success' ? '✅' : '⚠️'}</span>
            <span>{notification.message}</span>
          </div>
          <button
            type="button"
            onClick={() => setNotification(null)}
            style={{ background: 'none', border: 'none', fontSize: '18px', cursor: 'pointer', color: 'inherit', opacity: 0.7 }}
          >
            &times;
          </button>
        </div>
      )}

      {loading ? (
        <div style={{ padding: '32px 0', color: '#6B7280' }}>Loading accounts...</div>
      ) : accounts.length === 0 ? (
        <div className="card" style={{ textAlign: 'center', padding: '40px 20px' }}>
          <p style={{ color: '#4B5563', marginBottom: '16px' }}>
            You need an active account before moving money.
          </p>
          <a href="/accounts" className="btn-primary">
            Create an account
          </a>
        </div>
      ) : (
        <div style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))',
          gap: '24px',
          alignItems: 'start'
        }}>
          {/* 1. Deposit Card */}
          <div className="card">
            <h2 style={{ fontSize: '18px', fontWeight: 600, color: '#111827', marginBottom: '16px' }}>
              Deposit
            </h2>

            {depositIntent?.status === 'UNCERTAIN' && (
              <div style={{
                backgroundColor: '#FEF3C7',
                border: '1px solid #F59E0B',
                borderRadius: '8px',
                padding: '12px',
                marginBottom: '16px',
                fontSize: '13px',
                color: '#92400E'
              }}>
                <div style={{ fontWeight: 600, marginBottom: '4px' }}>Unresolved Deposit Pending</div>
                <div>Amount: {formatCurrency(depositIntent.payload.amount)}</div>
                <div style={{ fontSize: '11px', color: '#B45309', marginTop: '4px' }}>
                  Idempotency Key: {depositIntent.key.slice(0, 8)}...
                </div>
                <div style={{ display: 'flex', gap: '8px', marginTop: '10px' }}>
                  <button
                    type="button"
                    className="btn-outline"
                    style={{ padding: '4px 10px', fontSize: '12px', borderColor: '#B45309', color: '#B45309' }}
                    onClick={() => handleDiscardIntent('deposit')}
                  >
                    Discard intent
                  </button>
                </div>
              </div>
            )}

            <form onSubmit={handleDeposit}>
              <div className="form-group">
                <label className="form-label" htmlFor="deposit-account">To account</label>
                <select
                  id="deposit-account"
                  className="form-select"
                  value={depositAccountId}
                  onChange={(e) => setDepositAccountId(e.target.value)}
                  disabled={depositLoading}
                >
                  {accounts.map((acc) => (
                    <option key={acc.id} value={acc.id}>
                      Account {maskAccountNumber(acc.accountNumber)} ({formatCurrency(Number(acc.balance))})
                    </option>
                  ))}
                </select>
              </div>

              <div className="form-group">
                <label className="form-label" htmlFor="deposit-amount">Amount</label>
                <input
                  id="deposit-amount"
                  type="number"
                  step="0.01"
                  min="0.01"
                  className="form-input"
                  placeholder="₹0.00"
                  value={depositAmount}
                  onChange={(e) => setDepositAmount(e.target.value)}
                  disabled={depositLoading}
                  required
                />
              </div>

              <button
                type="submit"
                className="btn-primary"
                style={{ width: '100%', marginTop: '8px' }}
                disabled={depositLoading || !depositAmount}
              >
                {depositLoading
                  ? 'Depositing...'
                  : depositIntent?.status === 'UNCERTAIN'
                  ? 'Retry Deposit'
                  : 'Deposit'}
              </button>
            </form>
            <div style={{ fontSize: '12px', color: '#9CA3AF', marginTop: '16px', textAlign: 'center' }}>
              Simulated cash-in operation via bank vault.
            </div>
          </div>

          {/* 2. Withdraw Card */}
          <div className="card">
            <h2 style={{ fontSize: '18px', fontWeight: 600, color: '#111827', marginBottom: '16px' }}>
              Withdraw
            </h2>

            {withdrawIntent?.status === 'UNCERTAIN' && (
              <div style={{
                backgroundColor: '#FEF3C7',
                border: '1px solid #F59E0B',
                borderRadius: '8px',
                padding: '12px',
                marginBottom: '16px',
                fontSize: '13px',
                color: '#92400E'
              }}>
                <div style={{ fontWeight: 600, marginBottom: '4px' }}>Unresolved Withdrawal Pending</div>
                <div>Amount: {formatCurrency(withdrawIntent.payload.amount)}</div>
                <div style={{ fontSize: '11px', color: '#B45309', marginTop: '4px' }}>
                  Idempotency Key: {withdrawIntent.key.slice(0, 8)}...
                </div>
                <div style={{ display: 'flex', gap: '8px', marginTop: '10px' }}>
                  <button
                    type="button"
                    className="btn-outline"
                    style={{ padding: '4px 10px', fontSize: '12px', borderColor: '#B45309', color: '#B45309' }}
                    onClick={() => handleDiscardIntent('withdraw')}
                  >
                    Discard intent
                  </button>
                </div>
              </div>
            )}

            <form onSubmit={handleWithdraw}>
              <div className="form-group">
                <label className="form-label" htmlFor="withdraw-account">From account</label>
                <select
                  id="withdraw-account"
                  className="form-select"
                  value={withdrawAccountId}
                  onChange={(e) => setWithdrawAccountId(e.target.value)}
                  disabled={withdrawLoading}
                >
                  {accounts.map((acc) => (
                    <option key={acc.id} value={acc.id}>
                      Account {maskAccountNumber(acc.accountNumber)}
                    </option>
                  ))}
                </select>
              </div>

              <div className="form-group">
                <label className="form-label" htmlFor="withdraw-amount">
                  Amount {selectedWithdrawAccount && `(available ${formatCurrency(Number(selectedWithdrawAccount.balance))})`}
                </label>
                <input
                  id="withdraw-amount"
                  type="number"
                  step="0.01"
                  min="0.01"
                  className="form-input"
                  placeholder="₹0.00"
                  value={withdrawAmount}
                  onChange={(e) => setWithdrawAmount(e.target.value)}
                  disabled={withdrawLoading}
                  required
                />
              </div>

              <button
                type="submit"
                className="btn-outline"
                style={{ width: '100%', marginTop: '8px' }}
                disabled={withdrawLoading || !withdrawAmount}
              >
                {withdrawLoading
                  ? 'Withdrawing...'
                  : withdrawIntent?.status === 'UNCERTAIN'
                  ? 'Retry Withdrawal'
                  : 'Withdraw'}
              </button>
            </form>
            <div style={{ fontSize: '12px', color: '#9CA3AF', marginTop: '16px', textAlign: 'center' }}>
              Simulated cash-out operation from your account.
            </div>
          </div>

          {/* 3. Transfer Card */}
          <div className="card">
            <h2 style={{ fontSize: '18px', fontWeight: 600, color: '#111827', marginBottom: '16px' }}>
              Transfer
            </h2>

            {transferIntent?.status === 'UNCERTAIN' && (
              <div style={{
                backgroundColor: '#FEF3C7',
                border: '1px solid #F59E0B',
                borderRadius: '8px',
                padding: '12px',
                marginBottom: '16px',
                fontSize: '13px',
                color: '#92400E'
              }}>
                <div style={{ fontWeight: 600, marginBottom: '4px' }}>Unresolved Transfer Pending</div>
                <div>To: {transferIntent.payload.destinationAccountNumber}</div>
                <div>Amount: {formatCurrency(transferIntent.payload.amount)}</div>
                <div style={{ fontSize: '11px', color: '#B45309', marginTop: '4px' }}>
                  Idempotency Key: {transferIntent.key.slice(0, 8)}...
                </div>
                <div style={{ display: 'flex', gap: '8px', marginTop: '10px' }}>
                  <button
                    type="button"
                    className="btn-outline"
                    style={{ padding: '4px 10px', fontSize: '12px', borderColor: '#B45309', color: '#B45309' }}
                    onClick={() => handleDiscardIntent('transfer')}
                  >
                    Discard intent
                  </button>
                </div>
              </div>
            )}

            <form onSubmit={handleOpenTransferReview}>
              <div className="form-group">
                <label className="form-label" htmlFor="transfer-source">From</label>
                <select
                  id="transfer-source"
                  className="form-select"
                  value={transferSourceId}
                  onChange={(e) => setTransferSourceId(e.target.value)}
                  disabled={transferLoading}
                >
                  {accounts.map((acc) => (
                    <option key={acc.id} value={acc.id}>
                      Account {maskAccountNumber(acc.accountNumber)} ({formatCurrency(Number(acc.balance))})
                    </option>
                  ))}
                </select>
              </div>

              <div className="form-group">
                <label className="form-label" htmlFor="transfer-dest">To account number</label>
                <input
                  id="transfer-dest"
                  type="text"
                  className="form-input"
                  placeholder="VLTR..."
                  value={transferDestNumber}
                  onChange={(e) => setTransferDestNumber(e.target.value)}
                  disabled={transferLoading}
                  required
                />
              </div>

              <div className="form-group">
                <label className="form-label" htmlFor="transfer-amount">Amount</label>
                <input
                  id="transfer-amount"
                  type="number"
                  step="0.01"
                  min="0.01"
                  className="form-input"
                  placeholder="₹0.00"
                  value={transferAmount}
                  onChange={(e) => setTransferAmount(e.target.value)}
                  disabled={transferLoading}
                  required
                />
              </div>

              <button
                type="submit"
                className="btn-primary"
                style={{ width: '100%', marginTop: '8px' }}
                disabled={transferLoading || !transferAmount || !transferDestNumber}
              >
                {transferIntent?.status === 'UNCERTAIN' ? 'Review retry transfer' : 'Review transfer'}
              </button>
            </form>
            <div style={{ fontSize: '12px', color: '#9CA3AF', marginTop: '16px', textAlign: 'center' }}>
              Atomic internal transfer with zero-sum ledger invariants.
            </div>
          </div>
        </div>
      )}

      {/* Transfer Review Modal */}
      {showTransferReview && (
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
          <div className="card" style={{ width: '100%', maxWidth: '440px', padding: '28px' }}>
            <h3 style={{ fontSize: '18px', fontWeight: 700, color: '#111827', marginBottom: '16px' }}>
              {isTransferRetry ? 'Confirm Transfer Retry' : 'Review Transfer'}
            </h3>

            {isTransferRetry && (
              <div style={{
                backgroundColor: '#EFF6FF',
                border: '1px solid #BFDBFE',
                borderRadius: '6px',
                padding: '10px 12px',
                marginBottom: '14px',
                fontSize: '12px',
                color: '#1E40AF'
              }}>
                Retrying previously unresolved transfer with original idempotency key.
              </div>
            )}

            <div style={{
              backgroundColor: '#F9FAFB',
              border: '1px solid #E5E7EB',
              borderRadius: '8px',
              padding: '16px',
              marginBottom: '20px'
            }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '10px' }}>
                <span style={{ fontSize: '13px', color: '#6B7280' }}>From Account:</span>
                <span style={{ fontSize: '13px', fontWeight: 600, color: '#111827' }}>
                  {selectedTransferSourceAccount
                    ? `Account ${maskAccountNumber(selectedTransferSourceAccount.accountNumber)}`
                    : 'Selected Account'}
                </span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '10px' }}>
                <span style={{ fontSize: '13px', color: '#6B7280' }}>To Account:</span>
                <span style={{ fontSize: '13px', fontWeight: 600, color: '#111827' }}>
                  {transferDestNumber.trim()}
                </span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '10px' }}>
                <span style={{ fontSize: '13px', color: '#6B7280' }}>Amount:</span>
                <span style={{ fontSize: '16px', fontWeight: 700, color: '#0D6E64' }}>
                  {formatCurrency(parseFloat(transferAmount) || 0)}
                </span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ fontSize: '13px', color: '#6B7280' }}>Currency:</span>
                <span style={{ fontSize: '13px', fontWeight: 500, color: '#111827' }}>INR</span>
              </div>
            </div>

            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '12px' }}>
              <button
                type="button"
                className="btn-outline"
                onClick={() => setShowTransferReview(false)}
                disabled={transferLoading}
              >
                Back
              </button>
              <button
                type="button"
                className="btn-primary"
                onClick={handleConfirmTransfer}
                disabled={transferLoading}
              >
                {transferLoading
                  ? 'Processing...'
                  : isTransferRetry
                  ? 'Confirm retry'
                  : 'Confirm transfer'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
