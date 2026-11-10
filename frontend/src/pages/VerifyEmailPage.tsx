import React, { useState, useEffect } from 'react';
import { Link, useSearchParams, useNavigate } from 'react-router-dom';
import { authApi } from '../api/auth';

export const VerifyEmailPage: React.FC = () => {
  const [searchParams] = useSearchParams();
  const tokenParam = searchParams.get('token') || '';
  const navigate = useNavigate();

  const [token, setToken] = useState(tokenParam);
  const [isVerifying, setIsVerifying] = useState(false);
  const [verified, setVerified] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Resend state
  const [resendEmail, setResendEmail] = useState('');
  const [isResending, setIsResending] = useState(false);
  const [resendMessage, setResendMessage] = useState<string | null>(null);

  useEffect(() => {
    if (tokenParam) {
      handleVerify(tokenParam);
    }
  }, [tokenParam]);

  const handleVerify = async (verificationToken: string) => {
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
  };

  const handleResend = async (e: React.FormEvent) => {
    e.preventDefault();
    setResendMessage(null);
    if (!resendEmail.trim()) return;

    try {
      setIsResending(true);
      const res = await authApi.resendVerification({ email: resendEmail.trim() });
      setResendMessage(res.message || 'If an unverified account exists with that email, a verification link has been sent.');
    } catch (err: unknown) {
      setResendMessage('If an unverified account exists with that email, a verification link has been sent.');
    } finally {
      setIsResending(false);
    }
  };

  return (
    <div style={{
      minHeight: '100vh',
      backgroundColor: '#F7F8FA',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      padding: '24px',
      position: 'relative'
    }}>
      <header style={{ position: 'absolute', top: '24px', left: '28px', zIndex: 10 }}>
        <Link to="/" style={{ fontSize: '20px', fontWeight: 700, color: '#111827', textDecoration: 'none', letterSpacing: '-0.02em' }}>
          Vaultora
        </Link>
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
              style={{ width: '100%', padding: '11px', backgroundColor: '#0D6E64' }}
              onClick={() => navigate('/login')}
            >
              Continue to Sign in
            </button>
          </div>
        ) : (
          <div>
            <p style={{ fontSize: '14px', color: '#4B5563', marginBottom: '20px' }}>
              Confirm your email address to activate your customer banking account.
            </p>

            {error && (
              <div className="alert alert-danger" style={{ marginBottom: '16px', padding: '10px 14px', fontSize: '13px' }}>
                {error}
              </div>
            )}

            {!tokenParam ? (
              <form onSubmit={(e) => { e.preventDefault(); handleVerify(token); }} style={{ marginBottom: '28px' }}>
                <div className="form-group" style={{ marginBottom: '16px' }}>
                  <label className="form-label" htmlFor="manual-token">
                    Verification Token
                  </label>
                  <input
                    id="manual-token"
                    type="text"
                    className="form-input"
                    placeholder="Enter token from email"
                    value={token}
                    onChange={(e) => setToken(e.target.value)}
                    disabled={isVerifying}
                    required
                  />
                </div>
                <button
                  type="submit"
                  className="btn-primary"
                  style={{ width: '100%', padding: '10px', backgroundColor: '#0D6E64' }}
                  disabled={isVerifying}
                >
                  {isVerifying ? 'Verifying...' : 'Verify Token'}
                </button>
              </form>
            ) : isVerifying ? (
              <div style={{ textAlign: 'center', padding: '24px', color: '#6B7280' }}>
                Verifying your email token...
              </div>
            ) : null}

            {/* Resend Verification Section */}
            <div style={{ borderTop: '1px solid #E5E7EB', paddingTop: '20px', marginTop: '20px' }}>
              <h2 style={{ fontSize: '14px', fontWeight: 600, color: '#374151', margin: '0 0 8px 0' }}>
                Need a new verification link?
              </h2>
              {resendMessage && (
                <div className="alert alert-success" style={{ marginBottom: '12px', padding: '10px 14px', fontSize: '13px' }}>
                  {resendMessage}
                </div>
              )}
              <form onSubmit={handleResend}>
                <div className="form-group" style={{ marginBottom: '12px' }}>
                  <input
                    type="email"
                    className="form-input"
                    placeholder="Enter your customer email"
                    value={resendEmail}
                    onChange={(e) => setResendEmail(e.target.value)}
                    disabled={isResending}
                    required
                  />
                </div>
                <button
                  type="submit"
                  className="btn btn-secondary"
                  style={{ width: '100%', padding: '8px', fontSize: '13px' }}
                  disabled={isResending}
                >
                  {isResending ? 'Sending...' : 'Resend Verification Email'}
                </button>
              </form>
            </div>

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
