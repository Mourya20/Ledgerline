import React, { useState } from 'react';
import { Link, useSearchParams, useNavigate } from 'react-router-dom';
import { authApi } from '../api/auth';
import { ThemeToggle } from '../components/ThemeToggle';

export const ResetPasswordPage: React.FC = () => {
  const [searchParams] = useSearchParams();
  const tokenParam = searchParams.get('token') || '';
  const navigate = useNavigate();

  const [token, setToken] = useState(tokenParam);
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [success, setSuccess] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    if (!token.trim()) {
      setError('A valid reset token is required.');
      return;
    }

    if (newPassword.length < 12) {
      setError('New password must be at least 12 characters.');
      return;
    }

    if (newPassword !== confirmPassword) {
      setError('Passwords do not match.');
      return;
    }

    try {
      setIsSubmitting(true);
      await authApi.resetPassword({ token: token.trim(), newPassword });
      setSuccess(true);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to reset password. The link may have expired.';
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
        <ThemeToggle variant="switch" />
      </header>

      <div className="card" style={{ width: '100%', maxWidth: '420px', padding: '32px' }}>
        <div style={{ fontSize: '13px', color: 'var(--text-muted)', fontWeight: 500, marginBottom: '6px' }}>
          Account Security
        </div>
        <h1 style={{ fontSize: '24px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '8px' }}>
          Reset your password
        </h1>
        <p style={{ fontSize: '14px', color: 'var(--text-muted)', marginBottom: '24px' }}>
          Enter your new password below. It must be at least 12 characters long.
        </p>

        {error && (
          <div className="alert alert-danger" style={{ marginBottom: '16px', padding: '10px 14px', fontSize: '13px' }}>
            {error}
          </div>
        )}

        {success ? (
          <div>
            <div className="alert alert-success" style={{ marginBottom: '20px', padding: '12px 16px', fontSize: '14px' }}>
              Your password has been reset successfully! You can now sign in with your new credentials.
            </div>
            <button
              type="button"
              className="btn-primary"
              style={{ width: '100%', padding: '11px', backgroundColor: '#0D6E64' }}
              onClick={() => navigate('/login')}
            >
              Proceed to Sign in
            </button>
          </div>
        ) : (
          <form onSubmit={handleSubmit}>
            {!tokenParam && (
              <div className="form-group" style={{ marginBottom: '16px' }}>
                <label className="form-label" htmlFor="reset-token">
                  Reset Token
                </label>
                <input
                  id="reset-token"
                  type="text"
                  className="form-input"
                  placeholder="Paste reset token here"
                  value={token}
                  onChange={(e) => setToken(e.target.value)}
                  disabled={isSubmitting}
                  required
                />
              </div>
            )}

            <div className="form-group" style={{ marginBottom: '16px' }}>
              <label className="form-label" htmlFor="new-password">
                New Password (min 12 chars)
              </label>
              <input
                id="new-password"
                type="password"
                className="form-input"
                placeholder="••••••••••••"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                disabled={isSubmitting}
                required
                autoFocus
              />
            </div>

            <div className="form-group" style={{ marginBottom: '24px' }}>
              <label className="form-label" htmlFor="confirm-password">
                Confirm New Password
              </label>
              <input
                id="confirm-password"
                type="password"
                className="form-input"
                placeholder="••••••••••••"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                disabled={isSubmitting}
                required
              />
            </div>

            <button
              type="submit"
              className="btn-primary"
              style={{ width: '100%', padding: '11px', backgroundColor: '#0D6E64' }}
              disabled={isSubmitting}
            >
              {isSubmitting ? 'Updating password...' : 'Update Password'}
            </button>

            <div style={{ marginTop: '20px', textAlign: 'center', fontSize: '13px', color: '#6B7280' }}>
              Back to{' '}
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
