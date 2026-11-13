import React from 'react';
import { useTheme, type ThemeMode } from '../context/ThemeContext';

interface ThemeToggleProps {
  variant?: 'switch' | 'segmented' | 'icon' | 'button';
  className?: string;
  style?: React.CSSProperties;
}

// Minimalist, crisp SVG symbols (NO cartoon emojis)
export const SunIcon: React.FC<{ size?: number; className?: string }> = ({ size = 15, className = '' }) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
    className={className}
    style={{ display: 'inline-block', verticalAlign: 'middle' }}
  >
    <circle cx="12" cy="12" r="4" />
    <path d="M12 2v2" />
    <path d="M12 20v2" />
    <path d="m4.93 4.93 1.41 1.41" />
    <path d="m17.66 17.66 1.41 1.41" />
    <path d="M2 12h2" />
    <path d="M20 12h2" />
    <path d="m6.34 17.66-1.41 1.41" />
    <path d="m19.07 4.93-1.41 1.41" />
  </svg>
);

export const MoonIcon: React.FC<{ size?: number; className?: string }> = ({ size = 15, className = '' }) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
    className={className}
    style={{ display: 'inline-block', verticalAlign: 'middle' }}
  >
    <path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z" />
  </svg>
);

export const MonitorIcon: React.FC<{ size?: number; className?: string }> = ({ size = 14, className = '' }) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
    className={className}
    style={{ display: 'inline-block', verticalAlign: 'middle' }}
  >
    <rect width="20" height="14" x="2" y="3" rx="2" />
    <line x1="8" x2="16" y1="21" y2="21" />
    <line x1="12" x2="12" y1="17" y2="21" />
  </svg>
);

