import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AppLayout } from './AppLayout';

// Mock useAuth
vi.mock('../../context/AuthContext', () => ({
  useAuth: () => ({
    user: { userId: 'u-1234', email: 'test@example.com', role: 'CUSTOMER' },
    isAuthenticated: true,
    isLoading: false,
    logout: vi.fn(),
  }),
}));

describe('AppLayout Component', () => {
  afterEach(() => {
    cleanup();
  });
  it('renders all core navigation links', () => {
    render(
      <MemoryRouter>
        <AppLayout />
      </MemoryRouter>
    );

    expect(screen.getByText('Dashboard')).toBeDefined();
    expect(screen.getByText('Accounts')).toBeDefined();
    expect(screen.getByText('Move money')).toBeDefined();
    expect(screen.getByText('History')).toBeDefined();
    expect(screen.getByText('Sign out')).toBeDefined();
    expect(screen.getByText('test@example.com')).toBeDefined();
  });

  it('toggles mobile menu button and updates aria-expanded attribute', () => {
    render(
      <MemoryRouter>
        <AppLayout />
      </MemoryRouter>
    );

    const toggleButton = screen.getByRole('button', { name: /toggle navigation menu/i });
    expect(toggleButton.getAttribute('aria-expanded')).toBe('false');
    expect(toggleButton.textContent).toBe('☰');

    // Click toggle to open
    fireEvent.click(toggleButton);
    expect(toggleButton.getAttribute('aria-expanded')).toBe('true');
    expect(toggleButton.textContent).toBe('✕');

    // Click toggle to close
    fireEvent.click(toggleButton);
    expect(toggleButton.getAttribute('aria-expanded')).toBe('false');
    expect(toggleButton.textContent).toBe('☰');
  });

  it('renders LedgerLine title brand linking to root home', () => {
    render(
      <MemoryRouter>
        <AppLayout />
      </MemoryRouter>
    );

    const brandLinks = screen.getAllByRole('link', { name: /ledgerline/i });
    expect(brandLinks.length).toBeGreaterThan(0);
    expect(brandLinks[0].getAttribute('href')).toBe('/');
  });

  it('closes mobile menu when a navigation link is clicked', () => {
    render(
      <MemoryRouter>
        <AppLayout />
      </MemoryRouter>
    );

    const toggleButton = screen.getByRole('button', { name: /toggle navigation menu/i });
    fireEvent.click(toggleButton);
    expect(toggleButton.getAttribute('aria-expanded')).toBe('true');

    // Click on Accounts link
    const accountsLink = screen.getByText('Accounts');
    fireEvent.click(accountsLink);

    // Menu should close
    expect(toggleButton.getAttribute('aria-expanded')).toBe('false');
  });
});
