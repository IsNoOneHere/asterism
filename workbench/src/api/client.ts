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
export type RepoConfig = {
  repoId: string;
  name: string;
  kind: 'frontend' | 'backend' | 'other';
  gitlabProject: string;
  defaultBranch: string;
  cloneMode: 'gitlab' | 'local';
  localPath: string;
  allowedPaths: string[];
  forbiddenPaths: string[];
  testCommands: string[];
};
export type GitConfiguration = {
  repos: RepoConfig[];
  releaseMode: 'local' | 'gitlab';
  validationMode: 'auto' | 'manual' | 'skip';
  mrTargetBranch: string;
  mrLabels: string[];
  gitlabBaseUrl: string;
  effectiveGitlabBaseUrl: string;
  tokenSet: boolean;
  usingGlobalToken: boolean;
};
export type WorkItem = Schemas['WorkItemView'] & {
  workItemId: string;
  systemId: string;
  prdId: string;
  caseId: string;
  lifecycleStatus: string;
  approvalStatus: string;
  executionAllowed: boolean;
  currentStage: string;
  waitingFor: string;
  canDelete: boolean;
  canControl: boolean;
  availableActions: string[];
  lastAppliedSequence: number;
  pendingAction?: { action: string; signalId: string; submittedAt?: string } | null;
  releaseMode?: 'local' | 'gitlab' | '';
  validationMode?: 'auto' | 'manual' | 'skip' | '';
  createdAt?: string;
  updatedAt?: string;
  targets?: SuspectedTarget[];
};
export type WorkItemEvent = Schemas['DomainEventRecord'] & {
  sequence: number;
  eventId: string;
  eventType: string;
};
export type WorkItemActionRequest = {
  requestId: string;
  expectedStatus: string;
  expectedProjectionSequence: number;
  note?: string;
  evidence?: string;
};
export type UserAccount = Schemas['UserAccountView'] & { userId: string; displayName: string; enabled: boolean };
export type ContextSnapshot = Schemas['ContextSnapshot'] & { manifestId?: string | null };
export type MemoryCategory = 'constraint' | 'convention' | 'lesson';
export type MemoryDraft = {
  category: MemoryCategory;
  title: string;
  content: string;
};
export type MemoryItem = Schemas['MemoryView'] & {
  memoryId: string;
  systemId: string;
  category: MemoryCategory | '';
  title: string;
  content: string;
  status: string;
  workItemId?: string | null;
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
  attachmentIds: string[];
  observations: UiObservation[];
  createdAt?: string;
};
export type Conversation = {
  messages: ConversationMessage[];
  pendingAssistant: boolean;
  pendingSince?: string;
};
export type UiObservation = {
  page_title?: string;
  pageTitle?: string;
  text_anchors?: string[];
  textAnchors?: string[];
  ui_elements?: string[];
  uiElements?: string[];
  error_messages?: string[];
  errorMessages?: string[];
  user_visible_summary?: string;
  userVisibleSummary?: string;
};
export type SuspectedTarget = {
  entryId: string;
  repo?: string;
  kind: string;
  title: string;
  routePath: string;
  apiEndpoints: string[];
  codeRefs: string[];
  confidence: number;
};
export type Attachment = {
  attachmentId: string;
  systemId: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
};
export type KnowledgeEntry = {
  entryId: string;
  systemId: string;
  repo: string;
  kind: 'route' | 'page' | 'api' | string;
  title: string;
  anchorTexts: string[];
  routePath: string;
  apiEndpoints: string[];
  codeRefs: string[];
  status: string;
  source: string;
  sourceRef: string;
  createdAt?: string;
};
export type KnowledgePageResult = {
  items: KnowledgeEntry[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
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
  assistantPending?: boolean;
};

export type PrdDraftUpdate = {
  title?: string;
  goal?: string;
  draft: Record<string, unknown>;
  missingFields: string[];
  status: string;
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
  canDelete: boolean;
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
  supportsVision: boolean;
};

export type Agent = {
  name: string;
  kind: 'builtin';
  engine: 'claude_sdk_team' | 'fake' | '';
  modelProfileRef: string;
  pathScope: string[];
  prompt: string;
  maxTurns?: number;
  timeoutSeconds?: number;
};

export type AgentConfiguration = {
  modelProfiles: ModelProfile[];
  agents: Agent[];
  maxRevisions: number;
  engines: string[];
  migration?: { migrated: boolean; from: string[] };
};

export type ModelConnectionTestResult = {
  connected: boolean;
  message: string;
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
  deleteSystem: (systemId: string) =>
    requestVoid('/api/v5/systems/' + encodeURIComponent(systemId), { method: 'DELETE' }),
  updateSystem: (systemId: string, body: unknown) =>
    request<SystemProfile>('/api/v5/systems/' + encodeURIComponent(systemId), { method: 'PUT', headers: jsonHeaders, body: JSON.stringify(body) }),
  updateSystemProfile: (systemId: string, body: unknown) =>
    request<SystemProfile>('/api/v5/systems/' + encodeURIComponent(systemId) + '/profile', { method: 'PATCH', headers: jsonHeaders, body: JSON.stringify(body) }),
  gitConfiguration: (systemId: string) =>
    request<GitConfiguration>('/api/v5/systems/' + encodeURIComponent(systemId) + '/git-config'),
  updateGitConfiguration: (systemId: string, body: unknown) =>
    request<GitConfiguration>('/api/v5/systems/' + encodeURIComponent(systemId) + '/git-config', {
      method: 'PUT', headers: jsonHeaders, body: JSON.stringify(body),
    }),
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
  testModelProfileConnection: (systemId: string, profileId: string) =>
    request<ModelConnectionTestResult>('/api/v5/systems/' + encodeURIComponent(systemId) + '/model-profiles/' + encodeURIComponent(profileId) + '/connection-test', { method: 'POST' }),
  updateAgent: (systemId: string, agentName: string, body: unknown) =>
    request<AgentConfiguration>('/api/v5/systems/' + encodeURIComponent(systemId) + '/agents/' + encodeURIComponent(agentName), {
      method: 'PATCH', headers: jsonHeaders, body: JSON.stringify(body),
    }),
  updateExecutionSettings: (systemId: string, body: { maxRevisions: number }) =>
    request<AgentConfiguration>('/api/v5/systems/' + encodeURIComponent(systemId) + '/agent-config/settings', {
      method: 'PATCH', headers: jsonHeaders, body: JSON.stringify(body),
    }),
  systemReadiness: (systemId: string) => request<SystemReadiness>('/api/v5/systems/' + encodeURIComponent(systemId) + '/readiness'),
  uploadAttachment: (systemId: string, file: File) => {
    const body = new FormData();
    body.append('systemId', systemId);
    body.append('file', file);
    return request<Attachment>('/api/v5/attachments', { method: 'POST', body });
  },
  attachmentUrl: (attachmentId: string) => '/api/v5/attachments/' + encodeURIComponent(attachmentId),
  sendPrdMessage: (systemId: string, body: { prdId?: string; content: string; attachmentIds?: string[] }) =>
    request<PrdMessageResult>('/api/v5/systems/' + systemId + '/prd/messages', { method: 'POST', headers: jsonHeaders, body: JSON.stringify(body) }),
  confirmPrdTargets: (prdId: string, entryIds: string[], accepted = true) =>
    request<{ draft: Record<string, unknown> }>('/api/v5/prd-sessions/' + encodeURIComponent(prdId) + '/targets/confirm', {
      method: 'POST', headers: jsonHeaders, body: JSON.stringify({ entryIds, accepted }),
    }),
  updatePrdDraft: (prdId: string, body: { title: string; goal: string; acceptanceCriteria: string[] }) =>
    request<PrdDraftUpdate>('/api/v5/prd-sessions/' + encodeURIComponent(prdId) + '/draft', {
      method: 'PATCH', headers: jsonHeaders, body: JSON.stringify(body),
    }),
  confirmPrd: (prdId: string) =>
    request<PrdMessageResult>('/api/v5/prd-sessions/' + prdId + '/confirm', { method: 'POST' }),
  conversation: (conversationId: string) =>
    request<Conversation>('/api/v5/conversations/' + encodeURIComponent(conversationId)),
  prdSessions: (systemId: string) => request<PrdSession[]>('/api/v5/prd-sessions?systemId=' + encodeURIComponent(systemId)),
  prdSession: (prdId: string) => request<PrdSession>('/api/v5/prd-sessions/' + encodeURIComponent(prdId)),
  deletePrdDraft: (prdId: string) => requestVoid('/api/v5/prd-sessions/' + encodeURIComponent(prdId), { method: 'DELETE' }),
  workItems: (options: { systemId?: string; scope?: string; status?: string; q?: string; sort?: string } | string) => {
    const value = typeof options === 'string' ? { systemId: options, scope: 'system' } : options;
    const params = new URLSearchParams();
    Object.entries(value).forEach(([key, item]) => item && params.set(key, item));
    return request<WorkItem[]>('/api/v5/work-items?' + params.toString());
  },
  workItem: (workItemId: string) => request<WorkItem>('/api/v5/work-items/' + encodeURIComponent(workItemId)),
  deleteWorkItem: (workItemId: string) => requestVoid('/api/v5/work-items/' + encodeURIComponent(workItemId), { method: 'DELETE' }),
  // 后端主线程会补该接口；前端先固定预期契约。
  workItemEvents: (workItemId: string) => request<WorkItemEvent[]>('/api/v5/work-items/' + encodeURIComponent(workItemId) + '/events'),
  approveOwner: (workItemId: string, body: WorkItemActionRequest) =>
    request('/api/v5/work-items/' + workItemId + '/owner-approval', { method: 'POST', headers: jsonHeaders, body: JSON.stringify(body) }),
  submitSignal: (workItemId: string, signalName: string, body: WorkItemActionRequest) =>
    request('/api/v5/work-items/' + workItemId + '/signals/' + signalName, { method: 'POST', headers: jsonHeaders, body: JSON.stringify(body) }),
  checkMergeStatus: (workItemId: string, body: WorkItemActionRequest) =>
    request('/api/v5/work-items/' + workItemId + '/merge-status/check', { method: 'POST', headers: jsonHeaders, body: JSON.stringify(body) }),
  users: () => request<UserAccount[]>('/api/v5/users'),
  upsertUser: (body: { userId: string; displayName: string; email?: string; password?: string }) =>
    request<UserAccount>('/api/v5/users', { method: 'POST', headers: jsonHeaders, body: JSON.stringify(body) }),
  deleteUser: (userId: string) => requestVoid('/api/v5/users/' + encodeURIComponent(userId), { method: 'DELETE' }),
  disableUser: (userId: string) => requestVoid('/api/v5/users/' + encodeURIComponent(userId) + '/disable', { method: 'POST' }),
  enableUser: (userId: string) => requestVoid('/api/v5/users/' + encodeURIComponent(userId) + '/enable', { method: 'POST' }),
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
  createMemory: (body: MemoryDraft & { systemId: string; workItemId?: string }) =>
    request<MemoryItem>('/api/v5/memory/candidates', { method: 'POST', headers: jsonHeaders, body: JSON.stringify(body) }),
  approveMemory: (memoryId: string, draft?: MemoryDraft) => request<MemoryItem>('/api/v5/memory/' + encodeURIComponent(memoryId) + '/approve', {
    method: 'POST', ...(draft ? { headers: jsonHeaders, body: JSON.stringify(draft) } : {}),
  }),
  rejectMemory: (memoryId: string) => request<MemoryItem>('/api/v5/memory/' + encodeURIComponent(memoryId) + '/reject', { method: 'POST' }),
  disableMemory: (memoryId: string) => request<MemoryItem>('/api/v5/memory/' + encodeURIComponent(memoryId) + '/disable', { method: 'POST' }),
  contextSnapshot: (systemId: string) =>
    request<ContextSnapshot>('/api/v5/context-snapshots?systemId=' + encodeURIComponent(systemId)),
  knowledge: (systemId: string, status: string) =>
    request<KnowledgeEntry[]>('/api/v5/systems/' + encodeURIComponent(systemId) + '/knowledge?status=' + encodeURIComponent(status)),
  knowledgePage: (systemId: string, status: string, page: number, query: string) =>
    request<KnowledgePageResult>('/api/v5/systems/' + encodeURIComponent(systemId) + '/knowledge/page?' + new URLSearchParams({
      status, page: String(page), pageSize: '10', query,
    })),
  createKnowledge: (systemId: string, body: unknown) =>
    request<KnowledgeEntry>('/api/v5/systems/' + encodeURIComponent(systemId) + '/knowledge', {
      method: 'POST', headers: jsonHeaders, body: JSON.stringify(body),
    }),
  updateKnowledgeStatus: (systemId: string, entryId: string, status: string) =>
    request<KnowledgeEntry>('/api/v5/systems/' + encodeURIComponent(systemId) + '/knowledge/' + encodeURIComponent(entryId) + '/status', {
      method: 'PATCH', headers: jsonHeaders, body: JSON.stringify({ status }),
    }),
  runRouteIndex: (systemId: string) =>
    request<{ workflowId: string }>('/api/v5/systems/' + encodeURIComponent(systemId) + '/knowledge/route-index', { method: 'POST' }),
};
