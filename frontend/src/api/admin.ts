import { apiClient } from './client';
import type {
  AdminStatsResponse,
  UserSummaryResponse,
  UserStatus,
  AccountStatus,
  AccountResponse,
  TransactionLimitResponse,
  UpdateTransactionLimitRequest,
  MakerCheckerResponse,
  MakerCheckerStatus,
  AdminTransferRequest
} from './types';

export const adminApi = {
  getStats: async (): Promise<AdminStatsResponse> => {
    return apiClient<AdminStatsResponse>('/api/v1/admin/stats', {
      method: 'GET',
    });
  },

  getUsers: async (): Promise<UserSummaryResponse[]> => {
    return apiClient<UserSummaryResponse[]>('/api/v1/admin/users', {
      method: 'GET',
    });
  },

  getUserAccounts: async (userId: string): Promise<AccountResponse[]> => {
    return apiClient<AccountResponse[]>(`/api/v1/admin/users/${userId}/accounts`, {
      method: 'GET',
    });
  },

  updateUserStatus: async (userId: string, status: UserStatus): Promise<UserSummaryResponse> => {
    return apiClient<UserSummaryResponse>(`/api/v1/admin/users/${userId}/status`, {
      method: 'PATCH',
      body: JSON.stringify({ status }),
    });
  },

  updateAccountStatus: async (accountId: string, status: AccountStatus): Promise<AccountResponse> => {
    return apiClient<AccountResponse>(`/api/v1/admin/accounts/${accountId}/status`, {
      method: 'PATCH',
      body: JSON.stringify({ status }),
    });
  },

  assignUserPassword: async (userId: string, password: string): Promise<{ message: string }> => {
    return apiClient<{ message: string }>(`/api/v1/admin/users/${userId}/password`, {
      method: 'POST',
      body: JSON.stringify({ password }),
    });
  },

  getLimits: async (): Promise<TransactionLimitResponse> => {
    return apiClient<TransactionLimitResponse>('/api/v1/admin/settings/limits', {
      method: 'GET',
    });
  },

  updateLimits: async (data: UpdateTransactionLimitRequest): Promise<TransactionLimitResponse> => {
    return apiClient<TransactionLimitResponse>('/api/v1/admin/settings/limits', {
      method: 'PUT',
      body: JSON.stringify(data),
    });
  },

  getMakerCheckerRequests: async (status?: MakerCheckerStatus): Promise<MakerCheckerResponse[]> => {
    const query = status ? `?status=${encodeURIComponent(status)}` : '';
    return apiClient<MakerCheckerResponse[]>(`/api/v1/admin/maker-checker/requests${query}`, {
      method: 'GET',
    });
  },

  approveMakerCheckerRequest: async (requestId: string): Promise<MakerCheckerResponse> => {
    return apiClient<MakerCheckerResponse>(`/api/v1/admin/maker-checker/requests/${requestId}/approve`, {
      method: 'POST',
    });
  },

  rejectMakerCheckerRequest: async (requestId: string, rejectionReason?: string): Promise<MakerCheckerResponse> => {
    return apiClient<MakerCheckerResponse>(`/api/v1/admin/maker-checker/requests/${requestId}/reject`, {
      method: 'POST',
      body: JSON.stringify(rejectionReason ? { rejectionReason } : {}),
    });
  },

  initiateReversal: async (transactionId: string, reason: string): Promise<MakerCheckerResponse> => {
    return apiClient<MakerCheckerResponse>(`/api/v1/admin/transactions/${transactionId}/reverse`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    });
  },

  initiateTransfer: async (data: AdminTransferRequest): Promise<MakerCheckerResponse> => {
    return apiClient<MakerCheckerResponse>('/api/v1/admin/maker-checker/requests/transfer', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  },
};
