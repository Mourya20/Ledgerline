import React, { useState } from 'react';
import { Link, useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { ApiException } from '../api/client';
import { ThemeToggle } from '../components/ThemeToggle';

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
      backgroundColor: 'var(--bg-page)',
      color: 'var(--text-main)',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      padding: '70px 24px 40px',
      position: 'relative'
    }}>
      {/* Top Header Bar with Brand & Theme Toggle */}
      <header style={{
        position: 'absolute',
        top: '20px',
        left: '28px',
        right: '28px',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        zIndex: 10
      }}>
        <Link
          to="/"
          style={{
            fontSize: '22px',
            fontWeight: 800,
            color: 'var(--text-main)',
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
        <ThemeToggle variant="switch" />
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
            gap: '7px',
            backgroundColor: 'var(--primary-teal-light)',
            color: 'var(--primary-teal)',
            border: '1px solid var(--primary-teal-border)',
            padding: '4px 12px',
            borderRadius: '9999px',
            fontSize: '12px',
            fontWeight: 600,
            marginBottom: '16px',
            letterSpacing: '0.02em'
          }}>
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
              <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
            </svg>
            <span>Secure Banking & Transaction Platform</span>
          </div>

          <h2 style={{
            fontSize: '32px',
            fontWeight: 800,
            lineHeight: 1.2,
            color: 'var(--text-main)',
            letterSpacing: '-0.03em',
            marginBottom: '14px'
          }}>
            Institutional financial engineering with zero-sum precision.
          </h2>

          <p style={{
            fontSize: '15px',
            color: 'var(--text-muted)',
            lineHeight: 1.6,
            marginBottom: '28px'
          }}>
            Vaultora enforces strict mathematical balance invariants, transaction-scoped advisory locks, and non-repudiable audit logs across all financial movements.
          </p>

          <div style={{ display: 'flex', flexDirection: 'column', gap: '14px', marginBottom: '28px' }}>
            <div style={{ display: 'flex', alignItems: 'flex-start', gap: '12px' }}>
              <div style={{
                width: '36px',
                height: '36px',
                borderRadius: '8px',
                backgroundColor: 'var(--primary-teal-light)',
                color: 'var(--primary-teal)',
                border: '1px solid var(--primary-teal-border)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                flexShrink: 0
              }}>
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="m16 16 3-8 3 8c-.87.65-1.92 1-3 1s-2.13-.35-3-1Z"/>
                  <path d="m2 16 3-8 3 8c-.87.65-1.92 1-3 1s-2.13-.35-3-1Z"/>
                  <path d="M7 21h10"/>
                  <path d="M12 3v18"/>
                  <path d="M3 7h2c2 0 5-1 7-2 2 1 5 2 7 2h2"/>
                </svg>
              </div>
              <div>
                <div style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-main)' }}>Mathematical Zero-Sum Balance</div>
                <div style={{ fontSize: '13px', color: 'var(--text-muted)' }}>Every transfer balances debits against credits with database trigger verification.</div>
              </div>
            </div>

            <div style={{ display: 'flex', alignItems: 'flex-start', gap: '12px' }}>
              <div style={{
                width: '36px',
                height: '36px',
                borderRadius: '8px',
                backgroundColor: 'rgba(59, 130, 246, 0.12)',
                color: '#3B82F6',
                border: '1px solid rgba(59, 130, 246, 0.25)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                flexShrink: 0
              }}>
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <rect width="18" height="11" x="3" y="11" rx="2" ry="2"/>
                  <path d="M7 11V7a5 5 0 0 1 10 0v4"/>
                </svg>
              </div>
              <div>
                <div style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-main)' }}>Maker-Checker Dual Governance</div>
                <div style={{ fontSize: '13px', color: 'var(--text-muted)' }}>High-value movements and reversals require dual-authorization review.</div>
              </div>
            </div>

            <div style={{ display: 'flex', alignItems: 'flex-start', gap: '12px' }}>
              <div style={{
                width: '36px',
                height: '36px',
                borderRadius: '8px',
                backgroundColor: 'rgba(168, 85, 247, 0.12)',
                color: '#A855F7',
                border: '1px solid rgba(168, 85, 247, 0.25)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                flexShrink: 0
              }}>
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                  <polyline points="14 2 14 8 20 8"/>
                  <path d="m9 15 2 2 4-4"/>
                </svg>
              </div>
              <div>
                <div style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-main)' }}>Auditor Portal & Invariant Proof</div>
                <div style={{ fontSize: '13px', color: 'var(--text-muted)' }}>Real-time immutable audit trail with formula-safe CSV and PDF statement exports.</div>
              </div>
            </div>
          </div>
        </div>

        {/* Right Side: Sign-In Card */}
        <div style={{ display: 'flex', justifyContent: 'center' }}>
          <div className="card" style={{ width: '100%', maxWidth: '420px', padding: '32px', boxShadow: 'var(--shadow-card)' }}>
        <div style={{ fontSize: '13px', color: 'var(--text-muted)', fontWeight: 500, marginBottom: '6px' }}>
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
              <div style={{ marginTop: '8px' }}>
                <Link
                  to={`/verify-email?email=${encodeURIComponent(email)}`}
                  style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: '4px',
                    color: 'var(--primary-teal)',
                    fontWeight: 600,
                    textDecoration: 'underline'
                  }}
                >
                  <span>Activate account with one click</span>
                  <span>&rarr;</span>
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
          <div style={{ fontSize: '11px', fontWeight: 600, color: 'var(--text-muted)', marginBottom: '10px', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
            Quick Sign-In Samples
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr', gap: '8px' }}>
            <div style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              padding: '8px 12px',
              borderRadius: '6px',
              backgroundColor: 'var(--border-subtle)',
              border: '1px solid var(--border-color)',
              fontSize: '12px'
            }}>
              <div>
                <span style={{ fontWeight: 600, color: 'var(--text-main)', marginRight: '6px' }}>Customer</span>
                <code style={{ fontSize: '11px', color: 'var(--text-muted)' }}>customer1@ledgerline.com</code>
              </div>
              <button
                type="button"
                onClick={() => handlePrefill('customer1@ledgerline.com', 'customer1')}
                disabled={isSubmitting}
                style={{
                  fontSize: '11px',
                  fontWeight: 600,
                  color: 'var(--primary-teal)',
                  backgroundColor: 'var(--primary-teal-light)',
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
              backgroundColor: 'var(--border-subtle)',
              border: '1px solid var(--border-color)',
              fontSize: '12px'
            }}>
              <div>
                <span style={{ fontWeight: 600, color: 'var(--text-main)', marginRight: '6px' }}>Auditor</span>
                <code style={{ fontSize: '11px', color: 'var(--text-muted)' }}>auditor1@ledgerline.com</code>
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
              backgroundColor: 'var(--border-subtle)',
              border: '1px solid var(--border-color)',
              fontSize: '12px'
            }}>
              <div>
                <span style={{ fontWeight: 600, color: 'var(--text-main)', marginRight: '6px' }}>Administrator</span>
                <code style={{ fontSize: '11px', color: 'var(--text-muted)' }}>admin1@ledgerline.com</code>
              </div>
              <button
                type="button"
                onClick={() => handlePrefill('admin1@ledgerline.com', 'admin1')}
                disabled={isSubmitting}
                style={{
                  fontSize: '11px',
                  fontWeight: 600,
                  color: '#C2410C',
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
