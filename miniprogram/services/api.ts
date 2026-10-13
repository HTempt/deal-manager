import { request } from '../utils/request'

export const api = {
  home: () => request<any>('/api/home/overview'),
  dailyAdvice: (refresh = false) => request<{
    date: string; overview: string; candidateCount: number; updatedAt?: string; stale: boolean; aiGenerated: boolean;
    items: { customerId: number; customerName: string; priority: string; advice: string; stage?: string;
      budget?: number | null; intentionScore?: number | null; daysWithoutContact: number; hasContactHistory: boolean;
      planTime?: string | null; overdueDays: number }[]
  }>(`/api/home/daily-advice?refresh=${refresh}`, { method: 'POST', timeout: 90000 }),
  customers: (params = '') => request<any>(`/api/customers${params}`),
  customer: (id: string | number) => request<any>(`/api/customers/${id}`),
  customerFollows: (id: string | number) => request<any[]>(`/api/customers/${id}/follows`),
  customerSummary: (id: string | number, refresh = false) => request<{
    overview: string; suggestion: string; followCount: number; daysWithoutContact: number;
    hasContactHistory: boolean; stale: boolean; recordsUsed: number; updatedAt?: string; aiGenerated: boolean
  }>(`/api/customers/${id}/summary?refresh=${refresh}`, { method: 'POST', timeout: 90000 }),
  createCustomer: (data: unknown) => request<any>('/api/customers', { method: 'POST', data }),
  updateCustomer: (id: string | number, data: unknown) => request<any>(`/api/customers/${id}`, { method: 'PUT', data }),
  scheduleCustomer: (id: string | number, nextFollowTime: string) => request<any>(`/api/customers/${id}/next-follow`, { method: 'PUT', data: { nextFollowTime } }),
  deleteCustomer: (id: string | number) => request<void>(`/api/customers/${id}`, { method: 'DELETE' }),
  todayFollows: () => request<any[]>('/api/follows/today'),
  overdueFollows: () => request<any[]>('/api/follows/overdue'),
  upcomingFollows: () => request<any[]>('/api/follows/upcoming'),
  createFollow: (data: unknown) => request<any>('/api/follows', { method: 'POST', data }),
  createDeal: (data: unknown) => request<any>('/api/deals', { method: 'POST', data }),
  generateAi: (data: { customerId: number; scenario: string; action: 'GENERATE' | 'REGENERATE' | 'SHORTEN'; previousContent?: string }) => request<any>(`/api/ai/${data.action === 'GENERATE' ? 'generate' : 'regenerate'}-script`, { method: 'POST', data, timeout: 90000 }),
  dataOverview: (month: string) => request<any>(`/api/data/overview?month=${month}`),
  dataSource: (month: string) => request<any[]>(`/api/data/source?month=${month}`),
  dataFunnel: (month: string) => request<{
    month: string; cohortBasis: string; progressBasis: string;
    stages: { stage: string; customers: number | null }[]; lostCustomers: number | null;
    dealConversionPercent: number | null; quoteToDealPercent: number | null; inquiryToQuotePercent: number | null
  }>(`/api/data/funnel?month=${encodeURIComponent(month)}`),
  dataFollowEfficiency: (month: string) => request<{
    month: string; asOf: string; expectedTasks: number; completedTasks: number; pendingTasks: number;
    completionPercent: number | null; followRecords: number; followedCustomers: number;
    averageFollowCount: number | null; dealCycleCustomers: number; averageDealCycleDays: number | null
  }>(`/api/data/follow-efficiency?month=${encodeURIComponent(month)}`),
  dataSummary: (month: string, refresh = false) => request<{ content: string; aiGenerated: boolean; updatedAt?: string; stale: boolean }>(`/api/data/summary?month=${encodeURIComponent(month)}&refresh=${refresh}`, { method: 'POST', timeout: 90000 }),
  profile: () => request<any>('/api/user/profile'),
  industries: () => request<any>('/api/settings/industry'),
  saveIndustry: (industry: string) => request<any>('/api/settings/industry', { method: 'PUT', data: { industry } }),
  aiSetting: () => request<any>('/api/settings/ai'),
  saveAiSetting: (data: unknown) => request<any>('/api/settings/ai', { method: 'PUT', data }),
  subscribeNotification: (templateId: string, subscribeToken: string, expiresIn: number) => request<void>('/api/notifications/subscribe', { method: 'POST', data: { templateId, subscribeToken, expiresIn } }),
  unsubscribeNotification: () => request<void>('/api/notifications/subscribe', { method: 'DELETE' }),
  notificationSettings: () => request<{ enabled: boolean }>('/api/notifications/settings'),
  setNotificationEnabled: (enabled: boolean) => request<{ enabled: boolean }>('/api/notifications/settings', { method: 'PUT', data: { enabled } })
}
