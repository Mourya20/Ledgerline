import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import React from 'react';
import { ThemeProvider, useTheme } from './ThemeContext';
import { ThemeToggle } from '../components/ThemeToggle';

const ThemeTester: React.FC = () => {
  const { theme, toggleTheme } = useTheme();
  return (
    <div>
      <span data-testid="current-theme">{theme}</span>
      <button onClick={toggleTheme}>Toggle</button>
      <ThemeToggle variant="pill" />
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

  it('defaults to light theme when no preference stored', () => {
    render(
      <ThemeProvider>
        <ThemeTester />
      </ThemeProvider>
    );

    expect(screen.getByTestId('current-theme').textContent).toBe('light');
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

    expect(screen.getByTestId('current-theme').textContent).toBe('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(localStorage.getItem('vaultora_theme')).toBe('dark');

    fireEvent.click(toggleButton);
    expect(screen.getByTestId('current-theme').textContent).toBe('light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    expect(localStorage.getItem('vaultora_theme')).toBe('light');
  });

  it('restores stored theme from localStorage on load', () => {
    localStorage.setItem('vaultora_theme', 'dark');

    render(
      <ThemeProvider>
        <ThemeTester />
      </ThemeProvider>
    );

    expect(screen.getByTestId('current-theme').textContent).toBe('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });
});
