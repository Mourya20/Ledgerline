import type { TransactionEntryResponse, TransactionResponse } from '../api/types';

/**
 * Formats a numeric amount into INR currency (₹12,480.50)
 */
export function formatCurrency(amount: number, _currency = 'INR'): string {
  const formatter = new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  return formatter.format(amount);
}

/**
 * Masks an account number showing only the last 4 digits (e.g. ••••4821)
 */
export function maskAccountNumber(accountNumber?: string | null): string {
  if (!accountNumber) return '••••';
  const clean = accountNumber.trim();
  if (clean.startsWith('****')) {
    return `••••${clean.substring(4)}`;
  }
  if (clean.startsWith('••••')) {
    return clean;
  }
  if (clean.length <= 4) return `••••${clean}`;
  return `••••${clean.substring(clean.length - 4)}`;
}

/**
 * Formats an ISO date string into readable format (e.g. Nov 8)
 */
export function formatDate(isoDate?: string | null, includeYear = false): string {
  if (!isoDate) return '—';
  try {
    const d = new Date(isoDate);
    if (isNaN(d.getTime())) return '—';
    return d.toLocaleDateString('en-US', {
      month: 'short',
      day: 'numeric',
      ...(includeYear ? { year: 'numeric' } : {}),
    });
  } catch {
    return '—';
  }
}

export interface CustomerTransactionView {
  description: string;
  accountLabel: string;
  typeLabel: 'Deposit' | 'Withdrawal' | 'Transfer';
  amount: number;
  isPositive: boolean;
  formattedAmount: string;
  rawDate: string;
}

/**
 * Analyzes transaction entries to produce the customer-perspective description,
 * account, direction, and amount matching the PDF visual presentation.
 *
 * Correctly distinguishes:
 * 1. Cash deposits (positive amount, "Cash deposit")
 * 2. Cash withdrawals (negative amount, "Cash withdrawal")
 * 3. Own-account transfers between two accounts owned by the same user
 * 4. Outgoing transfers to another customer
 * 5. Incoming transfers received from another customer
 */
export function parseCustomerTransaction(
  tx: TransactionResponse,
  userAccountIds: Set<string>,
  focusAccountId?: string
): CustomerTransactionView {
  const type = tx.transactionType.toUpperCase();
  const entries = tx.entries || [];

  // Filter entries owned by this user
  const userEntries = entries.filter((e) => e.accountId && userAccountIds.has(e.accountId));
  // Filter external/counterpart entries (or system vault)
  const externalEntries = entries.filter((e) => !e.accountId || !userAccountIds.has(e.accountId));

  let description = 'Transaction';
  let typeLabel: 'Deposit' | 'Withdrawal' | 'Transfer' = 'Transfer';
  let isPositive = false;
  let amount = 0;
  let activeAccountEntry: TransactionEntryResponse | undefined;

  if (type === 'DEPOSIT') {
    typeLabel = 'Deposit';
    description = 'Cash deposit';
    isPositive = true;
    activeAccountEntry = userEntries.find((e) => e.entryType === 'CREDIT') || userEntries[0];
    amount = activeAccountEntry ? Number(activeAccountEntry.amount) : 0;
  } else if (type === 'WITHDRAWAL') {
    typeLabel = 'Withdrawal';
    description = 'Cash withdrawal';
    isPositive = false;
    activeAccountEntry = userEntries.find((e) => e.entryType === 'DEBIT') || userEntries[0];
    amount = activeAccountEntry ? Number(activeAccountEntry.amount) : 0;
  } else if (type === 'TRANSFER') {
    typeLabel = 'Transfer';

    // Check if both sides of the transfer belong to this customer (own-account transfer)
    const isOwnAccountTransfer = userEntries.length >= 2;

    if (isOwnAccountTransfer) {
      const debitLeg = userEntries.find((e) => e.entryType === 'DEBIT');
      const creditLeg = userEntries.find((e) => e.entryType === 'CREDIT');

      if (focusAccountId) {
        if (creditLeg && creditLeg.accountId === focusAccountId) {
          // Viewing from destination account perspective: Money arrived
          activeAccountEntry = creditLeg;
          isPositive = true;
          amount = Number(creditLeg.amount);
          const sourceNum = debitLeg ? maskAccountNumber(debitLeg.accountNumber) : '••••';
          description = `Transfer from ${sourceNum}`;
        } else {
          // Viewing from source account perspective: Money sent
          activeAccountEntry = debitLeg;
          isPositive = false;
          amount = debitLeg ? Number(debitLeg.amount) : 0;
          const destNum = creditLeg ? maskAccountNumber(creditLeg.accountNumber) : '••••';
          description = `Transfer to ${destNum}`;
        }
      } else {
        // Consolidated view (e.g. All accounts): default perspective to debit leg
        activeAccountEntry = debitLeg || creditLeg;
        isPositive = false;
        amount = debitLeg ? Number(debitLeg.amount) : Number(creditLeg?.amount || 0);
        const destNum = creditLeg ? maskAccountNumber(creditLeg.accountNumber) : '••••';
        description = `Transfer to ${destNum}`;
      }
    } else {
      // Transfer involving another customer
      const userEntry = userEntries[0];
      const counterpartEntry = externalEntries[0];
      activeAccountEntry = userEntry;

      if (userEntry) {
        amount = Number(userEntry.amount);
        const cpNum = counterpartEntry?.accountNumber
          ? maskAccountNumber(counterpartEntry.accountNumber)
          : '••••';

        if (userEntry.entryType === 'DEBIT') {
          // Sent to another customer
          isPositive = false;
          description = `Transfer to ${cpNum}`;
        } else {
          // Received from another customer
          isPositive = true;
          description = `Transfer from ${cpNum}`;
        }
      }
    }
  }

  const accountLabel = activeAccountEntry
    ? `Account ${maskAccountNumber(activeAccountEntry.accountNumber)}`
    : 'Account';

  const formattedAmount = `${isPositive ? '+' : '-'}${formatCurrency(amount)}`;

  return {
    description,
    accountLabel,
    typeLabel,
    amount,
    isPositive,
    formattedAmount,
    rawDate: tx.completedAt || tx.createdAt,
  };
}
