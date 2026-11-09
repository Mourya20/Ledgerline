import { describe, it, expect, beforeEach } from 'vitest';
import {
  prepareTransactionIntent,
  markIntentUncertain,
  clearStoredIntent,
  getStoredIntent,
  isSamePayload,
  type DepositPayload,
} from './idempotencyManager';

describe('idempotencyManager', () => {
  beforeEach(() => {
    sessionStorage.clear();
  });

  const payloadA: DepositPayload = {
    accountId: 'acc-1111',
    amount: 1000,
    currency: 'INR',
  };

  const payloadB: DepositPayload = {
    accountId: 'acc-1111',
    amount: 2000,
    currency: 'INR',
  };

  it('generates a new unique idempotency key for a new operation', () => {
    const { intent, isRetry } = prepareTransactionIntent('deposit', payloadA);
    expect(isRetry).toBe(false);
    expect(intent.key).toBeDefined();
    expect(intent.status).toBe('PENDING');
    expect(intent.payload).toEqual(payloadA);

    // Verify it is saved in sessionStorage
    const stored = getStoredIntent<DepositPayload>('deposit');
    expect(stored?.key).toBe(intent.key);
  });

  it('reuses the exact same key when retrying the same payload', () => {
    const first = prepareTransactionIntent('deposit', payloadA);
    markIntentUncertain('deposit', first.intent, 'Network timeout');

    // Attempt second submission with same payload
    const retry = prepareTransactionIntent('deposit', payloadA);
    expect(retry.isRetry).toBe(true);
    expect(retry.intent.key).toBe(first.intent.key);
    expect(retry.intent.payload).toEqual(payloadA);
  });

  it('rejects attempt to alter payload while an uncertain transaction is pending', () => {
    const first = prepareTransactionIntent('deposit', payloadA);
    markIntentUncertain('deposit', first.intent, 'Connection failed');

    // Attempt to submit DIFFERENT payload with existing unresolved intent
    expect(() => {
      prepareTransactionIntent('deposit', payloadB);
    }).toThrowError(/An unresolved deposit transaction is pending/);
  });

  it('clears completed intent on success so subsequent operation receives a new key', () => {
    const first = prepareTransactionIntent('deposit', payloadA);
    clearStoredIntent('deposit');

    // Next operation should be fresh with new key
    const second = prepareTransactionIntent('deposit', payloadA);
    expect(second.isRetry).toBe(false);
    expect(second.intent.key).not.toBe(first.intent.key);
  });

  it('preserves unresolved transaction intent across page reload via sessionStorage', () => {
    const first = prepareTransactionIntent('deposit', payloadA);
    markIntentUncertain('deposit', first.intent, 'Gateway timeout');

    // Simulate page reload by querying sessionStorage directly
    const stored = getStoredIntent<DepositPayload>('deposit');
    expect(stored).not.toBeNull();
    expect(stored?.key).toBe(first.intent.key);
    expect(stored?.status).toBe('UNCERTAIN');
    expect(stored?.errorMessage).toBe('Gateway timeout');
  });

  it('accurately identifies identical vs different payloads', () => {
    expect(isSamePayload(payloadA, { ...payloadA })).toBe(true);
    expect(isSamePayload(payloadA, payloadB)).toBe(false);
  });
});
