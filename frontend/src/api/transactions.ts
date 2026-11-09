import { apiClient } from './client';
import type {
  DepositRequest,
  Page,
  TransactionResponse,
  TransferRequest,
  WithdrawRequest,
} from './types';

export interface GetTransactionsParams {
  accountId?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export const transactionsApi = {
  getTransactions: async (params: GetTransactionsParams = {}): Promise<Page<TransactionResponse>> => {
    const query = new URLSearchParams();
    if (params.accountId) query.set('accountId', params.accountId);
    if (params.page !== undefined) query.set('page', params.page.toString());
    if (params.size !== undefined) query.set('size', params.size.toString());
    if (params.sort) query.set('sort', params.sort);

    const qs = query.toString();
    const endpoint = `/api/v1/transactions${qs ? `?${qs}` : ''}`;
    return apiClient<Page<TransactionResponse>>(endpoint, {
      method: 'GET',
    });
  },

  getTransactionById: async (transactionId: string): Promise<TransactionResponse> => {
    return apiClient<TransactionResponse>(`/api/v1/transactions/${transactionId}`, {
      method: 'GET',
    });
  },

  deposit: async (data: DepositRequest, idempotencyKey: string): Promise<TransactionResponse> => {
    return apiClient<TransactionResponse>('/api/v1/transactions/deposit', {
      method: 'POST',
      body: JSON.stringify(data),
      idempotencyKey,
    });
  },

  withdraw: async (data: WithdrawRequest, idempotencyKey: string): Promise<TransactionResponse> => {
    return apiClient<TransactionResponse>('/api/v1/transactions/withdraw', {
      method: 'POST',
      body: JSON.stringify(data),
      idempotencyKey,
    });
  },

  transfer: async (data: TransferRequest, idempotencyKey: string): Promise<TransactionResponse> => {
    return apiClient<TransactionResponse>('/api/v1/transactions/transfer', {
      method: 'POST',
      body: JSON.stringify(data),
      idempotencyKey,
    });
  },
};