export const ThemeToggle: React.FC<ThemeToggleProps> = ({
  variant = 'switch',
  className = '',
  style = {},
}) => {
  const { mode, theme, setMode, toggleTheme } = useTheme();
  const isDark = theme === 'dark';

  // 1. Sleek Icon Symbol Button
  if (variant === 'icon') {
    return (
      <button
        type="button"
        onClick={toggleTheme}
        className={`theme-toggle-symbol-btn ${className}`}
        aria-label={`Current: ${isDark ? 'Dark' : 'Light'}. Click to toggle theme.`}
        title={`Theme: ${mode === 'system' ? 'System (' + theme + ')' : theme}. Click to toggle.`}
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          width: '34px',
          height: '34px',
          borderRadius: '8px',
          border: '1px solid var(--border-color)',
          backgroundColor: 'var(--bg-card)',
          color: isDark ? '#F1F5F9' : '#334155',
          cursor: 'pointer',
          transition: 'all 0.18s ease',
          boxShadow: 'var(--shadow-card)',
          ...style,
        }}
      >
        {isDark ? <SunIcon size={16} /> : <MoonIcon size={16} />}
      </button>
    );
  }

  // 2. Three-Option Segmented Control (System / Light / Dark)
  if (variant === 'segmented') {
    const options: { id: ThemeMode; label: string; icon: React.ReactNode }[] = [
      { id: 'light', label: 'Light', icon: <SunIcon size={13} /> },
      { id: 'system', label: 'Auto', icon: <MonitorIcon size={13} /> },
      { id: 'dark', label: 'Dark', icon: <MoonIcon size={13} /> },
    ];

    return (
      <div
        className={`theme-segmented-group ${className}`}
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          backgroundColor: isDark ? 'rgba(255, 255, 255, 0.05)' : '#F1F5F9',
          border: '1px solid var(--border-color)',
          borderRadius: '8px',
          padding: '2px',
          gap: '2px',
          ...style,
        }}
      >
        {options.map((opt) => {
          const isActive = mode === opt.id;
          return (
            <button
              key={opt.id}
              type="button"
              onClick={() => setMode(opt.id)}
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: '5px',
                padding: '4px 9px',
                fontSize: '12px',
                fontWeight: isActive ? 600 : 500,
                color: isActive ? 'var(--text-main)' : 'var(--text-muted)',
                backgroundColor: isActive ? 'var(--bg-card)' : 'transparent',
                border: isActive ? '1px solid var(--border-color)' : '1px solid transparent',
                borderRadius: '6px',
                cursor: 'pointer',
                transition: 'all 0.15s ease',
                boxShadow: isActive ? '0 1px 3px rgba(0,0,0,0.1)' : 'none',
              }}
              title={`Switch to ${opt.label} mode`}
            >
              {opt.icon}
              <span>{opt.label}</span>
            </button>
          );
        })}
      </div>
    );
  }

  // 3. Sidebar Row Button with Integrated Switch
  if (variant === 'button') {
    return (
      <div
        className={`theme-sidebar-row ${className}`}
        style={{
          width: '100%',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '8px 12px',
          fontSize: '13px',
          color: 'var(--text-main)',
          backgroundColor: 'var(--bg-card)',
          border: '1px solid var(--border-color)',
          borderRadius: '8px',
          fontWeight: 500,
          ...style,
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <span style={{ color: 'var(--text-muted)' }}>
            {isDark ? <MoonIcon size={14} /> : <SunIcon size={14} />}
          </span>
          <span style={{ fontSize: '13px', color: 'var(--text-main)' }}>Theme</span>
          {mode === 'system' && (
            <span
              style={{
                fontSize: '10px',
                fontWeight: 600,
                textTransform: 'uppercase',
                letterSpacing: '0.04em',
                padding: '1px 5px',
                borderRadius: '4px',
                backgroundColor: isDark ? 'rgba(255, 255, 255, 0.08)' : '#F1F5F9',
                color: 'var(--text-muted)',
              }}
            >
              Auto
            </span>
          )}
        </div>

        {/* Minimalist Switch Toggle */}
        <button
          type="button"
          onClick={toggleTheme}
          role="switch"
          aria-checked={isDark}
          aria-label="Toggle dark mode"
          title={`Switch to ${isDark ? 'light' : 'dark'} mode`}
          style={{
            position: 'relative',
            width: '42px',
            height: '22px',
            borderRadius: '9999px',
            backgroundColor: isDark ? '#14B8A6' : '#CBD5E1',
            border: 'none',
            cursor: 'pointer',
            padding: 0,
            display: 'inline-flex',
            alignItems: 'center',
            transition: 'background-color 0.2s ease',
            outline: 'none',
          }}
        >
          <span
            style={{
              position: 'absolute',
              left: isDark ? '22px' : '2px',
              width: '18px',
              height: '18px',
              borderRadius: '50%',
              backgroundColor: '#FFFFFF',
              boxShadow: '0 1px 3px rgba(0,0,0,0.25)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: isDark ? '#0F172A' : '#64748B',
              transition: 'left 0.2s cubic-bezier(0.4, 0, 0.2, 1)',
            }}
          >
            {isDark ? <MoonIcon size={10} /> : <SunIcon size={10} />}
          </span>
        </button>
      </div>
    );
  }

  // 4. Default Sleek Sliding Symbol Switch (variant="switch")
  return (
    <div
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: '8px',
        ...style,
      }}
      className={className}
    >
      <button
        type="button"
        onClick={toggleTheme}
        role="switch"
        aria-checked={isDark}
        aria-label={`Toggle theme (currently ${theme})`}
        title={`Current: ${mode === 'system' ? 'System default (' + theme + ')' : theme}. Click to toggle.`}
        style={{
          position: 'relative',
          width: '46px',
          height: '24px',
          borderRadius: '9999px',
          backgroundColor: isDark ? '#0D9488' : '#E2E8F0',
          border: '1px solid var(--border-color)',
          cursor: 'pointer',
          padding: 0,
          display: 'inline-flex',
          alignItems: 'center',
          transition: 'all 0.2s ease',
          outline: 'none',
        }}
      >
        <span
          style={{
            position: 'absolute',
            left: isDark ? '24px' : '2px',
            width: '18px',
            height: '18px',
            borderRadius: '50%',
            backgroundColor: isDark ? '#0F172A' : '#FFFFFF',
            border: isDark ? '1px solid rgba(255,255,255,0.1)' : 'none',
            boxShadow: '0 1px 3px rgba(0,0,0,0.2)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: isDark ? '#2DD4BF' : '#475569',
            transition: 'left 0.2s cubic-bezier(0.4, 0, 0.2, 1)',
          }}
        >
          {isDark ? <MoonIcon size={11} /> : <SunIcon size={11} />}
        </span>
      </button>

      {/* Subtle indicator showing if on System preference */}
      {mode === 'system' && (
        <span
          style={{
            fontSize: '11px',
            color: 'var(--text-muted)',
            fontWeight: 500,
            userSelect: 'none',
          }}
          title="Following system appearance"
        >
          Auto
        </span>
      )}
    </div>
  );
};
