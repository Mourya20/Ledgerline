import { apiClient, clearCsrfToken, fetchCsrfToken } from './client';
import type { LoginRequest, RegisterRequest, UserResponse } from './types';

export const authApi = {
  getCsrf: async () => {
    return fetchCsrfToken();
  },

  register: async (data: RegisterRequest): Promise<UserResponse> => {
    return apiClient<UserResponse>('/api/v1/auth/register', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  },

  login: async (data: LoginRequest): Promise<UserResponse> => {
    return apiClient<UserResponse>('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  },

  getMe: async (): Promise<UserResponse> => {
    return apiClient<UserResponse>('/api/v1/auth/me', {
      method: 'GET',
    });
  },

  logout: async (): Promise<void> => {
    try {
      await apiClient('/api/v1/auth/logout', {
        method: 'POST',
      });
    } finally {
      clearCsrfToken();
    }
  },
};
