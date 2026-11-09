export type TransactionType = 'deposit' | 'withdraw' | 'transfer';

export interface BaseTransactionIntent<T> {
  key: string;
  type: TransactionType;
  payload: T;
  createdAt: number;
  status: 'PENDING' | 'UNCERTAIN';
  errorMessage?: string;
}

export interface DepositPayload {
  accountId: string;
  amount: number;
  currency: string;
}

export interface WithdrawPayload {
  accountId: string;
  amount: number;
  currency: string;
}

export interface TransferPayload {
  sourceAccountId: string;
  destinationAccountNumber: string;
  amount: number;
  currency: string;
}

const STORAGE_PREFIX = 'vaultora_tx_intent_';

function getStorageKey(type: TransactionType): string {
  return `${STORAGE_PREFIX}${type}`;
}

/**
 * Checks if two transaction payloads have identical properties
 */
export function isSamePayload<T>(a: T, b: T): boolean {
  if (!a || !b) return false;
  const keysA = Object.keys(a as object).sort();
  const keysB = Object.keys(b as object).sort();
  if (keysA.length !== keysB.length) return false;

  for (const k of keysA) {
    const valA = (a as Record<string, unknown>)[k];
    const valB = (b as Record<string, unknown>)[k];

    if (typeof valA === 'number' && typeof valB === 'number') {
      if (Math.abs(valA - valB) > 0.00001) return false;
    } else if (valA !== valB) {
      return false;
    }
  }
  return true;
}

/**
 * Retrieves an active or uncertain intent from sessionStorage
 */
export function getStoredIntent<T>(type: TransactionType): BaseTransactionIntent<T> | null {
  if (typeof sessionStorage === 'undefined') return null;
  try {
    const data = sessionStorage.getItem(getStorageKey(type));
    if (!data) return null;
    return JSON.parse(data) as BaseTransactionIntent<T>;
  } catch (err) {
    console.warn(`Failed to parse stored transaction intent for ${type}:`, err);
    return null;
  }
}

/**
 * Persists an intent into sessionStorage
 */
export function storeIntent<T>(type: TransactionType, intent: BaseTransactionIntent<T>): void {
  if (typeof sessionStorage === 'undefined') return;
  try {
    sessionStorage.setItem(getStorageKey(type), JSON.stringify(intent));
  } catch (err) {
    console.warn(`Failed to store transaction intent for ${type}:`, err);
  }
}

/**
 * Clears an intent from sessionStorage upon success or explicit abandonment
 */
export function clearStoredIntent(type: TransactionType): void {
  if (typeof sessionStorage === 'undefined') return;
  try {
    sessionStorage.removeItem(getStorageKey(type));
  } catch (err) {
    console.warn(`Failed to remove transaction intent for ${type}:`, err);
  }
}

/**
 * Prepares or retrieves a transaction intent for dispatch.
 *
 * Rules:
 * - If an existing uncertain intent has identical payload, reuses the existing idempotency key.
 * - If an existing intent has DIFFERENT payload, throws an error requiring resolution/abandonment.
 * - If no intent exists, generates a fresh crypto.randomUUID().
 */
export function prepareTransactionIntent<T>(
  type: TransactionType,
  payload: T
): { intent: BaseTransactionIntent<T>; isRetry: boolean } {
  const existing = getStoredIntent<T>(type);

  if (existing) {
    if (isSamePayload(existing.payload, payload)) {
      // Valid retry with exact same key and payload
      return { intent: existing, isRetry: true };
    }

    // Attempted to change details while an uncertain transaction is unresolved
    throw new Error(
      `An unresolved ${type} transaction is pending. You must retry or explicitly discard it before submitting different transaction details.`
    );
  }

  // Create brand new intent
  const newIntent: BaseTransactionIntent<T> = {
    key: crypto.randomUUID(),
    type,
    payload,
    createdAt: Date.now(),
    status: 'PENDING',
  };

  storeIntent(type, newIntent);
  return { intent: newIntent, isRetry: false };
}

/**
 * Marks an intent as UNCERTAIN (e.g. after a network timeout or connection failure)
 */
export function markIntentUncertain<T>(
  type: TransactionType,
  intent: BaseTransactionIntent<T>,
  errorMessage: string
): void {
  const updated: BaseTransactionIntent<T> = {
    ...intent,
    status: 'UNCERTAIN',
    errorMessage,
  };
  storeIntent(type, updated);
}
