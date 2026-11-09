import { apiClient } from './client';
import type { AccountBalanceResponse, AccountResponse, CreateAccountRequest } from './types';

export const accountsApi = {
  getAccounts: async (customerEmail?: string): Promise<AccountResponse[]> => {
    const query = customerEmail ? `?customerEmail=${encodeURIComponent(customerEmail)}` : '';
    return apiClient<AccountResponse[]>(`/api/v1/accounts${query}`, {
      method: 'GET',
    });
  },

  getAccountById: async (accountId: string): Promise<AccountResponse> => {
    return apiClient<AccountResponse>(`/api/v1/accounts/${accountId}`, {
      method: 'GET',
    });
  },

  getAccountBalance: async (accountId: string): Promise<AccountBalanceResponse> => {
    return apiClient<AccountBalanceResponse>(`/api/v1/accounts/${accountId}/balance`, {
      method: 'GET',
    });
  },

  createAccount: async (data: CreateAccountRequest = { currency: 'INR' }): Promise<AccountResponse> => {
    return apiClient<AccountResponse>('/api/v1/accounts', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  },

  closeAccount: async (accountId: string): Promise<AccountResponse> => {
    return apiClient<AccountResponse>(`/api/v1/accounts/${accountId}`, {
      method: 'DELETE',
    });
  },
};
