import React, { useEffect, useState, useCallback } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { auditApi } from '../api/audit';
import type {
  Page,
  AuditLogItem,
  AuditorTransactionResponse,
  LedgerIntegrityResponse,
  TransactionStatus
} from '../api/types';
import { formatDate, formatCurrency } from '../utils/formatters';
import { PortalSwitcher } from '../components/PortalSwitcher';
import { ChangePasswordModal } from '../components/ChangePasswordModal';
import { ThemeToggle } from '../components/ThemeToggle';

type AuditorTab = 'logs' | 'transactions' | 'integrity';

export const AuditorPage: React.FC = () => {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const [activeTab, setActiveTab] = useState<AuditorTab>('logs');
  const [passwordModalOpen, setPasswordModalOpen] = useState(false);

  // --- TAB: AUDIT LOGS STATE ---
  const [logsData, setLogsData] = useState<Page<AuditLogItem> | null>(null);
  const [logsPage, setLogsPage] = useState<number>(0);
  const [logsPageSize, setLogsPageSize] = useState<number>(15);
  const [logsLoading, setLogsLoading] = useState<boolean>(true);
  const [logsError, setLogsError] = useState<string | null>(null);
  const [selectedLog, setSelectedLog] = useState<AuditLogItem | null>(null);

  // --- TAB: TRANSACTIONS INSPECTION STATE ---
  const [txData, setTxData] = useState<Page<AuditorTransactionResponse> | null>(null);
  const [txPage, setTxPage] = useState<number>(0);
  const [txPageSize, setTxPageSize] = useState<number>(15);
  const [txLoading, setTxLoading] = useState<boolean>(false);
  const [txError, setTxError] = useState<string | null>(null);
  const [exportingCsv, setExportingCsv] = useState<boolean>(false);

  // Filters
  const [filterStartDate, setFilterStartDate] = useState<string>('');
  const [filterEndDate, setFilterEndDate] = useState<string>('');
  const [filterEmail, setFilterEmail] = useState<string>('');
  const [filterType, setFilterType] = useState<string>('');
  const [filterStatus, setFilterStatus] = useState<TransactionStatus | ''>('');
  const [filterMinAmount, setFilterMinAmount] = useState<string>('');
  const [filterMaxAmount, setFilterMaxAmount] = useState<string>('');

  // --- TAB: LEDGER INTEGRITY STATE ---
  const [integrityData, setIntegrityData] = useState<LedgerIntegrityResponse | null>(null);
  const [integrityLoading, setIntegrityLoading] = useState<boolean>(false);
  const [integrityError, setIntegrityError] = useState<string | null>(null);

  // Fetch Audit Logs
  const fetchLogs = useCallback(async () => {
    try {
      setLogsLoading(true);
      setLogsError(null);
      const res = await auditApi.getLogs({
        page: logsPage,
        size: logsPageSize,
        sort: 'createdAt,desc',
      });
      setLogsData(res);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to retrieve audit trail.';
      setLogsError(msg);
    } finally {
      setLogsLoading(false);
    }
  }, [logsPage, logsPageSize]);

  // Fetch Transactions
  const fetchTransactions = useCallback(async () => {
    try {
      setTxLoading(true);
      setTxError(null);

      const startIso = filterStartDate ? new Date(filterStartDate).toISOString() : undefined;
      const endIso = filterEndDate ? new Date(filterEndDate + 'T23:59:59').toISOString() : undefined;
      const minAmt = filterMinAmount ? Number(filterMinAmount) : undefined;
      const maxAmt = filterMaxAmount ? Number(filterMaxAmount) : undefined;

      const res = await auditApi.getTransactions({
        startDate: startIso,
        endDate: endIso,
        userEmail: filterEmail.trim() || undefined,
        type: filterType || undefined,
        status: (filterStatus as TransactionStatus) || undefined,
        minAmount: minAmt,
        maxAmount: maxAmt,
        page: txPage,
        size: txPageSize,
        sort: 'createdAt,desc',
      });
      setTxData(res);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to retrieve transactions.';
      setTxError(msg);
    } finally {
      setTxLoading(false);
    }
  }, [txPage, txPageSize, filterStartDate, filterEndDate, filterEmail, filterType, filterStatus, filterMinAmount, filterMaxAmount]);

  // Fetch Ledger Integrity
  const fetchIntegrity = useCallback(async () => {
    try {
      setIntegrityLoading(true);
      setIntegrityError(null);
      const res = await auditApi.getLedgerIntegrity();
      setIntegrityData(res);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to run ledger integrity verification.';
      setIntegrityError(msg);
    } finally {
      setIntegrityLoading(false);
    }
  }, []);

  useEffect(() => {
    let ignore = false;
    const run = async () => {
      await Promise.resolve();
      if (ignore) return;
      if (activeTab === 'logs') {
        fetchLogs();
      } else if (activeTab === 'transactions') {
        fetchTransactions();
      } else if (activeTab === 'integrity') {
        fetchIntegrity();
      }
    };
    run();
    return () => {
      ignore = true;
    };
  }, [activeTab, fetchLogs, fetchTransactions, fetchIntegrity]);

  const handleExportCsv = async () => {
    try {
      setExportingCsv(true);
      const startIso = filterStartDate ? new Date(filterStartDate).toISOString() : undefined;
      const endIso = filterEndDate ? new Date(filterEndDate + 'T23:59:59').toISOString() : undefined;
      const minAmt = filterMinAmount ? Number(filterMinAmount) : undefined;
      const maxAmt = filterMaxAmount ? Number(filterMaxAmount) : undefined;

      await auditApi.exportTransactionsCsv({
        startDate: startIso,
        endDate: endIso,
        userEmail: filterEmail.trim() || undefined,
        type: filterType || undefined,
        status: (filterStatus as TransactionStatus) || undefined,
        minAmount: minAmt,
        maxAmount: maxAmt,
      });
    } catch (err: unknown) {
      alert(err instanceof Error ? err.message : 'Failed to export CSV');
    } finally {
      setExportingCsv(false);
    }
  };

  const handleResetFilters = () => {
    setFilterStartDate('');
    setFilterEndDate('');
    setFilterEmail('');
    setFilterType('');
    setFilterStatus('');
    setFilterMinAmount('');
    setFilterMaxAmount('');
    setTxPage(0);
  };

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  const getActionBadgeClass = (action: string): string => {
    if (action.includes('SUCCESS') || action.includes('CREATED') || action.includes('APPROVED')) return 'badge-green';
    if (action.includes('FAILURE') || action.includes('EXCEEDED') || action.includes('DISABLED') || action.includes('REJECTED')) return 'badge-red';
    if (action.includes('TRANSACTION') || action.includes('REVERSED')) return 'badge-blue';
    return 'badge-gray';
  };

  const getStatusBadgeClass = (status: TransactionStatus): string => {
    switch (status) {
      case 'COMPLETED': return 'badge-green';
      case 'REVERSED': return 'badge-purple';
      case 'PENDING': return 'badge-yellow';
      case 'FAILED': return 'badge-red';
      default: return 'badge-gray';
    }
  };

  return (
    <div style={{ minHeight: '100vh', backgroundColor: '#F8FAFC', display: 'flex', flexDirection: 'column' }}>
      {/* Top Navigation Bar */}
      <header style={{
        backgroundColor: '#FFFFFF',
        borderBottom: '1px solid #E5E7EB',
        padding: '0 24px',
        height: '64px',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        position: 'sticky',
        top: 0,
        zIndex: 20
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <Link
            to="/"
            style={{
              fontWeight: 700,
              fontSize: '18px',
              color: '#111827',
              letterSpacing: '-0.02em',
              textDecoration: 'none'
            }}
          >
            Vaultora
          </Link>
          <span style={{
            fontSize: '12px',
            fontWeight: 600,
            padding: '2px 8px',
            borderRadius: '4px',
            backgroundColor: '#FEF3C7',
            color: '#92400E',
            border: '1px solid #FCD34D'
          }}>
            Auditor Portal
          </span>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
          <PortalSwitcher />
          <button
            type="button"
            onClick={() => setPasswordModalOpen(true)}
            style={{
              background: 'none',
              border: 'none',
              fontSize: '13px',
              color: '#4B5563',
              cursor: 'pointer',
              textDecoration: 'underline'
            }}
          >
            Change password
          </button>
          {user && (
            <span style={{ fontSize: '13px', color: 'var(--text-muted)' }}>
              {user.email}
            </span>
          )}
          <ThemeToggle variant="pill" />
          <button
            type="button"
            onClick={handleLogout}
            className="btn btn-secondary"
            style={{ padding: '6px 12px', fontSize: '13px' }}
          >
            Sign out
          </button>
        </div>
      </header>

      {/* Main Content Area */}
      <main style={{ flex: 1, padding: '32px 24px', maxWidth: '1200px', width: '100%', margin: '0 auto' }}>
        <div style={{ marginBottom: '24px', display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: '16px' }}>
          <div>
            <h1 style={{ fontSize: '24px', fontWeight: 700, color: '#111827', margin: 0, letterSpacing: '-0.02em' }}>
              Auditor Inspection & Integrity Console
            </h1>
            <p style={{ fontSize: '14px', color: '#6B7280', marginTop: '6px', margin: 0 }}>
              Read-only inspection of immutable audit logs, ledger transactions, and automated balance reconciliation.
            </p>
          </div>
        </div>

        {/* Tab Navigation */}
        <div style={{ display: 'flex', gap: '8px', borderBottom: '1px solid #E5E7EB', marginBottom: '24px' }}>
          {[
            { id: 'logs', label: 'Audit Trail' },
            { id: 'transactions', label: 'Transaction Inspection' },
            { id: 'integrity', label: 'Ledger Integrity' },
          ].map((tab) => (
            <button
              key={tab.id}
              type="button"
              onClick={() => setActiveTab(tab.id as AuditorTab)}
              style={{
                padding: '10px 18px',
                fontSize: '14px',
                fontWeight: activeTab === tab.id ? 600 : 500,
                color: activeTab === tab.id ? '#0D6E64' : '#6B7280',
                borderBottom: activeTab === tab.id ? '2px solid #0D6E64' : '2px solid transparent',
                backgroundColor: 'transparent',
                borderTop: 'none',
                borderLeft: 'none',
                borderRight: 'none',
                cursor: 'pointer',
              }}
            >
              {tab.label}
            </button>
          ))}
        </div>

        {/* ============================================================== */}
        {/* TAB 1: AUDIT LOGS */}
        {/* ============================================================== */}
        {activeTab === 'logs' && (
          <div>
            {logsError && (
              <div className="alert alert-danger" style={{ marginBottom: '16px' }}>
                {logsError}
              </div>
            )}

            <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
              <div style={{
                padding: '16px 20px',
                borderBottom: '1px solid #E5E7EB',
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                backgroundColor: '#FAFAFA'
              }}>
                <div style={{ fontSize: '13px', fontWeight: 600, color: '#374151' }}>
                  {logsData ? `Showing ${logsData.numberOfElements} of ${logsData.totalElements} recorded events` : 'Loading events...'}
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '13px', color: '#6B7280' }}>
                  <span>Page size:</span>
                  <select
                    value={logsPageSize}
                    onChange={(e) => {
                      setLogsPageSize(Number(e.target.value));
                      setLogsPage(0);
                    }}
                    style={{
                      padding: '4px 8px',
                      borderRadius: '6px',
                      border: '1px solid #D1D5DB',
                      fontSize: '13px',
                      backgroundColor: '#FFFFFF'
                    }}
                  >
                    <option value={10}>10</option>
                    <option value={15}>15</option>
                    <option value={25}>25</option>
                    <option value={50}>50</option>
                  </select>
                  <button
                    type="button"
                    className="btn btn-secondary"
                    onClick={fetchLogs}
                    disabled={logsLoading}
                    style={{ fontSize: '12px', padding: '4px 10px' }}
                  >
                    {logsLoading ? '...' : 'Refresh'}
                  </button>
                </div>
              </div>

              <div style={{ overflowX: 'auto' }}>
                <table className="table" style={{ margin: 0 }}>
                  <thead>
                    <tr>
                      <th style={{ width: '180px' }}>Timestamp (UTC)</th>
                      <th style={{ width: '190px' }}>Action</th>
                      <th style={{ width: '120px' }}>Resource</th>
                      <th>Actor / Subject</th>
                      <th>Client IP</th>
                      <th style={{ width: '90px', textAlign: 'right' }}>Details</th>
                    </tr>
                  </thead>
                  <tbody>
                    {logsLoading && (!logsData || logsData.content.length === 0) ? (
                      <tr>
                        <td colSpan={6} style={{ textAlign: 'center', padding: '48px 16px', color: '#6B7280' }}>
                          Loading audit records...
                        </td>
                      </tr>
                    ) : !logsData || logsData.content.length === 0 ? (
                      <tr>
                        <td colSpan={6} style={{ textAlign: 'center', padding: '48px 16px', color: '#6B7280' }}>
                          No audit events recorded.
                        </td>
                      </tr>
                    ) : (
                      logsData.content.map((item) => {
                        const clientIp = (item.details?.ip as string) || '—';
                        const email = (item.details?.email as string) || null;

                        return (
                          <tr key={item.id}>
                            <td style={{ fontSize: '13px', color: '#374151', whiteSpace: 'nowrap' }}>
                              {formatDate(item.createdAt)}
                            </td>
                            <td>
                              <span className={`badge ${getActionBadgeClass(item.action)}`} style={{ fontSize: '11px' }}>
                                {item.action}
                              </span>
                            </td>
                            <td style={{ fontSize: '13px', color: '#4B5563' }}>
                              {item.resourceType || '—'}
                            </td>
                            <td style={{ fontSize: '13px', color: '#111827' }}>
                              {email ? email : item.actorUserId ? `User ${item.actorUserId.substring(0, 8)}...` : 'System'}
                            </td>
                            <td style={{ fontSize: '13px', color: '#4B5563', fontFamily: 'monospace' }}>
                              {clientIp}
                            </td>
                            <td style={{ textAlign: 'right' }}>
                              <button
                                type="button"
                                className="btn-outline"
                                style={{ padding: '3px 8px', fontSize: '11px' }}
                                onClick={() => setSelectedLog(item)}
                              >
                                View
                              </button>
                            </td>
                          </tr>
                        );
                      })
                    )}
                  </tbody>
                </table>
              </div>

              {logsData && logsData.totalPages > 1 && (
                <div style={{
                  padding: '12px 20px',
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  borderTop: '1px solid #E5E7EB',
                  backgroundColor: '#FFFFFF',
                  fontSize: '13px'
                }}>
                  <span style={{ color: '#6B7280' }}>
                    Page {logsPage + 1} of {logsData.totalPages}
                  </span>
                  <div style={{ display: 'flex', gap: '8px' }}>
                    <button
                      type="button"
                      className="btn-outline"
                      style={{ padding: '4px 10px', fontSize: '12px' }}
                      disabled={logsData.first || logsLoading}
                      onClick={() => setLogsPage((p) => Math.max(0, p - 1))}
                    >
                      Previous
                    </button>
                    <button
                      type="button"
                      className="btn-outline"
                      style={{ padding: '4px 10px', fontSize: '12px' }}
                      disabled={logsData.last || logsLoading}
                      onClick={() => setLogsPage((p) => p + 1)}
                    >
                      Next
                    </button>
                  </div>
                </div>
              )}
            </div>
          </div>
        )}

        {/* ============================================================== */}
        {/* TAB 2: TRANSACTIONS INSPECTION */}
        {/* ============================================================== */}
        {activeTab === 'transactions' && (
          <div>
            {txError && (
              <div className="alert alert-danger" style={{ marginBottom: '16px' }}>
                {txError}
              </div>
            )}

            {/* Filter Toolbar */}
            <div className="card" style={{ marginBottom: '20px', padding: '16px 20px' }}>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: '12px', marginBottom: '16px' }}>
                <div>
                  <label style={{ display: 'block', fontSize: '12px', fontWeight: 500, color: '#374151', marginBottom: '4px' }}>
                    Start Date
                  </label>
                  <input
                    type="date"
                    value={filterStartDate}
                    onChange={(e) => setFilterStartDate(e.target.value)}
                    style={{ width: '100%', padding: '6px 10px', fontSize: '13px', borderRadius: '6px', border: '1px solid #D1D5DB' }}
                  />
                </div>
                <div>
                  <label style={{ display: 'block', fontSize: '12px', fontWeight: 500, color: '#374151', marginBottom: '4px' }}>
                    End Date
                  </label>
                  <input
                    type="date"
                    value={filterEndDate}
                    onChange={(e) => setFilterEndDate(e.target.value)}
                    style={{ width: '100%', padding: '6px 10px', fontSize: '13px', borderRadius: '6px', border: '1px solid #D1D5DB' }}
                  />
                </div>
                <div>
                  <label style={{ display: 'block', fontSize: '12px', fontWeight: 500, color: '#374151', marginBottom: '4px' }}>
                    Customer Email
                  </label>
                  <input
                    type="text"
                    placeholder="Search by email..."
                    value={filterEmail}
                    onChange={(e) => setFilterEmail(e.target.value)}
                    style={{ width: '100%', padding: '6px 10px', fontSize: '13px', borderRadius: '6px', border: '1px solid #D1D5DB' }}
                  />
                </div>
                <div>
                  <label style={{ display: 'block', fontSize: '12px', fontWeight: 500, color: '#374151', marginBottom: '4px' }}>
                    Type
                  </label>
                  <select
                    value={filterType}
                    onChange={(e) => setFilterType(e.target.value)}
                    style={{ width: '100%', padding: '6px 10px', fontSize: '13px', borderRadius: '6px', border: '1px solid #D1D5DB', backgroundColor: '#FFF' }}
                  >
                    <option value="">All Types</option>
                    <option value="DEPOSIT">DEPOSIT</option>
                    <option value="WITHDRAWAL">WITHDRAWAL</option>
                    <option value="TRANSFER">TRANSFER</option>
                    <option value="REVERSAL">REVERSAL</option>
                  </select>
                </div>
                <div>
                  <label style={{ display: 'block', fontSize: '12px', fontWeight: 500, color: '#374151', marginBottom: '4px' }}>
                    Status
                  </label>
                  <select
                    value={filterStatus}
                    onChange={(e) => setFilterStatus(e.target.value as TransactionStatus | '')}
                    style={{ width: '100%', padding: '6px 10px', fontSize: '13px', borderRadius: '6px', border: '1px solid #D1D5DB', backgroundColor: '#FFF' }}
                  >
                    <option value="">All Statuses</option>
                    <option value="COMPLETED">COMPLETED</option>
                    <option value="PENDING">PENDING</option>
                    <option value="FAILED">FAILED</option>
                    <option value="REVERSED">REVERSED</option>
                  </select>
                </div>
                <div>
                  <label style={{ display: 'block', fontSize: '12px', fontWeight: 500, color: '#374151', marginBottom: '4px' }}>
                    Min Amount (₹)
                  </label>
                  <input
                    type="number"
                    placeholder="0.00"
                    value={filterMinAmount}
                    onChange={(e) => setFilterMinAmount(e.target.value)}
                    style={{ width: '100%', padding: '6px 10px', fontSize: '13px', borderRadius: '6px', border: '1px solid #D1D5DB' }}
                  />
                </div>
                <div>
                  <label style={{ display: 'block', fontSize: '12px', fontWeight: 500, color: '#374151', marginBottom: '4px' }}>
                    Max Amount (₹)
                  </label>
                  <input
                    type="number"
                    placeholder="No max"
                    value={filterMaxAmount}
                    onChange={(e) => setFilterMaxAmount(e.target.value)}
                    style={{ width: '100%', padding: '6px 10px', fontSize: '13px', borderRadius: '6px', border: '1px solid #D1D5DB' }}
                  />
                </div>
              </div>

              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '10px' }}>
                <div style={{ display: 'flex', gap: '8px' }}>
                  <button
                    type="button"
                    className="btn btn-primary"
                    onClick={() => { setTxPage(0); fetchTransactions(); }}
                    disabled={txLoading}
                    style={{ fontSize: '13px', padding: '6px 14px', backgroundColor: '#0D6E64' }}
                  >
                    {txLoading ? 'Searching...' : 'Apply Filters'}
                  </button>
                  <button
                    type="button"
                    className="btn btn-secondary"
                    onClick={handleResetFilters}
                    style={{ fontSize: '13px', padding: '6px 12px' }}
                  >
                    Reset
                  </button>
                </div>

                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={handleExportCsv}
                  disabled={exportingCsv}
                  style={{ fontSize: '13px', padding: '6px 14px', display: 'flex', alignItems: 'center', gap: '6px' }}
                >
                  <span>📥</span> {exportingCsv ? 'Exporting...' : 'Export Filtered CSV'}
                </button>
              </div>
            </div>

            {/* Transactions Table Card */}
            <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
              <div style={{
                padding: '16px 20px',
                borderBottom: '1px solid #E5E7EB',
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                backgroundColor: '#FAFAFA'
              }}>
                <div style={{ fontSize: '13px', fontWeight: 600, color: '#374151' }}>
                  {txData ? `Showing ${txData.numberOfElements} of ${txData.totalElements} transactions` : 'Loading...'}
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '13px', color: '#6B7280' }}>
                  <span>Page size:</span>
                  <select
                    value={txPageSize}
                    onChange={(e) => {
                      setTxPageSize(Number(e.target.value));
                      setTxPage(0);
                    }}
                    style={{
                      padding: '4px 8px',
                      borderRadius: '6px',
                      border: '1px solid #D1D5DB',
                      fontSize: '13px',
                      backgroundColor: '#FFFFFF'
                    }}
                  >
                    <option value={10}>10</option>
                    <option value={15}>15</option>
                    <option value={25}>25</option>
                    <option value={50}>50</option>
                  </select>
                </div>
              </div>

              <div style={{ overflowX: 'auto' }}>
                <table className="table" style={{ margin: 0 }}>
                  <thead>
                    <tr>
                      <th style={{ width: '160px' }}>Date (UTC)</th>
                      <th style={{ width: '110px' }}>Type</th>
                      <th style={{ width: '110px' }}>Status</th>
                      <th>Initiated By</th>
                      <th>Source / Dest</th>
                      <th style={{ textAlign: 'right' }}>Amount</th>
                      <th>References</th>
                    </tr>
                  </thead>
                  <tbody>
                    {txLoading && (!txData || txData.content.length === 0) ? (
                      <tr>
                        <td colSpan={7} style={{ textAlign: 'center', padding: '48px 16px', color: '#6B7280' }}>
                          Loading transactions...
                        </td>
                      </tr>
                    ) : !txData || txData.content.length === 0 ? (
                      <tr>
                        <td colSpan={7} style={{ textAlign: 'center', padding: '48px 16px', color: '#6B7280' }}>
                          No transactions found matching your filter criteria.
                        </td>
                      </tr>
                    ) : (
                      txData.content.map((tx) => (
                        <tr key={tx.id}>
                          <td style={{ fontSize: '13px', color: '#374151', whiteSpace: 'nowrap' }}>
                            {formatDate(tx.createdAt)}
                          </td>
                          <td style={{ fontSize: '13px', fontWeight: 600 }}>
                            {tx.transactionType}
                          </td>
                          <td>
                            <span className={`badge ${getStatusBadgeClass(tx.status)}`} style={{ fontSize: '11px' }}>
                              {tx.status}
                            </span>
                          </td>
                          <td style={{ fontSize: '13px', color: '#111827' }}>
                            {tx.initiatedByEmail}
                          </td>
                          <td style={{ fontSize: '12px', color: '#4B5563' }}>
                            {tx.sourceAccount && <div>From: {tx.sourceAccount}</div>}
                            {tx.destinationAccount && <div>To: {tx.destinationAccount}</div>}
                          </td>
                          <td style={{ textAlign: 'right', fontSize: '13px', fontWeight: 600, color: '#111827' }}>
                            {formatCurrency(tx.amount, tx.currency)}
                          </td>
                          <td style={{ fontSize: '11px', color: '#6B7280', fontFamily: 'monospace' }}>
                            <div>ID: {tx.id.substring(0, 8)}...</div>
                            {tx.relatedTransactionId && <div>Rel: {tx.relatedTransactionId.substring(0, 8)}...</div>}
                            {tx.reversalReason && <div style={{ color: '#9333EA' }}>Reason: {tx.reversalReason}</div>}
                          </td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>

              {txData && txData.totalPages > 1 && (
                <div style={{
                  padding: '12px 20px',
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  borderTop: '1px solid #E5E7EB',
                  backgroundColor: '#FFFFFF',
                  fontSize: '13px'
                }}>
                  <span style={{ color: '#6B7280' }}>
                    Page {txPage + 1} of {txData.totalPages}
                  </span>
                  <div style={{ display: 'flex', gap: '8px' }}>
                    <button
                      type="button"
                      className="btn-outline"
                      style={{ padding: '4px 10px', fontSize: '12px' }}
                      disabled={txData.first || txLoading}
                      onClick={() => setTxPage((p) => Math.max(0, p - 1))}
                    >
                      Previous
                    </button>
                    <button
                      type="button"
                      className="btn-outline"
                      style={{ padding: '4px 10px', fontSize: '12px' }}
                      disabled={txData.last || txLoading}
                      onClick={() => setTxPage((p) => p + 1)}
                    >
                      Next
                    </button>
                  </div>
                </div>
              )}
            </div>
          </div>
        )}

        {/* ============================================================== */}
        {/* TAB 3: LEDGER INTEGRITY */}
        {/* ============================================================== */}
        {activeTab === 'integrity' && (
          <div>
            {integrityError && (
              <div className="alert alert-danger" style={{ marginBottom: '16px' }}>
                {integrityError}
              </div>
            )}

            <div className="card" style={{ marginBottom: '20px', padding: '24px' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px', flexWrap: 'wrap', gap: '16px' }}>
                <div>
                  <h2 style={{ fontSize: '18px', fontWeight: 600, color: '#111827', margin: 0 }}>
                    Automated Ledger Integrity Verification
                  </h2>
                  <p style={{ fontSize: '13px', color: '#6B7280', margin: '4px 0 0 0' }}>
                    Mathematically reconciles global debit-credit equality and account-by-account transaction entries.
                  </p>
                </div>
                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={fetchIntegrity}
                  disabled={integrityLoading}
                  style={{ backgroundColor: '#0D6E64', padding: '8px 16px', fontSize: '13px' }}
                >
                  {integrityLoading ? 'Verifying Integrity...' : 'Run Live Verification'}
                </button>
              </div>

              {integrityData ? (
                <div>
                  {/* Status Banner */}
                  <div style={{
                    padding: '16px 20px',
                    borderRadius: '8px',
                    marginBottom: '24px',
                    backgroundColor: integrityData.status === 'INTACT' ? '#ECFDF5' : '#FEF2F2',
                    border: `1px solid ${integrityData.status === 'INTACT' ? '#A7F3D0' : '#FECACA'}`,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    flexWrap: 'wrap',
                    gap: '12px'
                  }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                      <span style={{
                        fontSize: '24px',
                        lineHeight: 1
                      }}>
                        {integrityData.status === 'INTACT' ? '🛡️' : '⚠️'}
                      </span>
                      <div>
                        <div style={{
                          fontSize: '16px',
                          fontWeight: 700,
                          color: integrityData.status === 'INTACT' ? '#065F46' : '#991B1B'
                        }}>
                          Ledger Status: {integrityData.status}
                        </div>
                        <div style={{ fontSize: '13px', color: integrityData.status === 'INTACT' ? '#047857' : '#B91C1C' }}>
                          {integrityData.status === 'INTACT'
                            ? 'All financial entries balance perfectly. Stored account balances match sum of all ledger postings.'
                            : `${integrityData.discrepanciesCount} discrepancies detected in ledger reconciliation.`}
                        </div>
                      </div>
                    </div>

                    <div style={{ fontSize: '12px', color: '#6B7280' }}>
                      Verified: {formatDate(integrityData.verifiedAt)}
                    </div>
                  </div>

                  {/* Summary Metric Cards */}
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '16px', marginBottom: '24px' }}>
                    <div style={{ padding: '16px', backgroundColor: '#F9FAFB', borderRadius: '8px', border: '1px solid #E5E7EB' }}>
                      <div style={{ fontSize: '12px', color: '#6B7280', marginBottom: '4px' }}>Total Ledger Debits</div>
                      <div style={{ fontSize: '18px', fontWeight: 700, color: '#111827' }}>
                        {formatCurrency(integrityData.totalDebits, 'INR')}
                      </div>
                    </div>
                    <div style={{ padding: '16px', backgroundColor: '#F9FAFB', borderRadius: '8px', border: '1px solid #E5E7EB' }}>
                      <div style={{ fontSize: '12px', color: '#6B7280', marginBottom: '4px' }}>Total Ledger Credits</div>
                      <div style={{ fontSize: '18px', fontWeight: 700, color: '#111827' }}>
                        {formatCurrency(integrityData.totalCredits, 'INR')}
                      </div>
                    </div>
                    <div style={{ padding: '16px', backgroundColor: '#F9FAFB', borderRadius: '8px', border: '1px solid #E5E7EB' }}>
                      <div style={{ fontSize: '12px', color: '#6B7280', marginBottom: '4px' }}>Transactions Verified</div>
                      <div style={{ fontSize: '18px', fontWeight: 700, color: '#111827' }}>
                        {integrityData.transactionsChecked}
                      </div>
                    </div>
                    <div style={{ padding: '16px', backgroundColor: '#F9FAFB', borderRadius: '8px', border: '1px solid #E5E7EB' }}>
                      <div style={{ fontSize: '12px', color: '#6B7280', marginBottom: '4px' }}>Accounts Reconciled</div>
                      <div style={{ fontSize: '18px', fontWeight: 700, color: '#111827' }}>
                        {integrityData.accountsChecked}
                      </div>
                    </div>
                  </div>

                  {/* Discrepancies Details if any */}
                  {integrityData.discrepancies && integrityData.discrepancies.length > 0 && (
                    <div style={{ padding: '16px 20px', borderRadius: '8px', backgroundColor: '#FFF1F2', border: '1px solid #FDA4AF' }}>
                      <h3 style={{ fontSize: '14px', fontWeight: 600, color: '#9F1239', margin: '0 0 10px 0' }}>
                        Reconciliation Discrepancies
                      </h3>
                      <ul style={{ margin: 0, paddingLeft: '20px', fontSize: '13px', color: '#881337' }}>
                        {integrityData.discrepancies.map((disc, idx) => (
                          <li key={idx} style={{ marginBottom: '4px' }}>{disc}</li>
                        ))}
                      </ul>
                    </div>
                  )}
                </div>
              ) : integrityLoading ? (
                <div style={{ padding: '40px', textAlign: 'center', color: '#6B7280' }}>
                  Executing ledger reconciliation queries...
                </div>
              ) : (
                <div style={{ padding: '40px', textAlign: 'center', color: '#6B7280' }}>
                  Click &quot;Run Live Verification&quot; to test ledger integrity.
                </div>
              )}
            </div>
          </div>
        )}

        {/* Modal: View Audit Log Details */}
        {selectedLog && (
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
              padding: '16px'
            }}
            onClick={() => setSelectedLog(null)}
          >
            <div
              className="card"
              style={{
                width: '100%',
                maxWidth: '600px',
                backgroundColor: '#FFFFFF',
                borderRadius: '8px',
                padding: '24px',
                boxShadow: '0 20px 25px -5px rgba(0, 0, 0, 0.1)',
                maxHeight: '80vh',
                overflowY: 'auto'
              }}
              onClick={(e) => e.stopPropagation()}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
                <h2 style={{ fontSize: '18px', fontWeight: 600, color: '#111827', margin: 0 }}>
                  Audit Event Details
                </h2>
                <button
                  type="button"
                  onClick={() => setSelectedLog(null)}
                  style={{ background: 'none', border: 'none', fontSize: '20px', cursor: 'pointer', color: '#6B7280' }}
                >
                  ×
                </button>
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: '130px 1fr', gap: '8px 16px', fontSize: '13px', marginBottom: '16px' }}>
                <span style={{ color: '#6B7280' }}>Event ID:</span>
                <span style={{ fontFamily: 'monospace', color: '#111827' }}>{selectedLog.id}</span>

                <span style={{ color: '#6B7280' }}>Action:</span>
                <span>
                  <span className={`badge ${getActionBadgeClass(selectedLog.action)}`} style={{ fontSize: '11px' }}>
                    {selectedLog.action}
                  </span>
                </span>

                <span style={{ color: '#6B7280' }}>Timestamp:</span>
                <span style={{ color: '#111827' }}>{formatDate(selectedLog.createdAt)}</span>

                <span style={{ color: '#6B7280' }}>Actor User ID:</span>
                <span style={{ fontFamily: 'monospace', color: '#111827' }}>{selectedLog.actorUserId || 'System / Unauthenticated'}</span>

                <span style={{ color: '#6B7280' }}>Resource:</span>
                <span style={{ color: '#111827' }}>
                  {selectedLog.resourceType} {selectedLog.resourceId && `(${selectedLog.resourceId})`}
                </span>
              </div>

              <div style={{ fontSize: '13px', fontWeight: 600, color: '#374151', marginBottom: '8px' }}>
                Event Payload / Metadata:
              </div>
              <pre style={{
                backgroundColor: '#F3F4F6',
                padding: '12px',
                borderRadius: '6px',
                fontSize: '12px',
                overflowX: 'auto',
                color: '#1F2937',
                margin: 0
              }}>
                {JSON.stringify(selectedLog.details, null, 2)}
              </pre>

              <div style={{ marginTop: '20px', textAlign: 'right' }}>
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={() => setSelectedLog(null)}
                  style={{ padding: '6px 14px', fontSize: '13px' }}
                >
                  Close
                </button>
              </div>
            </div>
          </div>
        )}

        <ChangePasswordModal isOpen={passwordModalOpen} onClose={() => setPasswordModalOpen(false)} />
      </main>
    </div>
  );
};
