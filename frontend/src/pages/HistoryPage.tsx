import React, { useEffect, useState, useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';
import { accountsApi } from '../api/accounts';
import { transactionsApi } from '../api/transactions';
import type { AccountResponse, Page, TransactionResponse } from '../api/types';
import { formatDate, maskAccountNumber, parseCustomerTransaction } from '../utils/formatters';
import { DownloadStatementModal } from '../components/DownloadStatementModal';

export const HistoryPage: React.FC = () => {
  const [searchParams, setSearchParams] = useSearchParams();
  const initialAccountId = searchParams.get('accountId') || '';

  const [accounts, setAccounts] = useState<AccountResponse[]>([]);
  const [selectedAccountId, setSelectedAccountId] = useState<string>(initialAccountId);
  const [statementModalOpen, setStatementModalOpen] = useState(false);
  const [pageData, setPageData] = useState<Page<TransactionResponse> | null>(null);
  const [currentPage, setCurrentPage] = useState<number>(0);
  const pageSize = 10;
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    async function loadAccounts() {
      try {
        const data = await accountsApi.getAccounts();
        setAccounts(data);
      } catch (err: unknown) {
        console.warn('Failed to load accounts for filter:', err);
      }
    }
    loadAccounts();
  }, []);

  const loadTransactions = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);

      const res = await transactionsApi.getTransactions({
        accountId: selectedAccountId || undefined,
        page: currentPage,
        size: pageSize,
        sort: 'createdAt,desc',
      });

      setPageData(res);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to load transaction history';
      setError(msg);
    } finally {
      setLoading(false);
    }
  }, [selectedAccountId, currentPage]);

  useEffect(() => {
    loadTransactions();
  }, [loadTransactions]);

  const handleAccountFilterChange = (accId: string) => {
    setSelectedAccountId(accId);
    setCurrentPage(0);
    if (accId) {
      setSearchParams({ accountId: accId });
    } else {
      setSearchParams({});
    }
  };

  const userAccountIds = new Set(accounts.map((a) => a.id));

  const totalElements = pageData?.totalElements || 0;
  const startItem = totalElements === 0 ? 0 : currentPage * pageSize + 1;
  const endItem = Math.min((currentPage + 1) * pageSize, totalElements);

  return (
    <div>
      <h1 style={{ fontSize: '26px', fontWeight: 700, color: '#111827', marginBottom: '24px' }}>
        Transaction history
      </h1>

      {error && (
        <div className="alert alert-danger" role="alert">
          {error}
        </div>
      )}

      {/* Filter Toolbar matching PDF layout */}
      <div style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: '16px',
        marginBottom: '20px',
        flexWrap: 'wrap'
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <label htmlFor="account-filter" style={{ fontSize: '13px', fontWeight: 500, color: '#4B5563' }}>
            Account
          </label>
          <select
            id="account-filter"
            className="form-select"
            style={{ width: '220px', padding: '8px 12px' }}
            value={selectedAccountId}
            onChange={(e) => handleAccountFilterChange(e.target.value)}
          >
            <option value="">All accounts</option>
            {accounts.map((acc) => (
              <option key={acc.id} value={acc.id}>
                Account {maskAccountNumber(acc.accountNumber)}
              </option>
            ))}
          </select>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <div style={{ fontSize: '12px', color: '#6B7280' }}>
            {selectedAccountId ? 'Filtered by selected account' : 'Showing all account transactions'}
          </div>
          {selectedAccountId && (
            <button
              type="button"
              className="btn btn-secondary"
              style={{ fontSize: '12px', padding: '6px 12px' }}
              onClick={() => setStatementModalOpen(true)}
            >
              Download Statement
            </button>
          )}
        </div>
      </div>

      {/* Transactions Table Card */}
      <div className="card" style={{ padding: '0px', overflow: 'hidden' }}>
        {loading ? (
          <div style={{ padding: '40px', textAlign: 'center', color: '#6B7280' }}>
            Loading transactions...
          </div>
        ) : !pageData || pageData.content.length === 0 ? (
          <div style={{ padding: '40px', textAlign: 'center', color: '#6B7280' }}>
            No transactions found for the selected filter.
          </div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left', fontSize: '14px' }}>
              <thead>
                <tr style={{ borderBottom: '1px solid #E5E7EB', backgroundColor: '#FAFAFA' }}>
                  <th style={{ padding: '14px 20px', color: '#6B7280', fontWeight: 500, fontSize: '13px' }}>Date</th>
                  <th style={{ padding: '14px 20px', color: '#6B7280', fontWeight: 500, fontSize: '13px' }}>Description</th>
                  <th style={{ padding: '14px 20px', color: '#6B7280', fontWeight: 500, fontSize: '13px' }}>Account</th>
                  <th style={{ padding: '14px 20px', color: '#6B7280', fontWeight: 500, fontSize: '13px' }}>Type</th>
                  <th style={{ padding: '14px 20px', color: '#6B7280', fontWeight: 500, fontSize: '13px', textAlign: 'right' }}>Amount</th>
                </tr>
              </thead>
              <tbody>
                {pageData.content.map((tx) => {
                  const parsed = parseCustomerTransaction(tx, userAccountIds, selectedAccountId);
                  return (
                    <tr key={tx.id} style={{ borderBottom: '1px solid #F3F4F6' }}>
                      <td style={{ padding: '16px 20px', color: '#4B5563', whiteSpace: 'nowrap' }}>
                        {formatDate(parsed.rawDate)}
                      </td>
                      <td style={{ padding: '16px 20px', fontWeight: 500, color: '#111827' }}>
                        {parsed.description}
                      </td>
                      <td style={{ padding: '16px 20px', color: '#4B5563' }}>
                        {parsed.accountLabel}
                      </td>
                      <td style={{ padding: '16px 20px' }}>
                        <span style={{
                          display: 'inline-block',
                          padding: '3px 9px',
                          borderRadius: '4px',
                          fontSize: '12px',
                          backgroundColor: '#F3F4F6',
                          color: '#374151'
                        }}>
                          {parsed.typeLabel}
                        </span>
                      </td>
                      <td style={{
                        padding: '16px 20px',
                        textAlign: 'right',
                        fontWeight: 600,
                        color: parsed.isPositive ? '#0D6E64' : '#111827',
                        whiteSpace: 'nowrap'
                      }}>
                        {parsed.formattedAmount}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        {/* Pagination Bar */}
        {pageData && pageData.totalElements > 0 && (
          <div style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            padding: '16px 20px',
            borderTop: '1px solid #E5E7EB',
            backgroundColor: '#FFFFFF',
            fontSize: '13px',
            color: '#6B7280'
          }}>
            <div>
              Showing {startItem}–{endItem} of {totalElements}
            </div>
            <div style={{ display: 'flex', gap: '8px' }}>
              <button
                type="button"
                className="btn-outline"
                style={{ padding: '6px 14px', fontSize: '13px' }}
                onClick={() => setCurrentPage((p) => Math.max(p - 1, 0))}
                disabled={pageData.first || loading}
              >
                Previous
              </button>
              <button
                type="button"
                className="btn-outline"
                style={{ padding: '6px 14px', fontSize: '13px' }}
                onClick={() => setCurrentPage((p) => p + 1)}
                disabled={pageData.last || loading}
              >
                Next
              </button>
            </div>
          </div>
        )}
      </div>
      <DownloadStatementModal
        isOpen={statementModalOpen}
        onClose={() => setStatementModalOpen(false)}
        account={accounts.find((a) => a.id === selectedAccountId) || null}
      />
    </div>
  );
};
