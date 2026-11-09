import { apiClient } from './client';
import type { HealthResponse } from './types';

export const healthApi = {
  getHealth: async (): Promise<HealthResponse> => {
    return apiClient<HealthResponse>('/actuator/health', { method: 'GET' });
  },
};
