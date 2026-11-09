import React from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

interface PortalOption {
  label: string;
  path: string;
  matches: (pathname: string) => boolean;
}

/**
 * Portal navigation bar for the currently authenticated user.
 * Strictly navigates between authorized portals without re-authenticating
 * or storing hardcoded credentials.
 *
 * Availability:
 * - CUSTOMER: Customer Portal only
 * - AUDITOR: Auditor Portal only
 * - ADMIN: Admin Console and Auditor Portal (authorized to inspect audit logs)
 *
 * Active indicator is dynamically determined by location.pathname.
 */
export const PortalSwitcher: React.FC = () => {
  const { user } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  if (!user) return null;

  const availablePortals: PortalOption[] = [];

  if (user.role === 'ADMIN') {
    availablePortals.push({
      label: 'Admin Console',
      path: '/admin',
      matches: (p) => p === '/admin' || p.startsWith('/admin/'),
    });
    availablePortals.push({
      label: 'Auditor Portal',
      path: '/auditor',
      matches: (p) => p === '/auditor' || p.startsWith('/auditor/'),
    });
  } else if (user.role === 'AUDITOR') {
    availablePortals.push({
      label: 'Auditor Portal',
      path: '/auditor',
      matches: (p) => p === '/auditor' || p.startsWith('/auditor/'),
    });
  } else if (user.role === 'CUSTOMER') {
    availablePortals.push({
      label: 'Customer Portal',
      path: '/dashboard',
      matches: (p) => p === '/dashboard' || p === '/accounts' || p === '/move-money' || p === '/history',
    });
  }

  if (availablePortals.length <= 1) {
    const single = availablePortals[0];
    return (
      <div
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          backgroundColor: '#F1F5F9',
          padding: '4px 10px',
          borderRadius: '6px',
          border: '1px solid #E2E8F0',
          fontSize: '12px',
          color: '#475569',
          fontWeight: 600,
        }}
      >
        <span style={{ color: '#94A3B8', marginRight: '6px', fontSize: '11px', textTransform: 'uppercase' }}>
          Portal:
        </span>
        {single ? single.label : user.role}
      </div>
    );
  }

  return (
    <div
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        backgroundColor: '#F1F5F9',
        padding: '3px',
        borderRadius: '8px',
        border: '1px solid #E2E8F0',
        gap: '2px',
        fontSize: '12px',
      }}
    >
      <span style={{ padding: '0 8px', color: '#64748B', fontWeight: 600, fontSize: '11px', textTransform: 'uppercase' }}>
        Portal:
      </span>
      {availablePortals.map((p) => {
        const isActive = p.matches(location.pathname);
        return (
          <button
            key={p.path}
            type="button"
            onClick={() => {
              if (!isActive) {
                navigate(p.path);
              }
            }}
            style={{
              padding: '4px 10px',
              borderRadius: '6px',
              border: 'none',
              cursor: isActive ? 'default' : 'pointer',
              fontWeight: isActive ? 600 : 500,
              fontSize: '12px',
              backgroundColor: isActive ? '#FFFFFF' : 'transparent',
              color: isActive ? '#0F766E' : '#475569',
              boxShadow: isActive ? '0 1px 2px rgba(0, 0, 0, 0.05)' : 'none',
              transition: 'all 0.15s ease',
            }}
          >
            {p.label}
          </button>
        );
      })}
    </div>
  );
};
