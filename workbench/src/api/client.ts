import type { components } from './generated';

type Schemas = components['schemas'];

export type CurrentUser = Schemas['CurrentUser'] & { userId: string; roles: string[] };
export type SystemProfile = Schemas['SystemProfile'] & {
  systemId: string;
  name: string;
  description?: string;
  repoPath: string;
  ownerUserId: string;
  allowedPaths?: string;
  forbiddenPaths?: string;
  testCommands?: string;
  agentConfig?: string;
  modelProviderConfig?: string;
};
export type WorkItem = Schemas['WorkItemProjection'] & {
  workItemId: string;
  systemId: string;
  prdId: string;
  caseId: string;
  lifecycleStatus: string;
  approvalStatus: string;
  executionAllowed: boolean;
  currentStage: string;
  waitingFor: string;
  canControl: boolean;
  availableActions: string[];
  createdAt?: string;
  updatedAt?: string;
};
export type WorkItemEvent = Schemas['DomainEventRecord'] & {
  sequence: number;
  eventId: string;
  eventType: string;
};
export type UserAccount = Schemas['UserAccountView'] & { userId: string; displayName: string; enabled: boolean };
export type ContextSnapshot = Schemas['ContextSnapshot'] & { manifestId?: string | null };
export type MemoryItem = Schemas['MemoryItem'] & {
  memoryId: string;
  systemId: string;
  content: string;
  status: string;
  sourceEventId?: string | null;
  createdBy?: string;
  createdAt?: string;
  approvedBy?: string | null;
};
export type SystemMember = {
  systemId: string;
  userId: string;
  displayName?: string | null;
  role: string;
  createdAt?: string;
};
export type ConversationMessage = {
  messageId: string;
  conversationId: string;
  systemId: string;
  prdId: string;
  senderType: 'user' | 'assistant' | string;
  content: string;
  createdAt?: string;
};
export type PrdMessageResult = {
  prdId?: string;
  conversationId?: string;
  status?: string;
  assistantMessage?: string;
  missingFields?: string[];
  draft?: Record<string, unknown>;
  workItemId?: string;
  lifecycleStatus?: string;
};

export type PrdSession = {
  prdId: string;
  systemId: string;
  conversationId: string;
  workItemId?: string;
  caseId?: string;
  title?: string;
  goal?: string;
  draft: Record<string, unknown>;
  missingFields: string[];
  status: string;
  createdBy: string;
  creatorDisplayName?: string;
  createdAt?: string;
  updatedAt?: string;
};

export type SystemReadiness = {
  systemId: string;
  ready: boolean;
  checkedAt?: string;
  effectiveExecutionProvider: string;
  stages: Array<{ name: string; ready: boolean; detail: string }>;
  issues: Array<{ code: string; severity: 'error' | 'warning'; message: string }>;
};

export type ModelProfile = {
  id: string;
  name: string;
  provider: 'anthropic' | 'openai-compat' | string;
  baseUrl: string;
  model: string;
  apiKeySet: boolean;
};

export type ModelRouting = {
  defaultProfileId: string;
  prdProfileId: string;
  planningProfileId: string;
  diffProfileId: string;
};

export type AgentRole = {
  id: string;
  name: string;
  engine: 'claude_sdk' | 'deepagents' | 'http' | 'fake' | string;
  modelProfileRef: string;
  pathScope: string[];
  prompt: string;
  maxTurns?: number;
  timeoutSeconds?: number;
};

export type AgentConfiguration = {
  modelProfiles: ModelProfile[];
  modelRouting: ModelRouting;
  agentRoles: AgentRole[];
  defaultRoleId: string;
  executionMode: 'single' | 'planner_select';
  engines: string[];
};

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public details?: unknown) {
    super(message);
  }
}

const jsonHeaders = { 'Content-Type': 'application/json' };

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  // 统一携带 session cookie，空响应直接返回 undefined。
  const response = await fetch(path, { credentials: 'include', ...init });
  if (!response.ok) {
    const error = await readError(response);
    if (response.status === 401) {
      window.dispatchEvent(new Event('v5:auth-expired'));
    }
    throw error;
  }
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

async function requestVoid(path: string, init?: RequestInit): Promise<void> {
  await request<unknown>(path, init);
}

