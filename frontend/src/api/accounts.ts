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

  downloadStatement: async (
    accountId: string,
    format: 'pdf' | 'csv' = 'pdf',
    startDate?: string,
    endDate?: string
  ): Promise<void> => {
    const params = new URLSearchParams();
    params.set('format', format);
    if (startDate) params.set('startDate', startDate);
    if (endDate) params.set('endDate', endDate);

    const res = await fetch(`/api/v1/accounts/${accountId}/statement?${params.toString()}`, {
      method: 'GET',
      credentials: 'include',
    });
    if (!res.ok) {
      throw new Error(`Failed to download statement: ${res.statusText}`);
    }
    const blob = await res.blob();
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `statement-${accountId}.${format}`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    window.URL.revokeObjectURL(url);
  },
};
