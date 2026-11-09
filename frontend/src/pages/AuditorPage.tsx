import React, { useEffect, useState, useCallback } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { auditApi } from '../api/audit';
import type { Page, AuditLogItem } from '../api/types';
import { formatDate } from '../utils/formatters';
import { PortalSwitcher } from '../components/PortalSwitcher';

export const AuditorPage: React.FC = () => {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const [pageData, setPageData] = useState<Page<AuditLogItem> | null>(null);
  const [currentPage, setCurrentPage] = useState<number>(0);
  const [pageSize, setPageSize] = useState<number>(15);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedLog, setSelectedLog] = useState<AuditLogItem | null>(null);

  const fetchLogs = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);
      const res = await auditApi.getLogs({
        page: currentPage,
        size: pageSize,
        sort: 'createdAt,desc',
      });
      setPageData(res);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to retrieve audit trail.';
      setError(msg);
    } finally {
      setLoading(false);
    }
  }, [currentPage, pageSize]);

  useEffect(() => {
    fetchLogs();
  }, [fetchLogs]);

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  const getActionBadgeClass = (action: string): string => {
    if (action.includes('SUCCESS') || action.includes('CREATED')) return 'badge-green';
    if (action.includes('FAILURE') || action.includes('EXCEEDED') || action.includes('DISABLED')) return 'badge-red';
    if (action.includes('TRANSACTION')) return 'badge-blue';
    return 'badge-gray';
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
            LedgerLine
          </Link>
          <span style={{
            fontSize: '11px',
            fontWeight: 700,
            textTransform: 'uppercase',
            letterSpacing: '0.05em',
            padding: '3px 8px',
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
          {user && (
            <span style={{ fontSize: '13px', color: '#4B5563' }}>
              {user.email}
            </span>
          )}
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
              System Audit Trail
            </h1>
            <p style={{ fontSize: '14px', color: '#6B7280', marginTop: '6px', margin: 0 }}>
              Immutable, read-only record of authentication attempts, security exceptions, and financial events.
            </p>
          </div>

          <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
            <button
              type="button"
              className="btn btn-secondary"
              onClick={fetchLogs}
              disabled={loading}
              style={{ fontSize: '13px', padding: '7px 14px' }}
            >
              {loading ? 'Refreshing...' : 'Refresh'}
            </button>
          </div>
        </div>

        {/* Error Notification */}
        {error && (
          <div style={{
            backgroundColor: '#FEF2F2',
            border: '1px solid #FCA5A5',
            color: '#991B1B',
            borderRadius: '8px',
            padding: '16px',
            marginBottom: '20px',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center'
          }}>
            <span>{error}</span>
            <button
              type="button"
              onClick={fetchLogs}
              style={{ background: 'none', border: 'none', color: '#991B1B', textDecoration: 'underline', cursor: 'pointer', fontWeight: 600 }}
            >
              Retry
            </button>
          </div>
        )}

        {/* Audit Log Table Card */}
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
              {pageData ? `Showing ${pageData.numberOfElements} of ${pageData.totalElements} recorded events` : 'Loading events...'}
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '13px', color: '#6B7280' }}>
              <span>Page size:</span>
              <select
                value={pageSize}
                onChange={(e) => {
                  setPageSize(Number(e.target.value));
                  setCurrentPage(0);
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
                  <th style={{ width: '180px' }}>Timestamp (UTC)</th>
                  <th style={{ width: '190px' }}>Action</th>
                  <th style={{ width: '120px' }}>Resource</th>
                  <th>Actor / Subject</th>
                  <th>Client IP</th>
                  <th style={{ width: '90px', textAlign: 'right' }}>Details</th>
                </tr>
              </thead>
              <tbody>
                {loading && (!pageData || pageData.content.length === 0) ? (
                  <tr>
                    <td colSpan={6} style={{ textAlign: 'center', padding: '48px 16px', color: '#6B7280' }}>
                      Loading audit records...
                    </td>
                  </tr>
                ) : !pageData || pageData.content.length === 0 ? (
                  <tr>
                    <td colSpan={6} style={{ textAlign: 'center', padding: '48px 16px', color: '#6B7280' }}>
                      No audit events recorded in this time range.
                    </td>
                  </tr>
                ) : (
                  pageData.content.map((item) => {
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
                          {item.resourceId && (
                            <div style={{ fontSize: '11px', color: '#9CA3AF', fontFamily: 'monospace' }}>
                              {item.resourceId.substring(0, 8)}...
                            </div>
                          )}
                        </td>
                        <td style={{ fontSize: '13px', color: '#111827' }}>
                          {email ? (
                            <div>
                              <div>{email}</div>
                              {item.actorUserId && (
                                <div style={{ fontSize: '11px', color: '#9CA3AF', fontFamily: 'monospace' }}>
                                  Actor: {item.actorUserId.substring(0, 8)}...
                                </div>
                              )}
                            </div>
                          ) : item.actorUserId ? (
                            <span style={{ fontFamily: 'monospace', fontSize: '12px' }}>
                              {item.actorUserId}
                            </span>
                          ) : (
                            <span style={{ color: '#9CA3AF', fontStyle: 'italic' }}>System / Anonymous</span>
                          )}
                        </td>
                        <td style={{ fontSize: '12px', color: '#4B5563', fontFamily: 'monospace' }}>
                          {clientIp}
                        </td>
                        <td style={{ textAlign: 'right' }}>
                          <button
                            type="button"
                            onClick={() => setSelectedLog(item)}
                            className="btn btn-secondary"
                            style={{ padding: '4px 8px', fontSize: '11px' }}
                            title="Inspect metadata"
                          >
                            Inspect
                          </button>
                        </td>
                      </tr>
                    );
                  })
                )}
              </tbody>
            </table>
          </div>

          {/* Pagination Controls */}
          {pageData && pageData.totalPages > 1 && (
            <div style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              padding: '12px 20px',
              borderTop: '1px solid #E5E7EB',
              backgroundColor: '#FAFAFA'
            }}>
              <span style={{ fontSize: '13px', color: '#6B7280' }}>
                Page {pageData.number + 1} of {pageData.totalPages}
              </span>
              <div style={{ display: 'flex', gap: '8px' }}>
                <button
                  type="button"
                  className="btn btn-secondary"
                  disabled={pageData.first || loading}
                  onClick={() => setCurrentPage((p) => Math.max(0, p - 1))}
                  style={{ padding: '6px 12px', fontSize: '13px' }}
                >
                  Previous
                </button>
                <button
                  type="button"
                  className="btn btn-secondary"
                  disabled={pageData.last || loading}
                  onClick={() => setCurrentPage((p) => p + 1)}
                  style={{ padding: '6px 12px', fontSize: '13px' }}
                >
                  Next
                </button>
              </div>
            </div>
          )}
        </div>
      </main>

      {/* Metadata Detail Modal */}
      {selectedLog && (
        <div style={{
          position: 'fixed',
          top: 0,
          left: 0,
          right: 0,
          bottom: 0,
          backgroundColor: 'rgba(0, 0, 0, 0.5)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          zIndex: 50,
          padding: '16px'
        }}>
          <div className="card" style={{ maxWidth: '640px', width: '100%', maxHeight: '85vh', display: 'flex', flexDirection: 'column' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
              <div>
                <h3 style={{ fontSize: '16px', fontWeight: 600, color: '#111827', margin: 0 }}>
                  Event Details: {selectedLog.action}
                </h3>
                <span style={{ fontSize: '12px', color: '#6B7280' }}>
                  ID: {selectedLog.id}
                </span>
              </div>
              <button
                type="button"
                onClick={() => setSelectedLog(null)}
                style={{ background: 'none', border: 'none', fontSize: '18px', cursor: 'pointer', color: '#6B7280' }}
              >
                ✕
              </button>
            </div>

            <div style={{ flex: 1, overflowY: 'auto', marginBottom: '16px' }}>
              <div style={{ fontSize: '12px', fontWeight: 600, color: '#4B5563', marginBottom: '6px' }}>
                Summary
              </div>
              <div style={{ backgroundColor: '#F9FAFB', padding: '12px', borderRadius: '6px', fontSize: '13px', marginBottom: '16px' }}>
                <div><strong>Action:</strong> {selectedLog.action}</div>
                <div><strong>Timestamp:</strong> {selectedLog.createdAt}</div>
                <div><strong>Resource Type:</strong> {selectedLog.resourceType || '—'}</div>
                <div><strong>Resource ID:</strong> {selectedLog.resourceId || '—'}</div>
                <div><strong>Actor User ID:</strong> {selectedLog.actorUserId || '—'}</div>
              </div>

              <div style={{ fontSize: '12px', fontWeight: 600, color: '#4B5563', marginBottom: '6px' }}>
                Metadata (JSON)
              </div>
              <pre style={{
                backgroundColor: '#1E293B',
                color: '#E2E8F0',
                padding: '12px',
                borderRadius: '6px',
                fontSize: '12px',
                overflowX: 'auto',
                margin: 0
              }}>
                {JSON.stringify(selectedLog.details, null, 2)}
              </pre>
            </div>

            <div style={{ textAlign: 'right' }}>
              <button
                type="button"
                className="btn btn-secondary"
                onClick={() => setSelectedLog(null)}
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
