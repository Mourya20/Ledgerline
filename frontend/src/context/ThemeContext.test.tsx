import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import React from 'react';
import { ThemeProvider, useTheme } from './ThemeContext';
import { ThemeToggle } from '../components/ThemeToggle';

const ThemeTester: React.FC = () => {
  const { mode, theme, toggleTheme, setMode } = useTheme();
  return (
    <div>
      <span data-testid="current-mode">{mode}</span>
      <span data-testid="current-theme">{theme}</span>
      <button onClick={toggleTheme}>Toggle</button>
      <button onClick={() => setMode('dark')}>SetDark</button>
      <ThemeToggle variant="switch" />
      <ThemeToggle variant="segmented" />
    </div>
  );
};

describe('ThemeContext and ThemeToggle', () => {
  beforeEach(() => {
    cleanup();
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
  });

  afterEach(() => {
    cleanup();
  });

  it('defaults to light mode on first load', () => {
    render(
      <ThemeProvider>
        <ThemeTester />
      </ThemeProvider>
    );

    expect(screen.getByTestId('current-mode').textContent).toBe('light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
  });

  it('toggles between light and dark theme on click', () => {
    render(
      <ThemeProvider>
        <ThemeTester />
      </ThemeProvider>
    );

    const toggleButton = screen.getByText('Toggle');
    fireEvent.click(toggleButton);

    const themeAfterOneClick = screen.getByTestId('current-theme').textContent;
    expect(themeAfterOneClick).toBe('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');

    fireEvent.click(toggleButton);
    expect(screen.getByTestId('current-theme').textContent).toBe('light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
  });

  it('allows explicit light and dark mode setting', () => {
    render(
      <ThemeProvider>
        <ThemeTester />
      </ThemeProvider>
    );

    fireEvent.click(screen.getByText('SetDark'));
    expect(screen.getByTestId('current-mode').textContent).toBe('dark');
    expect(screen.getByTestId('current-theme').textContent).toBe('dark');
    expect(localStorage.getItem('vaultora_theme_mode')).toBe('dark');

  });

  it('falls back to light mode when a removed system preference is stored', () => {
    localStorage.setItem('vaultora_theme_mode', 'system');

    render(
      <ThemeProvider>
        <ThemeTester />
      </ThemeProvider>
    );

    expect(screen.getByTestId('current-mode').textContent).toBe('light');
    expect(screen.getByTestId('current-theme').textContent).toBe('light');
  });

  it('restores stored mode from localStorage on load', () => {
    localStorage.setItem('vaultora_theme_mode', 'dark');

    render(
      <ThemeProvider>
        <ThemeTester />
      </ThemeProvider>
    );

    expect(screen.getByTestId('current-mode').textContent).toBe('dark');
    expect(screen.getByTestId('current-theme').textContent).toBe('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });
});
