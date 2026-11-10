import { apiClient } from './client';
import type {
  Page,
  AuditLogItem,
  AuditorTransactionResponse,
  LedgerIntegrityResponse,
  TransactionStatus
} from './types';

export interface GetAuditLogsParams {
  page?: number;
  size?: number;
  sort?: string;
}

export interface GetAuditTransactionsParams {
  startDate?: string;
  endDate?: string;
  userEmail?: string;
  accountId?: string;
  minAmount?: number;
  maxAmount?: number;
  type?: string;
  status?: TransactionStatus;
  page?: number;
  size?: number;
  sort?: string;
}

export const auditApi = {
  getLogs: async (params: GetAuditLogsParams = {}): Promise<Page<AuditLogItem>> => {
    const query = new URLSearchParams();
    if (params.page !== undefined) query.set('page', params.page.toString());
    if (params.size !== undefined) query.set('size', params.size.toString());
    if (params.sort) query.set('sort', params.sort);

    const queryString = query.toString();
    const endpoint = queryString ? `/api/v1/audit/logs?${queryString}` : '/api/v1/audit/logs';
    return apiClient<Page<AuditLogItem>>(endpoint, { method: 'GET' });
  },

  getTransactions: async (params: GetAuditTransactionsParams = {}): Promise<Page<AuditorTransactionResponse>> => {
    const query = new URLSearchParams();
    if (params.startDate) query.set('startDate', params.startDate);
    if (params.endDate) query.set('endDate', params.endDate);
    if (params.userEmail) query.set('userEmail', params.userEmail);
    if (params.accountId) query.set('accountId', params.accountId);
    if (params.minAmount !== undefined) query.set('minAmount', params.minAmount.toString());
    if (params.maxAmount !== undefined) query.set('maxAmount', params.maxAmount.toString());
    if (params.type) query.set('type', params.type);
    if (params.status) query.set('status', params.status);
    if (params.page !== undefined) query.set('page', params.page.toString());
    if (params.size !== undefined) query.set('size', params.size.toString());
    if (params.sort) query.set('sort', params.sort);

    const queryString = query.toString();
    const endpoint = queryString ? `/api/v1/audit/transactions?${queryString}` : '/api/v1/audit/transactions';
    return apiClient<Page<AuditorTransactionResponse>>(endpoint, { method: 'GET' });
  },

  exportTransactionsCsv: async (params: GetAuditTransactionsParams = {}): Promise<void> => {
    const query = new URLSearchParams();
    if (params.startDate) query.set('startDate', params.startDate);
    if (params.endDate) query.set('endDate', params.endDate);
    if (params.userEmail) query.set('userEmail', params.userEmail);
    if (params.accountId) query.set('accountId', params.accountId);
    if (params.minAmount !== undefined) query.set('minAmount', params.minAmount.toString());
    if (params.maxAmount !== undefined) query.set('maxAmount', params.maxAmount.toString());
    if (params.type) query.set('type', params.type);
    if (params.status) query.set('status', params.status);

    const res = await fetch(`/api/v1/audit/transactions/export?${query.toString()}`, {
      method: 'GET',
      credentials: 'include',
    });
    if (!res.ok) {
      throw new Error(`Failed to export transactions CSV: ${res.statusText}`);
    }
    const blob = await res.blob();
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'vaultora-audit-transactions.csv';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    window.URL.revokeObjectURL(url);
  },

  getLedgerIntegrity: async (): Promise<LedgerIntegrityResponse> => {
    return apiClient<LedgerIntegrityResponse>('/api/v1/audit/ledger-integrity', {
      method: 'GET',
    });
  },
};
