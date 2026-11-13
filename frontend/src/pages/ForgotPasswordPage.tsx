import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { authApi } from '../api/auth';
import { ThemeToggle } from '../components/ThemeToggle';

export const ForgotPasswordPage: React.FC = () => {
  const [email, setEmail] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setMessage(null);

    if (!email.trim()) {
      setError('Please enter your registered email address.');
      return;
    }

    try {
      setIsSubmitting(true);
      const res = await authApi.forgotPassword({ email: email.trim() });
      setMessage(res.message || 'If an account exists with that email, a password reset link has been generated.');
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'An error occurred. Please try again.';
      setError(msg);
    } finally {
      setIsSubmitting(false);
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

      <div className="card" style={{ width: '100%', maxWidth: '420px', padding: '32px' }}>
        <div style={{ fontSize: '13px', color: 'var(--text-muted)', fontWeight: 500, marginBottom: '6px' }}>
          Account Recovery
        </div>
        <h1 style={{ fontSize: '24px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '8px' }}>
          Forgot password
        </h1>
        <p style={{ fontSize: '14px', color: 'var(--text-muted)', marginBottom: '24px' }}>
          Enter your registered email address and we&apos;ll send you a single-use link to reset your password.
        </p>

        {error && (
          <div className="alert alert-danger" style={{ marginBottom: '16px', padding: '10px 14px', fontSize: '13px' }}>
            {error}
          </div>
        )}

        {message ? (
          <div>
            <div className="alert alert-success" style={{ marginBottom: '20px', padding: '12px 16px', fontSize: '14px', lineHeight: 1.4 }}>
              {message}
            </div>
            <div style={{ textAlign: 'center' }}>
              <Link to="/login" className="btn btn-primary" style={{ display: 'inline-block', padding: '10px 24px', textDecoration: 'none', backgroundColor: '#0D6E64' }}>
                Back to Sign in
              </Link>
            </div>
          </div>
        ) : (
          <form onSubmit={handleSubmit}>
            <div className="form-group" style={{ marginBottom: '20px' }}>
              <label className="form-label" htmlFor="recovery-email">
                Registered Email Address
              </label>
              <input
                id="recovery-email"
                type="email"
                className="form-input"
                placeholder="customer@example.com"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                disabled={isSubmitting}
                required
                autoFocus
              />
            </div>

            <button
              type="submit"
              className="btn-primary"
              style={{ width: '100%', padding: '11px', backgroundColor: '#0D6E64' }}
              disabled={isSubmitting}
            >
              {isSubmitting ? 'Sending instructions...' : 'Send reset link'}
            </button>

            <div style={{ marginTop: '20px', textAlign: 'center', fontSize: '13px', color: '#6B7280' }}>
              Remember your password?{' '}
              <Link to="/login" style={{ color: '#0D6E64', fontWeight: 600, textDecoration: 'none' }}>
                Sign in
              </Link>
            </div>
          </form>
        )}
      </div>
    </div>
  );
};
