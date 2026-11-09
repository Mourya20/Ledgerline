import { describe, it, expect, vi, beforeEach } from 'vitest';
import { apiClient, ApiException, clearCsrfToken } from './client';

describe('apiClient', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    clearCsrfToken();
    // Clear document.cookie if set
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/;';
  });

  it('attaches Idempotency-Key and Content-Type header on POST', async () => {
    const mockFetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/api/v1/auth/csrf')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ token: 'mock-csrf-token-123' }),
          headers: new Headers({ 'content-type': 'application/json' }),
        });
      }
      return Promise.resolve({
        ok: true,
        headers: new Headers({ 'content-type': 'application/json' }),
        json: () => Promise.resolve({ status: 'SUCCESS' }),
      });
    });

    vi.stubGlobal('fetch', mockFetch);

    const result = await apiClient<{ status: string }>('/api/v1/transactions/deposit', {
      method: 'POST',
      body: JSON.stringify({ amount: 100 }),
      idempotencyKey: 'test-key-uuid-1',
    });

    expect(result).toEqual({ status: 'SUCCESS' });
    expect(mockFetch).toHaveBeenCalledTimes(2); // 1 for CSRF token fetch, 1 for actual call

    const secondCallHeaders = mockFetch.mock.calls[1][1].headers as Headers;
    expect(secondCallHeaders.get('Content-Type')).toBe('application/json');
    expect(secondCallHeaders.get('Idempotency-Key')).toBe('test-key-uuid-1');
    expect(secondCallHeaders.get('X-XSRF-TOKEN')).toBe('mock-csrf-token-123');
  });

  it('throws ApiException with server error message on failure', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 409,
      statusText: 'Conflict',
      headers: new Headers({ 'content-type': 'application/json' }),
      text: () =>
        Promise.resolve(
          JSON.stringify({
            status: 409,
            error: 'Conflict',
            message: 'Idempotency conflict detected for key',
          })
        ),
    });

    vi.stubGlobal('fetch', mockFetch);

    await expect(
      apiClient('/api/v1/transactions/deposit', {
        method: 'POST',
        body: JSON.stringify({ amount: 100 }),
      })
    ).rejects.toThrowError(ApiException);
  });
});
