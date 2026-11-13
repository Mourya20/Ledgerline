import React, { useState, useEffect } from 'react';
import { Link, useSearchParams, useNavigate } from 'react-router-dom';
import { authApi } from '../api/auth';
import { ThemeToggle } from '../components/ThemeToggle';

export const VerifyEmailPage: React.FC = () => {
  const [searchParams] = useSearchParams();
  const tokenParam = searchParams.get('token') || '';
  const navigate = useNavigate();

  const emailParam = searchParams.get('email') || '';

  const [token, setToken] = useState(tokenParam);
  const [isVerifying, setIsVerifying] = useState(false);
  const [verified, setVerified] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Resend state
  const [resendEmail, setResendEmail] = useState(emailParam);
  const [isResending, setIsResending] = useState(false);
  const [resendMessage, setResendMessage] = useState<string | null>(null);

  const handleVerify = React.useCallback(async (verificationToken: string) => {
    try {
      setIsVerifying(true);
      setError(null);
      await authApi.verifyEmail({ token: verificationToken.trim() });
      setVerified(true);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Invalid or expired verification token.';
      setError(msg);
    } finally {
      setIsVerifying(false);
    }
  }, []);

  useEffect(() => {
    if (!tokenParam) return;
    let ignore = false;
    async function verifyParam() {
      try {
        setIsVerifying(true);
        setError(null);
        await authApi.verifyEmail({ token: tokenParam.trim() });
        if (!ignore) {
          setVerified(true);
        }
      } catch (err: unknown) {
        if (!ignore) {
          const msg = err instanceof Error ? err.message : 'Invalid or expired verification token.';
          setError(msg);
        }
      } finally {
        if (!ignore) {
          setIsVerifying(false);
        }
      }
    }
    verifyParam();
    return () => {
      ignore = true;
    };
  }, [tokenParam]);

  const handleResend = async (e: React.FormEvent) => {
    e.preventDefault();
    setResendMessage(null);
    setError(null);
    if (!resendEmail.trim()) return;

    try {
      setIsResending(true);
      const res = (await authApi.resendVerification({ email: resendEmail.trim() })) as { message?: string; token?: string; link?: string };
      if (res.token) {
        setToken(res.token);
        // Automatically verify token immediately for seamless one-click activation!
        await handleVerify(res.token);
      } else {
        setResendMessage(res.message || 'If an unverified account exists with that email, a verification link has been sent.');
      }
    } catch {
      setResendMessage('If an unverified account exists with that email, a verification link has been sent.');
    } finally {
      setIsResending(false);
    }
  };

  return (
    <div style={{
      minHeight: '100vh',
      backgroundColor: 'var(--bg-page)',
      color: 'var(--text-main)',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      padding: '70px 24px 24px',
      position: 'relative'
    }}>
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
        <Link to="/" style={{ fontSize: '20px', fontWeight: 700, color: 'var(--text-main)', textDecoration: 'none', letterSpacing: '-0.02em' }}>
          Vaultora
        </Link>
        <ThemeToggle variant="pill" />
      </header>

      <div className="card" style={{ width: '100%', maxWidth: '440px', padding: '32px' }}>
        <div style={{ fontSize: '13px', color: '#6B7280', fontWeight: 500, marginBottom: '6px' }}>
          Customer Onboarding
        </div>
        <h1 style={{ fontSize: '24px', fontWeight: 700, color: '#111827', marginBottom: '8px' }}>
          Verify your email
        </h1>

        {verified ? (
          <div>
            <div className="alert alert-success" style={{ marginBottom: '20px', padding: '16px', fontSize: '14px' }}>
              <div style={{ fontWeight: 600, marginBottom: '4px' }}>✓ Email Verified Successfully</div>
              Your email address has been confirmed. You may now sign in to access your customer banking portal.
            </div>
            <button
              type="button"
              className="btn-primary"
              style={{ width: '100%', padding: '11px' }}
              onClick={() => navigate('/login')}
            >
              Continue to Sign in
            </button>
          </div>
        ) : (
          <div>
            {/* Sandbox Simulation Notice Banner */}
            <div style={{
              backgroundColor: 'var(--primary-teal-light)',
              border: '1px solid var(--primary-teal-border)',
              borderRadius: '8px',
              padding: '12px 14px',
              marginBottom: '20px',
              fontSize: '13px',
              lineHeight: 1.5,
              color: 'var(--text-main)'
            }}>
              <div style={{ fontWeight: 600, display: 'flex', alignItems: 'center', gap: '6px', marginBottom: '4px', color: 'var(--primary-teal)' }}>
                <span>💡</span> Simulated Email Verification
              </div>
              <div style={{ color: 'var(--text-muted)' }}>
                In this demonstration environment, live outbound SMTP delivery is simulated. Click <strong>&quot;Instant Account Activation&quot;</strong> below to verify your email and activate your account immediately.
              </div>
            </div>

            {error && (
              <div className="alert alert-danger" style={{ marginBottom: '16px', padding: '10px 14px', fontSize: '13px' }}>
                {error}
              </div>
            )}

            {/* Instant One-Click Verification Form */}
            <form onSubmit={handleResend} style={{ marginBottom: '24px' }}>
              <div className="form-group" style={{ marginBottom: '12px' }}>
                <label className="form-label" htmlFor="resend-email" style={{ fontSize: '12px', fontWeight: 600 }}>
                  Registered Customer Email
                </label>
                <input
                  id="resend-email"
                  type="email"
                  className="form-input"
                  placeholder="Enter your customer email"
                  value={resendEmail}
                  onChange={(e) => setResendEmail(e.target.value)}
                  disabled={isResending || isVerifying}
                  required
                />
              </div>
              <button
                type="submit"
                className="btn-primary"
                style={{ width: '100%', padding: '10px 16px', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '8px' }}
                disabled={isResending || isVerifying}
              >
                <span>⚡</span>
                <span>{isResending || isVerifying ? 'Activating account...' : 'Instant Account Activation'}</span>
              </button>
            </form>

            {resendMessage && (
              <div className="alert alert-success" style={{ marginBottom: '16px', padding: '10px 14px', fontSize: '13px' }}>
                {resendMessage}
              </div>
            )}

            {!tokenParam ? (
              <details style={{ marginTop: '16px', borderTop: '1px solid var(--border-color)', paddingTop: '16px' }}>
                <summary style={{ fontSize: '12px', color: 'var(--text-muted)', cursor: 'pointer', userSelect: 'none' }}>
                  Manual verification token entry (optional)
                </summary>
                <form onSubmit={(e) => { e.preventDefault(); handleVerify(token); }} style={{ marginTop: '14px' }}>
                  <div className="form-group" style={{ marginBottom: '12px' }}>
                    <label className="form-label" htmlFor="manual-token">
                      Verification Token
                    </label>
                    <input
                      id="manual-token"
                      type="text"
                      className="form-input"
                      placeholder="Enter verification token"
                      value={token}
                      onChange={(e) => setToken(e.target.value)}
                      disabled={isVerifying}
                      required
                    />
                  </div>
                  <button
                    type="submit"
                    className="btn btn-secondary"
                    style={{ width: '100%', padding: '8px', fontSize: '13px' }}
                    disabled={isVerifying}
                  >
                    {isVerifying ? 'Verifying...' : 'Verify Token'}
                  </button>
                </form>
              </details>
            ) : isVerifying ? (
              <div style={{ textAlign: 'center', padding: '24px', color: 'var(--text-muted)' }}>
                Verifying your email token...
              </div>
            ) : null}

            <div style={{ marginTop: '20px', textAlign: 'center', fontSize: '13px', color: '#6B7280' }}>
              Already verified?{' '}
              <Link to="/login" style={{ color: '#0D6E64', fontWeight: 600, textDecoration: 'none' }}>
                Sign in
              </Link>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
