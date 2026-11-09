import type { ApiError } from './types';

const BASE_URL = import.meta.env.VITE_API_BASE_URL || '';

let cachedCsrfToken: string | null = null;

/**
 * Extracts a cookie value by name from document.cookie
 */
function getCookie(name: string): string | null {
  if (typeof document === 'undefined') return null;
  const match = document.cookie.match(new RegExp('(^|;\\s*)(' + name + ')=([^;]*)'));
  return match ? decodeURIComponent(match[3]) : null;
}

/**
 * Retrieves the CSRF token from the server or cookie
 */
export async function fetchCsrfToken(): Promise<string> {
  const cookieToken = getCookie('XSRF-TOKEN');
  if (cookieToken) {
    cachedCsrfToken = cookieToken;
    return cookieToken;
  }

  try {
    const res = await fetch(`${BASE_URL}/api/v1/auth/csrf`, {
      method: 'GET',
      credentials: 'include',
    });
    if (res.ok) {
      const data = await res.json();
      if (data && data.token) {
        cachedCsrfToken = data.token;
        return data.token;
      }
    }
  } catch (err) {
    console.warn('Failed to fetch CSRF token:', err);
  }

  // Fallback to cookie check again
  return getCookie('XSRF-TOKEN') || cachedCsrfToken || '';
}

export function clearCsrfToken(): void {
  cachedCsrfToken = null;
}

export interface RequestOptions extends RequestInit {
  idempotencyKey?: string;
}

export class ApiException extends Error {
  status: number;
  data?: ApiError;
  retryAfter?: number;

  constructor(status: number, message: string, data?: ApiError, retryAfter?: number) {
    super(message);
    this.name = 'ApiException';
    this.status = status;
    this.data = data;
    this.retryAfter = retryAfter;
  }
}

/**
 * Universal authenticated fetch wrapper for the Vaultora backend
 */
export async function apiClient<T>(endpoint: string, options: RequestOptions = {}): Promise<T> {
  const method = (options.method || 'GET').toUpperCase();
  const headers = new Headers(options.headers || {});

  // Add JSON content-type if sending body and not already specified
  if (options.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  // Attach Idempotency-Key if provided
  if (options.idempotencyKey) {
    headers.set('Idempotency-Key', options.idempotencyKey);
  }

  // For state-changing methods, ensure CSRF token is attached
  const isMutating = ['POST', 'PUT', 'DELETE', 'PATCH'].includes(method);
  if (isMutating) {
    let token = getCookie('XSRF-TOKEN') || cachedCsrfToken;
    if (!token) {
      token = await fetchCsrfToken();
    }
    if (token) {
      headers.set('X-XSRF-TOKEN', token);
    }
  }

  const url = endpoint.startsWith('http') ? endpoint : `${BASE_URL}${endpoint}`;

  const response = await fetch(url, {
    ...options,
    method,
    headers,
    credentials: 'include',
  });

  // Keep cookie token in sync if refreshed in response
  const cookieToken = getCookie('XSRF-TOKEN');
  if (cookieToken) {
    cachedCsrfToken = cookieToken;
  }

  if (!response.ok) {
    let errorData: ApiError | undefined;
    let errorMessage = `HTTP error ${response.status}: ${response.statusText}`;

    try {
      const text = await response.text();
      if (text) {
        errorData = JSON.parse(text);
        if (errorData?.message) {
          errorMessage = errorData.message;
        } else if (errorData?.error) {
          errorMessage = errorData.error;
        }
      }
    } catch {
      // response body was not JSON, keep default message
    }

    const retryHeader = response.headers.get('Retry-After');
    const retryAfter = retryHeader ? parseInt(retryHeader, 10) : undefined;

    throw new ApiException(response.status, errorMessage, errorData, retryAfter);
  }

  // Check if response has content before parsing JSON
  const contentType = response.headers.get('content-type');
  if (contentType && (contentType.includes('application/json') || contentType.includes('json'))) {
    return (await response.json()) as T;
  }

  return ({} as T);
}
