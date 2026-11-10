import { apiClient } from './client';
import type { AccountRequest, AccountRequestStatus, ReviewAccountRequest } from './types';

export const accountRequestsApi = {
  createRequest: async (currency: string = 'INR'): Promise<AccountRequest> => {
    return apiClient<AccountRequest>('/api/v1/account-requests', {
      method: 'POST',
      body: JSON.stringify({ currency }),
    });
  },

  getRequests: async (status?: AccountRequestStatus): Promise<AccountRequest[]> => {
    const url = status
      ? `/api/v1/account-requests?status=${encodeURIComponent(status)}`
      : '/api/v1/account-requests';
    return apiClient<AccountRequest[]>(url, {
      method: 'GET',
    });
  },

  approveRequest: async (requestId: string, adminNotes?: string): Promise<AccountRequest> => {
    const body: ReviewAccountRequest = adminNotes ? { adminNotes } : {};
    return apiClient<AccountRequest>(`/api/v1/account-requests/${requestId}/approve`, {
      method: 'POST',
      body: JSON.stringify(body),
    });
  },

  rejectRequest: async (requestId: string, adminNotes?: string): Promise<AccountRequest> => {
    const body: ReviewAccountRequest = adminNotes ? { adminNotes } : {};
    return apiClient<AccountRequest>(`/api/v1/account-requests/${requestId}/reject`, {
      method: 'POST',
      body: JSON.stringify(body),
    });
  },
};
