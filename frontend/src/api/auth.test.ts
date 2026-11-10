import { describe, it, expect, vi, beforeEach } from 'vitest';
import { authApi } from './auth';
import { clearCsrfToken } from './client';

describe('authApi password change', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    clearCsrfToken();
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/;';
  });

  it('calls POST /api/v1/auth/password with current and new password', async () => {
    const mockFetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/api/v1/auth/csrf')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ token: 'mock-csrf' }),
          headers: new Headers({ 'content-type': 'application/json' }),
        });
      }
      return Promise.resolve({
        ok: true,
        headers: new Headers({ 'content-type': 'application/json' }),
        json: () => Promise.resolve({ message: 'Password changed successfully' }),
      });
    });

    vi.stubGlobal('fetch', mockFetch);

    const result = await authApi.changePassword({
      currentPassword: 'OldPassword123!',
      newPassword: 'BrandNewPassword123!',
    });

    expect(result.message).toBe('Password changed successfully');

    const callArgs = mockFetch.mock.calls.find((c) =>
      (c[0] as string).includes('/api/v1/auth/password')
    );
    expect(callArgs).toBeDefined();
    expect(callArgs![1].method).toBe('POST');
    expect(JSON.parse(callArgs![1].body as string)).toEqual({
      currentPassword: 'OldPassword123!',
      newPassword: 'BrandNewPassword123!',
    });
  });
});
