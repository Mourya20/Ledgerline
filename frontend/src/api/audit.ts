import { apiClient } from './client';
import type { Page, AuditLogItem } from './types';

export interface GetAuditLogsParams {
  page?: number;
  size?: number;
  sort?: string;
}

export const auditApi = {
  getLogs: async (params: GetAuditLogsParams = {}): Promise<Page<AuditLogItem>> => {
    const query = new URLSearchParams();
    if (params.page !== undefined) query.set('page', params.page.toString());
    if (params.size !== undefined) query.set('size', params.size.toString());
    if (params.sort) query.set('sort', params.sort);

    const queryString = query.toString();
    const endpoint = queryString ? `/api/v1/audit/logs?${queryString}` : '/api/v1/audit/logs';
    return apiClient<Page<AuditLogItem>>(endpoint, { method: 'GET' });
  },
};