async function readError(response: Response) {
  const body = await response.text();
  try {
    const parsed = JSON.parse(body) as { code?: string; message?: string; detail?: string; details?: unknown };
    return new ApiError(response.status, parsed.code || 'REQUEST_FAILED', parsed.message || parsed.detail || '请求失败', parsed.details);
  } catch {
    return new ApiError(response.status, 'REQUEST_FAILED', body || `请求失败（${response.status}）`);
  }
}

export const api = {
  me: () => request<CurrentUser>('/api/v5/auth/me'),
  login: (username: string, password: string) =>
    requestVoid('/api/v5/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ username, password }),
    }),
  logout: () => requestVoid('/logout', { method: 'POST' }),
  systems: () => request<SystemProfile[]>('/api/v5/systems'),
  createSystem: (body: unknown) =>
    request<SystemProfile>('/api/v5/systems', { method: 'POST', headers: jsonHeaders, body: JSON.stringify(body) }),
  updateSystem: (systemId: string, body: unknown) =>
    request<SystemProfile>('/api/v5/systems/' + encodeURIComponent(systemId), { method: 'PUT', headers: jsonHeaders, body: JSON.stringify(body) }),
  updateSystemProfile: (systemId: string, body: unknown) =>
    request<SystemProfile>('/api/v5/systems/' + encodeURIComponent(systemId) + '/profile', { method: 'PATCH', headers: jsonHeaders, body: JSON.stringify(body) }),
  updateExecutionConfig: (systemId: string, body: unknown) =>
    request<SystemProfile>('/api/v5/systems/' + encodeURIComponent(systemId) + '/execution-config', { method: 'PATCH', headers: jsonHeaders, body: JSON.stringify(body) }),
  agentConfiguration: (systemId: string) =>
    request<AgentConfiguration>('/api/v5/systems/' + encodeURIComponent(systemId) + '/agent-config'),
  createModelProfile: (systemId: string, body: unknown) =>
    request<AgentConfiguration>('/api/v5/systems/' + encodeURIComponent(systemId) + '/model-profiles', {
      method: 'POST', headers: jsonHeaders, body: JSON.stringify(body),
    }),
  updateModelProfile: (systemId: string, profileId: string, body: unknown) =>
    request<AgentConfiguration>('/api/v5/systems/' + encodeURIComponent(systemId) + '/model-profiles/' + encodeURIComponent(profileId), {
      method: 'PATCH', headers: jsonHeaders, body: JSON.stringify(body),
    }),
  deleteModelProfile: (systemId: string, profileId: string) =>
    request<AgentConfiguration>('/api/v5/systems/' + encodeURIComponent(systemId) + '/model-profiles/' + encodeURIComponent(profileId), { method: 'DELETE' }),
  createAgentRole: (systemId: string, body: unknown) =>
    request<AgentConfiguration>('/api/v5/systems/' + encodeURIComponent(systemId) + '/agent-roles', {
      method: 'POST', headers: jsonHeaders, body: JSON.stringify(body),
    }),
  updateAgentRole: (systemId: string, roleId: string, body: unknown) =>
    request<AgentConfiguration>('/api/v5/systems/' + encodeURIComponent(systemId) + '/agent-roles/' + encodeURIComponent(roleId), {
      method: 'PATCH', headers: jsonHeaders, body: JSON.stringify(body),
    }),
  deleteAgentRole: (systemId: string, roleId: string) =>
    request<AgentConfiguration>('/api/v5/systems/' + encodeURIComponent(systemId) + '/agent-roles/' + encodeURIComponent(roleId), { method: 'DELETE' }),
  updateDefaultAgentRole: (systemId: string, roleId: string) =>
    request<AgentConfiguration>('/api/v5/systems/' + encodeURIComponent(systemId) + '/default-agent-role', {
      method: 'PATCH', headers: jsonHeaders, body: JSON.stringify({ roleId }),
    }),
  updateModelRouting: (systemId: string, body: ModelRouting) =>
    request<AgentConfiguration>('/api/v5/systems/' + encodeURIComponent(systemId) + '/model-routing', {
      method: 'PATCH', headers: jsonHeaders, body: JSON.stringify(body),
    }),
  updateExecutionPolicy: (systemId: string, mode: 'single' | 'planner_select', defaultRoleId: string) =>
    request<AgentConfiguration>('/api/v5/systems/' + encodeURIComponent(systemId) + '/execution-policy', {
      method: 'PATCH', headers: jsonHeaders, body: JSON.stringify({ mode, defaultRoleId }),
    }),
  systemReadiness: (systemId: string) => request<SystemReadiness>('/api/v5/systems/' + encodeURIComponent(systemId) + '/readiness'),
  sendPrdMessage: (systemId: string, body: { prdId?: string; content: string }) =>
    request<PrdMessageResult>('/api/v5/systems/' + systemId + '/prd/messages', { method: 'POST', headers: jsonHeaders, body: JSON.stringify(body) }),
  confirmPrd: (prdId: string) =>
    request<PrdMessageResult>('/api/v5/prd-sessions/' + prdId + '/confirm', { method: 'POST' }),
  conversation: (conversationId: string) =>
    request<ConversationMessage[]>('/api/v5/conversations/' + encodeURIComponent(conversationId)),
  prdSessions: (systemId: string) => request<PrdSession[]>('/api/v5/prd-sessions?systemId=' + encodeURIComponent(systemId)),
  prdSession: (prdId: string) => request<PrdSession>('/api/v5/prd-sessions/' + encodeURIComponent(prdId)),
  workItems: (options: { systemId?: string; scope?: string; status?: string; q?: string; sort?: string } | string) => {
    const value = typeof options === 'string' ? { systemId: options, scope: 'system' } : options;
    const params = new URLSearchParams();
    Object.entries(value).forEach(([key, item]) => item && params.set(key, item));
    return request<WorkItem[]>('/api/v5/work-items?' + params.toString());
  },
  workItem: (workItemId: string) => request<WorkItem>('/api/v5/work-items/' + encodeURIComponent(workItemId)),
  // 后端主线程会补该接口；前端先固定预期契约。
  workItemEvents: (workItemId: string) => request<WorkItemEvent[]>('/api/v5/work-items/' + encodeURIComponent(workItemId) + '/events'),
  approveOwner: (workItemId: string) =>
    request('/api/v5/work-items/' + workItemId + '/owner-approval', { method: 'POST' }),
  submitSignal: (workItemId: string, signalName: string) =>
    request('/api/v5/work-items/' + workItemId + '/signals/' + signalName, { method: 'POST' }),
  users: () => request<UserAccount[]>('/api/v5/users'),
  upsertUser: (body: { userId: string; displayName: string; email?: string; password?: string }) =>
    request<UserAccount>('/api/v5/users', { method: 'POST', headers: jsonHeaders, body: JSON.stringify(body) }),
  disableUser: (userId: string) => requestVoid('/api/v5/users/' + encodeURIComponent(userId) + '/disable', { method: 'POST' }),
  resetPassword: (userId: string, password: string) =>
    requestVoid('/api/v5/users/' + encodeURIComponent(userId) + '/reset-password', {
      method: 'POST',
      headers: jsonHeaders,
      body: JSON.stringify({ password }),
    }),
  upsertMembership: (body: { systemId: string; userId: string; role: string }) =>
    requestVoid('/api/v5/users/memberships', { method: 'POST', headers: jsonHeaders, body: JSON.stringify(body) }),
  members: (systemId: string) => request<SystemMember[]>('/api/v5/systems/' + encodeURIComponent(systemId) + '/members'),
  deleteMember: (systemId: string, userId: string, role: string) =>
    requestVoid('/api/v5/systems/' + encodeURIComponent(systemId) + '/members/' + encodeURIComponent(userId) + '/' + encodeURIComponent(role), {
      method: 'DELETE',
    }),
  memories: (systemId: string, status: string) =>
    request<MemoryItem[]>('/api/v5/memory?systemId=' + encodeURIComponent(systemId) + '&status=' + encodeURIComponent(status)),
  createMemory: (body: unknown) =>
    request('/api/v5/memory/candidates', { method: 'POST', headers: jsonHeaders, body: JSON.stringify(body) }),
  approveMemory: (memoryId: string) => request<MemoryItem>('/api/v5/memory/' + encodeURIComponent(memoryId) + '/approve', { method: 'POST' }),
  rejectMemory: (memoryId: string) => request<MemoryItem>('/api/v5/memory/' + encodeURIComponent(memoryId) + '/reject', { method: 'POST' }),
  disableMemory: (memoryId: string) => request<MemoryItem>('/api/v5/memory/' + encodeURIComponent(memoryId) + '/disable', { method: 'POST' }),
  contextSnapshot: (systemId: string) =>
    request<ContextSnapshot>('/api/v5/context-snapshots?systemId=' + encodeURIComponent(systemId)),
};
