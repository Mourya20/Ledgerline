import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { ApiException } from '../api/client';
import { validateRegistrationPassword } from '../utils/validation';

export const RegisterPage: React.FC = () => {
  const { register } = useAuth();
  const navigate = useNavigate();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    if (!email.trim() || !password || !confirmPassword) {
      setError('Please fill in all fields.');
      return;
    }

    const validation = validateRegistrationPassword(password, confirmPassword);
    if (!validation.valid) {
      setError(validation.error || 'Invalid password.');
      return;
    }

    setIsSubmitting(true);
    try {
      await register({
        email: email.trim(),
        password,
      });

      // Redirect to sign in with success message state
      navigate('/login', { state: { registered: true } });
    } catch (err) {
      if (err instanceof ApiException) {
        if (err.status === 409) {
          setError('An account with this email already exists.');
        } else {
          setError(err.message || 'Registration failed. Please try again.');
        }
      } else {
        setError('Network error or server unreachable. Please try again.');
      }
    } finally {
      setIsSubmitting(false);
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

      <div className="card" style={{ width: '100%', maxWidth: '380px', padding: '32px' }}>
        <div style={{ fontSize: '13px', color: '#6B7280', fontWeight: 500, marginBottom: '6px' }}>
          LedgerLine
        </div>
        <h1 style={{ fontSize: '24px', fontWeight: 700, color: '#111827', marginBottom: '24px' }}>
          Create account
        </h1>

        {error && (
          <div className="alert alert-danger" role="alert">
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label className="form-label" htmlFor="email">Email</label>
            <input
              id="email"
              type="email"
              className="form-input"
              placeholder="you@example.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              disabled={isSubmitting}
              autoComplete="email"
              required
            />
          </div>

          <div className="form-group">
            <label className="form-label" htmlFor="password">Password</label>
            <input
              id="password"
              type="password"
              className="form-input"
              placeholder="At least 12 characters"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              disabled={isSubmitting}
              autoComplete="new-password"
              required
            />
          </div>

          <div className="form-group">
            <label className="form-label" htmlFor="confirmPassword">Confirm password</label>
            <input
              id="confirmPassword"
              type="password"
              className="form-input"
              placeholder="Confirm password"
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              disabled={isSubmitting}
              autoComplete="new-password"
              required
            />
          </div>

          <button
            type="submit"
            className="btn-primary"
            style={{ width: '100%', marginTop: '8px', padding: '11px' }}
            disabled={isSubmitting}
          >
            {isSubmitting ? 'Registering...' : 'Register'}
          </button>
        </form>

        <div style={{ marginTop: '20px', textAlign: 'center', fontSize: '13px', color: '#4B5563' }}>
          Already have an account?{' '}
          <Link to="/login" style={{ color: '#0D6E64', fontWeight: 500 }}>
            Sign in
          </Link>
        </div>
      </div>
    </div>
  );
};
