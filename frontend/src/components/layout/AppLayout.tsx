import React, { useState } from 'react';
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { PortalSwitcher } from '../PortalSwitcher';
import { ChangePasswordModal } from '../ChangePasswordModal';

export const AppLayout: React.FC = () => {
  const { logout, user } = useAuth();
  const navigate = useNavigate();
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [passwordModalOpen, setPasswordModalOpen] = useState(false);

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  const navItems = [
    { label: 'Dashboard', path: '/dashboard' },
    { label: 'Accounts', path: '/accounts' },
    { label: 'Move money', path: '/move-money' },
    { label: 'History', path: '/history' },
    ...(user?.role === 'ADMIN'
      ? [
          { label: 'Admin Console', path: '/admin' },
          { label: 'Audit Trail', path: '/auditor' },
        ]
      : []),
  ];

  return (
    <div className="app-container">
      {/* Mobile top header bar */}
      <header className="mobile-header">
        <Link
          to="/"
          style={{
            fontWeight: 700,
            fontSize: '18px',
            color: '#111827',
            letterSpacing: '-0.02em',
            textDecoration: 'none'
          }}
        >
          Vaultora
        </Link>
        <button
          type="button"
          onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
          className="mobile-menu-btn"
          aria-label="Toggle navigation menu"
          aria-expanded={mobileMenuOpen}
        >
          {mobileMenuOpen ? '✕' : '☰'}
        </button>
      </header>

      {/* Sidebar (Desktop persistent, Mobile toggleable drawer) */}
      <aside className={`sidebar ${mobileMenuOpen ? 'mobile-open' : ''}`}>
        <div>
          <div className="sidebar-brand">
            <Link
              to="/"
              style={{
                fontWeight: 700,
                fontSize: '18px',
                color: '#111827',
                letterSpacing: '-0.02em',
                textDecoration: 'none'
              }}
            >
              Vaultora
            </Link>
          </div>

          <nav className="sidebar-nav">
            {navItems.map((item) => (
              <NavLink
                key={item.path}
                to={item.path}
                onClick={() => setMobileMenuOpen(false)}
                className={({ isActive }) => `sidebar-link ${isActive ? 'active' : ''}`}
              >
                {item.label}
              </NavLink>
            ))}
          </nav>
        </div>

        <div className="sidebar-footer">
          <div style={{ marginBottom: '12px' }}>
            <PortalSwitcher />
          </div>
          {user && (
            <div className="sidebar-user" title={user.email}>
              {user.email}
            </div>
          )}
          <button
            type="button"
            onClick={() => setPasswordModalOpen(true)}
            style={{
              width: '100%',
              textAlign: 'left',
              padding: '7px 12px',
              fontSize: '13px',
              color: '#374151',
              backgroundColor: '#F9FAFB',
              border: '1px solid #E5E7EB',
              borderRadius: '6px',
              cursor: 'pointer',
              marginBottom: '8px',
              fontWeight: 500,
              display: 'flex',
              alignItems: 'center',
              gap: '6px'
            }}
          >
            <span>🔒</span> Change password
          </button>
          <button
            type="button"
            onClick={handleLogout}
            className="sidebar-logout"
          >
            Sign out
          </button>
        </div>
      </aside>

      {/* Main Content Area */}
      <main className="main-content">
        <Outlet />
      </main>

      {/* Persistent Change Password Modal accessible across all portals */}
      <ChangePasswordModal
        isOpen={passwordModalOpen}
        onClose={() => setPasswordModalOpen(false)}
      />
    </div>
  );
};
