import React, { useState } from 'react';
import { accountsApi } from '../api/accounts';
import type { AccountResponse } from '../api/types';
import { maskAccountNumber } from '../utils/formatters';

interface DownloadStatementModalProps {
  isOpen: boolean;
  onClose: () => void;
  account: AccountResponse | null;
}

export const DownloadStatementModal: React.FC<DownloadStatementModalProps> = ({
  isOpen,
  onClose,
  account,
}) => {
  const [format, setFormat] = useState<'pdf' | 'csv'>('pdf');
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!isOpen || !account) return null;

  const handleDownload = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      setLoading(true);
      setError(null);

      const startIso = startDate ? new Date(startDate).toISOString() : undefined;
      const endIso = endDate ? new Date(endDate + 'T23:59:59').toISOString() : undefined;

      await accountsApi.downloadStatement(account.id, format, startIso, endIso);
      onClose();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to download statement';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      style={{
        position: 'fixed',
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
        backgroundColor: 'rgba(0, 0, 0, 0.5)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 1000,
        padding: '16px',
      }}
      onClick={onClose}
    >
      <div
        className="card"
        style={{
          width: '100%',
          maxWidth: '460px',
          backgroundColor: '#FFFFFF',
          borderRadius: '8px',
          padding: '24px',
          boxShadow: '0 20px 25px -5px rgba(0, 0, 0, 0.1)',
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
          <h2 style={{ fontSize: '18px', fontWeight: 600, color: '#111827', margin: 0 }}>
            Download Account Statement
          </h2>
          <button
            type="button"
            onClick={onClose}
            style={{ background: 'none', border: 'none', fontSize: '20px', cursor: 'pointer', color: '#6B7280' }}
          >
            ×
          </button>
        </div>

        <p style={{ fontSize: '13px', color: '#4B5563', marginBottom: '16px' }}>
          Generate an official bank statement for <strong>Account {maskAccountNumber(account.accountNumber)}</strong>.
        </p>

        {error && (
          <div className="alert alert-danger" style={{ marginBottom: '16px', padding: '10px 14px', fontSize: '13px' }}>
            {error}
          </div>
        )}

        <form onSubmit={handleDownload}>
          <div style={{ marginBottom: '16px' }}>
            <label style={{ display: 'block', fontSize: '13px', fontWeight: 500, color: '#374151', marginBottom: '6px' }}>
              Statement Format
            </label>
            <div style={{ display: 'flex', gap: '12px' }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '13px', cursor: 'pointer' }}>
                <input
                  type="radio"
                  name="statementFormat"
                  value="pdf"
                  checked={format === 'pdf'}
                  onChange={() => setFormat('pdf')}
                />
                PDF Document (.pdf)
              </label>
              <label style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '13px', cursor: 'pointer' }}>
                <input
                  type="radio"
                  name="statementFormat"
                  value="csv"
                  checked={format === 'csv'}
                  onChange={() => setFormat('csv')}
                />
                CSV Spreadsheet (.csv)
              </label>
            </div>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px', marginBottom: '20px' }}>
            <div>
              <label style={{ display: 'block', fontSize: '13px', fontWeight: 500, color: '#374151', marginBottom: '6px' }}>
                Start Date (Optional)
              </label>
              <input
                type="date"
                value={startDate}
                onChange={(e) => setStartDate(e.target.value)}
                style={{
                  width: '100%',
                  padding: '8px 12px',
                  borderRadius: '6px',
                  border: '1px solid #D1D5DB',
                  fontSize: '13px',
                }}
              />
            </div>
            <div>
              <label style={{ display: 'block', fontSize: '13px', fontWeight: 500, color: '#374151', marginBottom: '6px' }}>
                End Date (Optional)
              </label>
              <input
                type="date"
                value={endDate}
                onChange={(e) => setEndDate(e.target.value)}
                style={{
                  width: '100%',
                  padding: '8px 12px',
                  borderRadius: '6px',
                  border: '1px solid #D1D5DB',
                  fontSize: '13px',
                }}
              />
            </div>
          </div>

          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px' }}>
            <button
              type="button"
              onClick={onClose}
              className="btn btn-secondary"
              style={{ padding: '8px 16px', fontSize: '13px' }}
              disabled={loading}
            >
              Cancel
            </button>
            <button
              type="submit"
              className="btn btn-primary"
              style={{ padding: '8px 16px', fontSize: '13px', backgroundColor: '#0D6E64' }}
              disabled={loading}
            >
              {loading ? 'Generating...' : `Download ${format.toUpperCase()}`}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
