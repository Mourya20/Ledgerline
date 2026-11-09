import { apiClient } from './client';
import type {
  AdminStatsResponse,
  UserSummaryResponse,
  UserStatus,
  AccountStatus,
  AccountResponse,
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
};
