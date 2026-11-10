import { apiClient, clearCsrfToken, fetchCsrfToken } from './client';
import type {
  LoginRequest,
  RegisterRequest,
  UserResponse,
  ChangePasswordRequest,
  ForgotPasswordRequest,
  ResetPasswordRequest,
  VerifyEmailRequest,
  ResendVerificationRequest
} from './types';

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

  changePassword: async (data: ChangePasswordRequest): Promise<{ message: string }> => {
    return apiClient<{ message: string }>('/api/v1/auth/password', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  },

  forgotPassword: async (data: ForgotPasswordRequest): Promise<{ message: string }> => {
    return apiClient<{ message: string }>('/api/v1/auth/forgot-password', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  },

  resetPassword: async (data: ResetPasswordRequest): Promise<{ message: string }> => {
    return apiClient<{ message: string }>('/api/v1/auth/reset-password', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  },

  verifyEmail: async (data: VerifyEmailRequest): Promise<{ message: string }> => {
    return apiClient<{ message: string }>('/api/v1/auth/verify-email', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  },

  resendVerification: async (data: ResendVerificationRequest): Promise<{ message: string }> => {
    return apiClient<{ message: string }>('/api/v1/auth/resend-verification', {
      method: 'POST',
      body: JSON.stringify(data),
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
