import React from 'react';
import { useTheme } from '../context/ThemeContext';

interface ThemeToggleProps {
  variant?: 'pill' | 'icon' | 'button';
  className?: string;
  style?: React.CSSProperties;
}

export const ThemeToggle: React.FC<ThemeToggleProps> = ({
  variant = 'pill',
  className = '',
  style = {},
}) => {
  const { theme, toggleTheme } = useTheme();
  const isDark = theme === 'dark';

  if (variant === 'icon') {
    return (
      <button
        type="button"
        onClick={toggleTheme}
        className={`theme-toggle-icon ${className}`}
        aria-label={`Switch to ${isDark ? 'light' : 'dark'} mode`}
        title={`Switch to ${isDark ? 'light' : 'dark'} mode`}
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          width: '36px',
          height: '36px',
          borderRadius: '8px',
          border: '1px solid var(--border-color)',
          backgroundColor: 'var(--bg-card)',
          color: 'var(--text-main)',
          cursor: 'pointer',
          fontSize: '16px',
          transition: 'all 0.15s ease',
          ...style,
        }}
      >
        {isDark ? '☀️' : '🌙'}
      </button>
    );
  }

  if (variant === 'button') {
    return (
      <button
        type="button"
        onClick={toggleTheme}
        className={`theme-toggle-btn ${className}`}
        style={{
          width: '100%',
          textAlign: 'left',
          padding: '7px 12px',
          fontSize: '13px',
          color: 'var(--text-main)',
          backgroundColor: 'var(--bg-card)',
          border: '1px solid var(--border-color)',
          borderRadius: '6px',
          cursor: 'pointer',
          fontWeight: 500,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          transition: 'all 0.15s ease',
          ...style,
        }}
      >
        <span style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
          <span>{isDark ? '☀️' : '🌙'}</span>
          <span>{isDark ? 'Light mode' : 'Dark mode'}</span>
        </span>
        <span
          style={{
            fontSize: '10px',
            textTransform: 'uppercase',
            letterSpacing: '0.05em',
            padding: '2px 6px',
            borderRadius: '4px',
            backgroundColor: isDark ? '#1E293B' : '#F3F4F6',
            color: 'var(--text-muted)',
            fontWeight: 600,
          }}
        >
          {theme}
        </span>
      </button>
    );
  }

  // Default 'pill' variant
  return (
    <button
      type="button"
      onClick={toggleTheme}
      className={`theme-toggle-pill ${className}`}
      aria-label={`Switch to ${isDark ? 'light' : 'dark'} mode`}
      title={`Switch to ${isDark ? 'light' : 'dark'} mode`}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: '6px',
        padding: '5px 12px',
        borderRadius: '9999px',
        border: '1px solid var(--border-color)',
        backgroundColor: 'var(--bg-card)',
        color: 'var(--text-main)',
        fontSize: '12px',
        fontWeight: 500,
        cursor: 'pointer',
        transition: 'all 0.15s ease',
        ...style,
      }}
    >
      <span>{isDark ? '☀️' : '🌙'}</span>
      <span>{isDark ? 'Light' : 'Dark'}</span>
    </button>
  );
};
