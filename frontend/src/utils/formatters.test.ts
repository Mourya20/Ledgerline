import { describe, it, expect } from 'vitest';
import {
  formatCurrency,
  maskAccountNumber,
  formatDate,
  parseCustomerTransaction,
} from './formatters';
import type { TransactionResponse } from '../api/types';

describe('formatters utility', () => {
  it('formats currency into INR format with symbol', () => {
    const formatted = formatCurrency(12480.5);
    expect(formatted).toContain('12,480.50');
    expect(formatted).toContain('₹');
  });

  it('masks account number showing last 4 digits', () => {
    expect(maskAccountNumber('VLTR123456784821')).toBe('••••4821');
    expect(maskAccountNumber('4821')).toBe('••••4821');
    expect(maskAccountNumber('****7310')).toBe('••••7310');
    expect(maskAccountNumber('••••7310')).toBe('••••7310');
    expect(maskAccountNumber(null)).toBe('••••');
  });

  it('formats date strings', () => {
    const formatted = formatDate('2026-11-08T10:00:00Z');
    expect(formatted).toContain('Nov');
    expect(formatted).toContain('8');
  });

  describe('parseCustomerTransaction regression suite', () => {
    const accountAId = 'acc-aaaa-1111';
    const accountBId = 'acc-bbbb-2222';
    const userAccountIds = new Set([accountAId, accountBId]);

    // 1. Own-Account Transfer (between Account A and Account B, both owned by same customer)
    describe('1. Transfer between two customer-owned accounts', () => {
      const ownTransferTx: TransactionResponse = {
        id: 'tx-own-1',
        transactionType: 'TRANSFER',
        status: 'COMPLETED',
        currency: 'INR',
        entries: [
          {
            id: 'e-1',
            accountId: accountAId,
            accountNumber: 'VLTR000000004821',
            entryType: 'DEBIT',
            amount: 500,
            currency: 'INR',
          },
          {
            id: 'e-2',
            accountId: accountBId,
            accountNumber: 'VLTR000000007310',
            entryType: 'CREDIT',
            amount: 500,
            currency: 'INR',
          },
        ],
        createdAt: '2026-11-08T10:10:00Z',
        completedAt: '2026-11-08T10:10:00Z',
      };

      it('formats correctly when viewing from source account perspective (focusAccountId = accountAId)', () => {
        const result = parseCustomerTransaction(ownTransferTx, userAccountIds, accountAId);
        expect(result.typeLabel).toBe('Transfer');
        expect(result.description).toBe('Transfer to ••••7310');
        expect(result.accountLabel).toBe('Account ••••4821');
        expect(result.isPositive).toBe(false);
        expect(result.amount).toBe(500);
        expect(result.formattedAmount).toContain('-');
      });

      it('formats correctly when viewing from destination account perspective (focusAccountId = accountBId)', () => {
        const result = parseCustomerTransaction(ownTransferTx, userAccountIds, accountBId);
        expect(result.typeLabel).toBe('Transfer');
        expect(result.description).toBe('Transfer from ••••4821');
        expect(result.accountLabel).toBe('Account ••••7310');
        expect(result.isPositive).toBe(true);
        expect(result.amount).toBe(500);
        expect(result.formattedAmount).toContain('+');
      });

      it('formats correctly in consolidated view (no focus account)', () => {
        const result = parseCustomerTransaction(ownTransferTx, userAccountIds);
        expect(result.typeLabel).toBe('Transfer');
        expect(result.description).toBe('Transfer to ••••7310');
        expect(result.accountLabel).toBe('Account ••••4821');
        expect(result.isPositive).toBe(false);
        expect(result.amount).toBe(500);
      });
    });

    // 2. Transfer to another customer (outgoing)
    describe('2. Transfer to another customer account', () => {
      const externalOutgoingTx: TransactionResponse = {
        id: 'tx-ext-out',
        transactionType: 'TRANSFER',
        status: 'COMPLETED',
        currency: 'INR',
        entries: [
          {
            id: 'e-1',
            accountId: accountAId,
            accountNumber: 'VLTR000000004821',
            entryType: 'DEBIT',
            amount: 250,
            currency: 'INR',
          },
          {
            id: 'e-2',
            accountId: null,
            accountNumber: '****9999',
            entryType: 'CREDIT',
            amount: 250,
            currency: 'INR',
          },
        ],
        createdAt: '2026-11-08T10:20:00Z',
        completedAt: '2026-11-08T10:20:00Z',
      };

      it('formats as outgoing transfer with masked counterparty account', () => {
        const result = parseCustomerTransaction(externalOutgoingTx, userAccountIds);
        expect(result.typeLabel).toBe('Transfer');
        expect(result.description).toBe('Transfer to ••••9999');
        expect(result.accountLabel).toBe('Account ••••4821');
        expect(result.isPositive).toBe(false);
        expect(result.amount).toBe(250);
        expect(result.formattedAmount).toContain('-');
      });
    });

    // 3. Transfer received from another customer (incoming)
    describe('3. Transfer received from another customer', () => {
      const externalIncomingTx: TransactionResponse = {
        id: 'tx-ext-in',
        transactionType: 'TRANSFER',
        status: 'COMPLETED',
        currency: 'INR',
        entries: [
          {
            id: 'e-1',
            accountId: accountAId,
            accountNumber: 'VLTR000000004821',
            entryType: 'CREDIT',
            amount: 750,
            currency: 'INR',
          },
          {
            id: 'e-2',
            accountId: null,
            accountNumber: '****3333',
            entryType: 'DEBIT',
            amount: 750,
            currency: 'INR',
          },
        ],
        createdAt: '2026-11-08T10:30:00Z',
        completedAt: '2026-11-08T10:30:00Z',
      };

      it('formats as incoming transfer with masked counterparty account', () => {
        const result = parseCustomerTransaction(externalIncomingTx, userAccountIds);
        expect(result.typeLabel).toBe('Transfer');
        expect(result.description).toBe('Transfer from ••••3333');
        expect(result.accountLabel).toBe('Account ••••4821');
        expect(result.isPositive).toBe(true);
        expect(result.amount).toBe(750);
        expect(result.formattedAmount).toContain('+');
      });
    });

    // 4. Deposit and withdrawal formatting
    describe('4. Deposit and withdrawal operations', () => {
      it('formats a cash deposit as positive Cash deposit', () => {
        const depositTx: TransactionResponse = {
          id: 'tx-dep',
          transactionType: 'DEPOSIT',
          status: 'COMPLETED',
          currency: 'INR',
          entries: [
            {
              id: 'e-1',
              accountId: accountAId,
              accountNumber: 'VLTR000000004821',
              entryType: 'CREDIT',
              amount: 1000,
              currency: 'INR',
            },
            {
              id: 'e-2',
              accountId: null,
              accountNumber: 'Cash/Bank Counterpart',
              entryType: 'DEBIT',
              amount: 1000,
              currency: 'INR',
            },
          ],
          createdAt: '2026-11-08T10:00:00Z',
          completedAt: '2026-11-08T10:00:00Z',
        };

        const result = parseCustomerTransaction(depositTx, userAccountIds);
        expect(result.typeLabel).toBe('Deposit');
        expect(result.description).toBe('Cash deposit');
        expect(result.accountLabel).toBe('Account ••••4821');
        expect(result.isPositive).toBe(true);
        expect(result.amount).toBe(1000);
        expect(result.formattedAmount).toContain('+');
      });

      it('formats a cash withdrawal as negative Cash withdrawal', () => {
        const withdrawTx: TransactionResponse = {
          id: 'tx-with',
          transactionType: 'WITHDRAWAL',
          status: 'COMPLETED',
          currency: 'INR',
          entries: [
            {
              id: 'e-1',
              accountId: accountAId,
              accountNumber: 'VLTR000000004821',
              entryType: 'DEBIT',
              amount: 400,
              currency: 'INR',
            },
            {
              id: 'e-2',
              accountId: null,
              accountNumber: 'Cash/Bank Counterpart',
              entryType: 'CREDIT',
              amount: 400,
              currency: 'INR',
            },
          ],
          createdAt: '2026-11-08T10:05:00Z',
          completedAt: '2026-11-08T10:05:00Z',
        };

        const result = parseCustomerTransaction(withdrawTx, userAccountIds);
        expect(result.typeLabel).toBe('Withdrawal');
        expect(result.description).toBe('Cash withdrawal');
        expect(result.accountLabel).toBe('Account ••••4821');
        expect(result.isPositive).toBe(false);
        expect(result.amount).toBe(400);
        expect(result.formattedAmount).toContain('-');
      });
    });
  });
});
