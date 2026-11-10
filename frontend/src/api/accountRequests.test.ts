import { describe, it, expect, vi, beforeEach } from 'vitest';
import { accountRequestsApi } from './accountRequests';
import { clearCsrfToken } from './client';

describe('accountRequestsApi', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    clearCsrfToken();
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/;';
  });

  it('submits an account opening request for INR', async () => {
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
        json: () =>
          Promise.resolve({
            id: 'req-1',
            userId: 'user-1',
            userEmail: 'cust@vaultora.com',
            currency: 'INR',
            status: 'PENDING',
            createdAt: '2026-11-10T10:00:00Z',
            updatedAt: '2026-11-10T10:00:00Z',
          }),
      });
    });

    vi.stubGlobal('fetch', mockFetch);

    const result = await accountRequestsApi.createRequest('INR');
    expect(result.id).toBe('req-1');
    expect(result.status).toBe('PENDING');
    expect(result.currency).toBe('INR');

    const callArgs = mockFetch.mock.calls.find((c) => (c[0] as string).includes('/api/v1/account-requests'));
    expect(callArgs).toBeDefined();
    expect(callArgs![1].method).toBe('POST');
    expect(JSON.parse(callArgs![1].body as string)).toEqual({ currency: 'INR' });
  });

  it('retrieves account requests with optional status filter', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      headers: new Headers({ 'content-type': 'application/json' }),
      json: () =>
        Promise.resolve([
          { id: 'req-1', status: 'PENDING', currency: 'INR' },
        ]),
    });

    vi.stubGlobal('fetch', mockFetch);

    const results = await accountRequestsApi.getRequests('PENDING');
    expect(results).toHaveLength(1);
    expect(results[0].status).toBe('PENDING');

    const callArgs = mockFetch.mock.calls[0];
    expect(callArgs[0]).toContain('/api/v1/account-requests?status=PENDING');
    expect(callArgs[1].method).toBe('GET');
  });

  it('approves an account opening request with admin notes', async () => {
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
        json: () =>
          Promise.resolve({
            id: 'req-1',
            status: 'APPROVED',
            accountNumber: 'VLTR12345678',
            reviewedBy: 'admin1@ledgerline.com',
            adminNotes: 'Verified KYC',
          }),
      });
    });

    vi.stubGlobal('fetch', mockFetch);

    const result = await accountRequestsApi.approveRequest('req-1', 'Verified KYC');
    expect(result.status).toBe('APPROVED');
    expect(result.accountNumber).toBe('VLTR12345678');

    const approveCall = mockFetch.mock.calls.find((c) =>
      (c[0] as string).includes('/api/v1/account-requests/req-1/approve')
    );
    expect(approveCall).toBeDefined();
    expect(JSON.parse(approveCall![1].body as string)).toEqual({ adminNotes: 'Verified KYC' });
  });

  it('rejects an account opening request with admin notes', async () => {
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
        json: () =>
          Promise.resolve({
            id: 'req-1',
            status: 'REJECTED',
            reviewedBy: 'admin1@ledgerline.com',
            adminNotes: 'Incomplete documentation',
          }),
      });
    });

    vi.stubGlobal('fetch', mockFetch);

    const result = await accountRequestsApi.rejectRequest('req-1', 'Incomplete documentation');
    expect(result.status).toBe('REJECTED');

    const rejectCall = mockFetch.mock.calls.find((c) =>
      (c[0] as string).includes('/api/v1/account-requests/req-1/reject')
    );
    expect(rejectCall).toBeDefined();
    expect(JSON.parse(rejectCall![1].body as string)).toEqual({ adminNotes: 'Incomplete documentation' });
  });
});
