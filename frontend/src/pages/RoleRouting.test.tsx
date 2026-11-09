import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { AccessDeniedPage } from './AccessDeniedPage';
import { ProtectedRoute } from '../components/ProtectedRoute';
import * as AuthContextModule from '../context/AuthContext';

describe('Role-Aware Routing & Pages', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
  });

  it('renders AccessDeniedPage with current role information and action buttons', () => {
    vi.spyOn(AuthContextModule, 'useAuth').mockReturnValue({
      user: { userId: '111', email: 'auditor@example.com', role: 'AUDITOR' },
      isAuthenticated: true,
      isLoading: false,
      login: vi.fn(),
      register: vi.fn(),
      logout: vi.fn(),
      refreshUser: vi.fn(),
    });

    render(
      <MemoryRouter>
        <AccessDeniedPage />
      </MemoryRouter>
    );

    expect(screen.getByText('Access Restricted')).toBeDefined();
    expect(screen.getByText('auditor@example.com')).toBeDefined();
    expect(screen.getByText('AUDITOR')).toBeDefined();
    expect(screen.getByText('Go to Authorized Portal')).toBeDefined();
    expect(screen.getByText('Sign out')).toBeDefined();
  });

  it('ProtectedRoute redirects to /access-denied when user role is not permitted', () => {
    vi.spyOn(AuthContextModule, 'useAuth').mockReturnValue({
      user: { userId: '222', email: 'customer@example.com', role: 'CUSTOMER' },
      isAuthenticated: true,
      isLoading: false,
      login: vi.fn(),
      register: vi.fn(),
      logout: vi.fn(),
      refreshUser: vi.fn(),
    });

    render(
      <MemoryRouter initialEntries={['/auditor']}>
        <Routes>
          <Route
            path="/auditor"
            element={
              <ProtectedRoute allowedRoles={['AUDITOR', 'ADMIN']}>
                <div>Auditor Portal Secret</div>
              </ProtectedRoute>
            }
          />
          <Route path="/access-denied" element={<div>Access Denied Landed</div>} />
        </Routes>
      </MemoryRouter>
    );

    expect(screen.queryByText('Auditor Portal Secret')).toBeNull();
    expect(screen.getByText('Access Denied Landed')).toBeDefined();
  });

  it('ProtectedRoute permits access when user role matches allowed roles', () => {
    vi.spyOn(AuthContextModule, 'useAuth').mockReturnValue({
      user: { userId: '333', email: 'auditor@example.com', role: 'AUDITOR' },
      isAuthenticated: true,
      isLoading: false,
      login: vi.fn(),
      register: vi.fn(),
      logout: vi.fn(),
      refreshUser: vi.fn(),
    });

    render(
      <MemoryRouter initialEntries={['/auditor']}>
        <Routes>
          <Route
            path="/auditor"
            element={
              <ProtectedRoute allowedRoles={['AUDITOR', 'ADMIN']}>
                <div>Auditor Portal Secret</div>
              </ProtectedRoute>
            }
          />
          <Route path="/access-denied" element={<div>Access Denied Landed</div>} />
        </Routes>
      </MemoryRouter>
    );

    expect(screen.getByText('Auditor Portal Secret')).toBeDefined();
    expect(screen.queryByText('Access Denied Landed')).toBeNull();
  });
});
