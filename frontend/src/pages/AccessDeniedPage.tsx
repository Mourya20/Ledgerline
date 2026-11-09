import React from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

export const AccessDeniedPage: React.FC = () => {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const handleGoHome = () => {
    if (!user) {
      navigate('/login');
    } else if (user.role === 'AUDITOR') {
      navigate('/auditor');
    } else if (user.role === 'ADMIN') {
      navigate('/admin');
    } else {
      navigate('/dashboard');
    }
  };

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  return (
    <div style={{
      minHeight: '100vh',
      backgroundColor: '#F8FAFC',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      padding: '24px',
      position: 'relative',
      fontFamily: 'Inter, system-ui, -apple-system, sans-serif'
    }}>
      {/* Top Left Brand Title linking to Home */}
      <header style={{
        position: 'absolute',
        top: '24px',
        left: '28px',
        zIndex: 10
      }}>
        <Link
          to="/"
          style={{
            fontSize: '20px',
            fontWeight: 700,
            color: '#111827',
            textDecoration: 'none',
            letterSpacing: '-0.02em',
            display: 'inline-flex',
            alignItems: 'center'
          }}
        >
          LedgerLine
        </Link>
      </header>
      <div className="card" style={{
        maxWidth: '480px',
        width: '100%',
        padding: '36px',
        textAlign: 'center'
      }}>
        <div style={{
          width: '56px',
          height: '56px',
          borderRadius: '50%',
          backgroundColor: '#FEE2E2',
          color: '#DC2626',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          fontSize: '24px',
          fontWeight: 700,
          margin: '0 auto 20px'
        }}>
          !
        </div>

        <h1 style={{ fontSize: '20px', fontWeight: 700, color: '#111827', marginBottom: '8px' }}>
          Access Restricted
        </h1>

        <p style={{ fontSize: '14px', color: '#6B7280', lineHeight: 1.5, marginBottom: '24px' }}>
          You do not have permission to view this page. Role-based access control enforces strict separation between Customer banking, Auditor inspection, and Administration.
        </p>

        {user && (
          <div style={{
            backgroundColor: '#F3F4F6',
            borderRadius: '8px',
            padding: '12px 16px',
            marginBottom: '24px',
            fontSize: '13px',
            color: '#374151',
            textAlign: 'left'
          }}>
            <div><strong>Authenticated as:</strong> {user.email}</div>
            <div style={{ marginTop: '4px' }}>
              <strong>Current Role:</strong>{' '}
              <span className="badge badge-gray" style={{ textTransform: 'uppercase', fontWeight: 600 }}>
                {user.role}
              </span>
            </div>
          </div>
        )}

        <div style={{ display: 'flex', gap: '12px', justifyContent: 'center' }}>
          <button
            type="button"
            className="btn btn-primary"
            onClick={handleGoHome}
          >
            Go to Authorized Portal
          </button>
          <button
            type="button"
            className="btn btn-secondary"
            onClick={handleLogout}
          >
            Sign out
          </button>
        </div>
      </div>
    </div>
  );
};
