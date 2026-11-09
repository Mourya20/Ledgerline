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
  const [depositAccountId, setDepositAccountId] = useState('');
  const [depositAmount, setDepositAmount] = useState('');
  const [depositLoading, setDepositLoading] = useState(false);

  // Withdraw Form State
  const [withdrawAccountId, setWithdrawAccountId] = useState('');
  const [withdrawAmount, setWithdrawAmount] = useState('');
  const [withdrawLoading, setWithdrawLoading] = useState(false);

  // Transfer Form State
  const [transferSourceId, setTransferSourceId] = useState('');
  const [transferDestNumber, setTransferDestNumber] = useState('');
  const [transferAmount, setTransferAmount] = useState('');
  const [transferLoading, setTransferLoading] = useState(false);
  const [showTransferReview, setShowTransferReview] = useState(false);
  const [isTransferRetry, setIsTransferRetry] = useState(false);

  // Notifications
  const [notification, setNotification] = useState<{ type: 'success' | 'error'; message: string } | null>(null);

  const fetchAccounts = async () => {
    try {
      setLoading(true);
      const data = await accountsApi.getAccounts();
      setAccounts(data);
      if (data.length > 0) {
        if (!depositAccountId) setDepositAccountId(data[0].id);
        if (!withdrawAccountId) setWithdrawAccountId(data[0].id);
        if (!transferSourceId) {
          const match = initialSourceId ? data.find((a) => a.id === initialSourceId) : null;
          setTransferSourceId(match ? match.id : data[0].id);
        }
      }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to load accounts';
      setNotification({ type: 'error', message: msg });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchAccounts();

    // If an uncertain intent exists, populate form with its preserved payload
    const storedDep = getStoredIntent<DepositPayload>('deposit');
    if (storedDep?.status === 'UNCERTAIN') {
      setDepositAccountId(storedDep.payload.accountId);
      setDepositAmount(storedDep.payload.amount.toString());
      setDepositIntent(storedDep);
    }

    const storedWith = getStoredIntent<WithdrawPayload>('withdraw');
    if (storedWith?.status === 'UNCERTAIN') {
      setWithdrawAccountId(storedWith.payload.accountId);
      setWithdrawAmount(storedWith.payload.amount.toString());
      setWithdrawIntent(storedWith);
    }

    const storedTrans = getStoredIntent<TransferPayload>('transfer');
    if (storedTrans?.status === 'UNCERTAIN') {
      setTransferSourceId(storedTrans.payload.sourceAccountId);
      setTransferDestNumber(storedTrans.payload.destinationAccountNumber);
      setTransferAmount(storedTrans.payload.amount.toString());
      setTransferIntent(storedTrans);
    }
  }, []);

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
      setNotification({
        type: 'success',
        message: `Successfully deposited ${formatCurrency(amt)} into your account.`,
      });
      await fetchAccounts();
    } catch (err: unknown) {
      if (err instanceof ApiException) {
        if (err.status === 409) {
          setNotification({
            type: 'error',
            message: 'Idempotency conflict: this operation was already processed with different parameters.',
          });
        } else if (err.status >= 500) {
          markIntentUncertain('deposit', preparedIntent, err.message);
          setDepositIntent(getStoredIntent('deposit'));
          setNotification({
            type: 'error',
            message: `Server error encountered (${err.message}). The transaction intent has been preserved. You can safely retry.`,
          });
        } else {
          // Client errors 400, 422, etc. do not commit
          clearStoredIntent('deposit');
          setDepositIntent(null);
          setNotification({ type: 'error', message: err.message || 'Deposit failed.' });
        }
      } else {
        // Network timeout / connection lost: UNCERTAIN
        markIntentUncertain('deposit', preparedIntent, 'Network timeout / connection lost');
        setDepositIntent(getStoredIntent('deposit'));
        setNotification({
          type: 'error',
          message: 'Network connection lost. The deposit intent has been preserved. Please retry to safely resolve.',
        });
      }
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
      setNotification({
        type: 'success',
        message: `Successfully withdrew ${formatCurrency(amt)} from your account.`,
      });
      await fetchAccounts();
    } catch (err: unknown) {
      if (err instanceof ApiException) {
        if (err.status === 409) {
          setNotification({
            type: 'error',
            message: 'Idempotency conflict: this operation was already processed with different parameters.',
          });
        } else if (err.status >= 500) {
          markIntentUncertain('withdraw', preparedIntent, err.message);
          setWithdrawIntent(getStoredIntent('withdraw'));
          setNotification({
            type: 'error',
            message: `Server error encountered (${err.message}). The transaction intent has been preserved. You can safely retry.`,
          });
        } else {
          clearStoredIntent('withdraw');
          setWithdrawIntent(null);
          setNotification({ type: 'error', message: err.message || 'Withdrawal failed.' });
        }
      } else {
        markIntentUncertain('withdraw', preparedIntent, 'Network timeout / connection lost');
        setWithdrawIntent(getStoredIntent('withdraw'));
        setNotification({
          type: 'error',
          message: 'Network connection lost. The withdrawal intent has been preserved. Please retry to safely resolve.',
        });
      }
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
      setNotification({
        type: 'success',
        message: `Successfully transferred ${formatCurrency(amt)} to ${transferDestNumber.trim()}.`,
      });
      await fetchAccounts();
    } catch (err: unknown) {
      setShowTransferReview(false);
      if (err instanceof ApiException) {
        if (err.status === 409) {
          setNotification({
            type: 'error',
            message: 'Idempotency conflict: this transfer was already submitted with different parameters.',
          });
        } else if (err.status >= 500) {
          markIntentUncertain('transfer', preparedIntent, err.message);
          setTransferIntent(getStoredIntent('transfer'));
          setNotification({
            type: 'error',
            message: `Server error (${err.message}). The transfer intent has been preserved. You can safely retry.`,
          });
        } else {
          clearStoredIntent('transfer');
          setTransferIntent(null);
          setNotification({ type: 'error', message: err.message || 'Transfer failed.' });
        }
      } else {
        markIntentUncertain('transfer', preparedIntent, 'Network timeout / connection lost');
        setTransferIntent(getStoredIntent('transfer'));
        setNotification({
          type: 'error',
          message: 'Network connection lost during transfer. The intent has been preserved. You can safely retry.',
        });
      }
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
      <h1 style={{ fontSize: '26px', fontWeight: 700, color: '#111827', marginBottom: '28px' }}>
        Move money
      </h1>

      {notification && (
        <div
          className={`alert ${notification.type === 'success' ? 'alert-success' : 'alert-danger'}`}
          role="alert"
        >
          {notification.message}
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
