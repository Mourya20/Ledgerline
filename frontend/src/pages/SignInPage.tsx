import React, { useState } from 'react';
import { Link, useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { ApiException } from '../api/client';

export const SignInPage: React.FC = () => {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  // Check if directed from registration with success flag
  const registrationSuccess = (location.state as { registered?: boolean })?.registered;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    if (!email.trim() || !password) {
      setError('Please enter both email and password.');
      return;
    }

    setIsSubmitting(true);
    try {
      const loggedInUser = await login({ email: email.trim(), password });
      if (loggedInUser.role === 'AUDITOR') {
        navigate('/auditor');
      } else if (loggedInUser.role === 'ADMIN') {
        navigate('/admin');
      } else {
        navigate('/dashboard');
      }
    } catch (err) {
      if (err instanceof ApiException) {
        if (err.status === 429) {
          const waitTime = err.retryAfter ? `${err.retryAfter} seconds` : 'the cooldown window';
          setError(`Too many failed login attempts. Please wait ${waitTime} before trying again.`);
        } else if (err.status === 401 || err.status === 403) {
          if (err.message && err.message.toLowerCase().includes('verification')) {
            setError(err.message);
          } else {
            setError('Invalid email or password.');
          }
        } else {
          setError(err.message || 'Login failed. Please check your credentials.');
        }
      } else {
        setError('Network error or server unreachable. Please try again.');
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  const handlePrefill = (sampleEmail: string, samplePassword: string) => {
    setEmail(sampleEmail);
    setPassword(samplePassword);
    setError(null);
  };

  return (
    <div style={{
      minHeight: '100vh',
      backgroundColor: '#F8FAFC',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      padding: '40px 24px',
      position: 'relative'
    }}>
      {/* Top Left Brand Title */}
      <header style={{
        position: 'absolute',
        top: '24px',
        left: '28px',
        zIndex: 10
      }}>
        <Link
          to="/"
          style={{
            fontSize: '22px',
            fontWeight: 800,
            color: '#0F172A',
            textDecoration: 'none',
            letterSpacing: '-0.03em',
            display: 'inline-flex',
            alignItems: 'center',
            gap: '8px'
          }}
        >
          <span style={{
            width: '28px',
            height: '28px',
            borderRadius: '6px',
            backgroundColor: '#0D6E64',
            color: '#FFFFFF',
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontSize: '14px',
            fontWeight: 700
          }}>
            V
          </span>
          Vaultora
        </Link>
      </header>

      {/* Main Home Responsive Layout */}
      <div style={{
        width: '100%',
        maxWidth: '1060px',
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fit, minmax(340px, 1fr))',
        gap: '48px',
        alignItems: 'center',
        marginTop: '32px'
      }}>
        {/* Left Side: Static Home Branding & Architecture Highlights */}
        <div style={{ padding: '12px 0' }}>
          <div style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: '6px',
            backgroundColor: '#E6F4F1',
            color: '#0D6E64',
            padding: '4px 12px',
            borderRadius: '9999px',
            fontSize: '12px',
            fontWeight: 600,
            marginBottom: '16px',
            letterSpacing: '0.02em'
          }}>
            <span>🛡️</span>
            <span>Secure Banking & Transaction Platform</span>
          </div>

          <h2 style={{
            fontSize: '32px',
            fontWeight: 800,
            lineHeight: 1.2,
            color: '#0F172A',
            letterSpacing: '-0.03em',
            marginBottom: '14px'
          }}>
            Institutional financial engineering with zero-sum precision.
          </h2>

          <p style={{
            fontSize: '15px',
            color: '#475569',
            lineHeight: 1.6,
            marginBottom: '28px'
          }}>
            Vaultora enforces strict mathematical balance invariants, transaction-scoped advisory locks, and non-repudiable audit logs across all financial movements.
          </p>

          <div style={{ display: 'flex', flexDirection: 'column', gap: '14px', marginBottom: '28px' }}>
            <div style={{ display: 'flex', alignItems: 'flex-start', gap: '12px' }}>
              <div style={{
                width: '34px',
                height: '34px',
                borderRadius: '8px',
                backgroundColor: '#ECFDF5',
                color: '#0D6E64',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: '16px',
                flexShrink: 0
              }}>
                ⚖️
              </div>
              <div>
                <div style={{ fontSize: '14px', fontWeight: 600, color: '#0F172A' }}>Mathematical Zero-Sum Balance</div>
                <div style={{ fontSize: '13px', color: '#64748B' }}>Every transfer balances debits against credits with database trigger verification.</div>
              </div>
            </div>

            <div style={{ display: 'flex', alignItems: 'flex-start', gap: '12px' }}>
              <div style={{
                width: '34px',
                height: '34px',
                borderRadius: '8px',
                backgroundColor: '#EFF6FF',
                color: '#2563EB',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: '16px',
                flexShrink: 0
              }}>
                🔒
              </div>
              <div>
                <div style={{ fontSize: '14px', fontWeight: 600, color: '#0F172A' }}>Maker-Checker Dual Governance</div>
                <div style={{ fontSize: '13px', color: '#64748B' }}>High-value movements and reversals require dual-authorization review.</div>
              </div>
            </div>

            <div style={{ display: 'flex', alignItems: 'flex-start', gap: '12px' }}>
              <div style={{
                width: '34px',
                height: '34px',
                borderRadius: '8px',
                backgroundColor: '#F5F3FF',
                color: '#7C3AED',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: '16px',
                flexShrink: 0
              }}>
                📋
              </div>
              <div>
                <div style={{ fontSize: '14px', fontWeight: 600, color: '#0F172A' }}>Auditor Portal & Invariant Proof</div>
                <div style={{ fontSize: '13px', color: '#64748B' }}>Real-time immutable audit trail with formula-safe CSV and PDF statement exports.</div>
              </div>
            </div>
          </div>
        </div>

        {/* Right Side: Sign-In Card */}
        <div style={{ display: 'flex', justifyContent: 'center' }}>
          <div className="card" style={{ width: '100%', maxWidth: '420px', padding: '32px', boxShadow: '0 4px 16px rgba(0, 0, 0, 0.05)' }}>
        <div style={{ fontSize: '13px', color: '#6B7280', fontWeight: 500, marginBottom: '6px' }}>
          Vaultora
        </div>
        <h1 style={{ fontSize: '24px', fontWeight: 700, color: '#111827', marginBottom: '24px' }}>
          Sign in
        </h1>

        {registrationSuccess && (
          <div className="alert-success" style={{ marginBottom: '16px' }}>
            Account created successfully. Please sign in below.
          </div>
        )}

        {error && (
          <div className="alert-error" style={{ marginBottom: '16px' }}>
            <div>{error}</div>
            {error.toLowerCase().includes('verification') && (
              <div style={{ marginTop: '6px' }}>
                <Link to="/verify-email" style={{ color: '#0D6E64', fontWeight: 600, textDecoration: 'underline' }}>
                  Verify email or resend link &rarr;
                </Link>
              </div>
            )}
          </div>
        )}

        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label className="form-label" htmlFor="email">Email</label>
            <input
              id="email"
              type="email"
              className="form-input"
              placeholder="customer1@ledgerline.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              disabled={isSubmitting}
              autoComplete="email"
              required
            />
          </div>

          <div className="form-group">
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '6px' }}>
              <label className="form-label" htmlFor="password" style={{ margin: 0 }}>Password</label>
              <Link to="/forgot-password" style={{ fontSize: '12px', color: '#0D6E64', textDecoration: 'none', fontWeight: 500 }}>
                Forgot password?
              </Link>
            </div>
            <input
              id="password"
              type="password"
              className="form-input"
              placeholder="•••••••• (e.g. customer1)"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              disabled={isSubmitting}
              autoComplete="current-password"
              required
            />
          </div>

          <button
            type="submit"
            className="btn-primary"
            style={{ width: '100%', marginTop: '8px', padding: '11px' }}
            disabled={isSubmitting}
          >
            {isSubmitting ? 'Signing in...' : 'Sign in'}
          </button>
        </form>

        <div style={{ marginTop: '20px', textAlign: 'center', fontSize: '13px', color: '#4B5563' }}>
          New customer?{' '}
          <Link to="/register" style={{ color: '#0D6E64', fontWeight: 500 }}>
            Create an account
          </Link>
        </div>

        {/* Sample Roles Quick Fill */}
        <div style={{ marginTop: '24px', paddingTop: '20px', borderTop: '1px solid #E5E7EB' }}>
          <div style={{ fontSize: '11px', fontWeight: 600, color: '#6B7280', marginBottom: '10px', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
            Quick Sign-In Samples
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr', gap: '8px' }}>
            <div style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              padding: '8px 12px',
              borderRadius: '6px',
              backgroundColor: '#F9FAFB',
              border: '1px solid #E5E7EB',
              fontSize: '12px'
            }}>
              <div>
                <span style={{ fontWeight: 600, color: '#0F172A', marginRight: '6px' }}>Customer</span>
                <code style={{ fontSize: '11px', color: '#4B5563' }}>customer1@ledgerline.com</code>
              </div>
              <button
                type="button"
                onClick={() => handlePrefill('customer1@ledgerline.com', 'customer1')}
                disabled={isSubmitting}
                style={{
                  fontSize: '11px',
                  fontWeight: 600,
                  color: '#0D6E64',
                  backgroundColor: '#E6F4F1',
                  border: 'none',
                  padding: '3px 10px',
                  borderRadius: '4px',
                  cursor: 'pointer'
                }}
              >
                Fill
              </button>
            </div>

            <div style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              padding: '8px 12px',
              borderRadius: '6px',
              backgroundColor: '#F9FAFB',
              border: '1px solid #E5E7EB',
              fontSize: '12px'
            }}>
              <div>
                <span style={{ fontWeight: 600, color: '#0F172A', marginRight: '6px' }}>Auditor</span>
                <code style={{ fontSize: '11px', color: '#4B5563' }}>auditor1@ledgerline.com</code>
              </div>
              <button
                type="button"
                onClick={() => handlePrefill('auditor1@ledgerline.com', 'auditor1')}
                disabled={isSubmitting}
                style={{
                  fontSize: '11px',
                  fontWeight: 600,
                  color: '#0369A1',
                  backgroundColor: '#E0F2FE',
                  border: 'none',
                  padding: '3px 10px',
                  borderRadius: '4px',
                  cursor: 'pointer'
                }}
              >
                Fill
              </button>
            </div>

            <div style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              padding: '8px 12px',
              borderRadius: '6px',
              backgroundColor: '#F9FAFB',
              border: '1px solid #E5E7EB',
              fontSize: '12px'
            }}>
              <div>
                <span style={{ fontWeight: 600, color: '#0F172A', marginRight: '6px' }}>Administrator</span>
                <code style={{ fontSize: '11px', color: '#4B5563' }}>admin1@ledgerline.com</code>
              </div>
              <button
                type="button"
                onClick={() => handlePrefill('admin1@ledgerline.com', 'admin1')}
                disabled={isSubmitting}
                style={{
                  fontSize: '11px',
                  fontWeight: 600,
                  color: '#7C2D12',
                  backgroundColor: '#FFEDD5',
                  border: 'none',
                  padding: '3px 10px',
                  borderRadius: '4px',
                  cursor: 'pointer'
                }}
              >
                Fill
              </button>
            </div>
          </div>
        </div>
        </div>
      </div>
    </div>
    </div>
  );
};
