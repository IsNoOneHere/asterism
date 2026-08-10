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
export type ArtifactType = 'PRODUCT' | 'PLANNING' | 'CODING' | 'VALIDATION' | 'RELEASE';
export type ArtifactStatus = 'PROPOSED' | 'APPROVED' | 'REJECTED' | 'SUPERSEDED';
export type ArtifactRef = {
  artifactId: string;
  artifactType: ArtifactType;
  version: number;
  contentHash: string;
  rootArtifactId: string;
  parentArtifactId?: string | null;
  supersedesArtifactId?: string | null;
  status: ArtifactStatus;
};
export type ArtifactSummary = {
  ref: ArtifactRef;
  systemId: string;
  prdId: string;
  workItemId: string;
  caseId: string;
  content: Record<string, unknown>;
  createdBy: string;
  createdAt: string;
  reviewedBy?: string | null;
  reviewedAt?: string | null;
  reviewNote?: string | null;
};
export type ArtifactGraphEdge = {
  fromArtifactId: string;
  toArtifactId: string;
  edgeType: 'DERIVED_FROM' | 'SUPERSEDES';
};
export type ArtifactGraph = {
  rootArtifactId: string;
  nodes: ArtifactSummary[];
  edges: ArtifactGraphEdge[];
  effectiveHeads: Partial<Record<ArtifactType, ArtifactRef>>;
  versionActions: Record<string, ArtifactVersionAction>;
};
export type ArtifactVersionAction = {
  canSelect: boolean;
  selectDisabledReason?: string | null;
  canContinue: boolean;
  continueDisabledReason?: string | null;
};
export type ArtifactTransition = {
  transitionId: string;
  fromStatus?: ArtifactStatus | null;
  toStatus: ArtifactStatus;
  actor: string;
  note?: string | null;
  domainEventId: string;
  createdAt: string;
};
export type ArtifactEvidence = {
  evidenceId: string;
  evidenceType: string;
  payload: Record<string, unknown>;
  transitionId?: string | null;
  domainEventId: string;
  actor: string;
  createdAt: string;
};
export type ArtifactDetail = {
  artifact: ArtifactSummary;
  transitions: ArtifactTransition[];
  evidence: ArtifactEvidence[];
};
export type ArtifactVersionSelectionRequest = {
  requestId: string;
  artifact: ArtifactRef;
  expectedHeads: Partial<Record<ArtifactType, ArtifactRef>>;
};
export type ArtifactVersionSelectionResponse = {
  workItemId: string;
  signalId: string;
  status: string;
  effectiveHeads: Partial<Record<ArtifactType, ArtifactRef>>;
};
export type WorkItemActionRequest = {
  requestId: string;
  expectedStatus: string;
  expectedProjectionSequence: number;
  note?: string;
  evidence?: string;
  artifactRef?: ArtifactRef;
};
export type UserAccount = Schemas['UserAccountView'] & { userId: string; displayName: string; enabled: boolean };
export type MemoryType = 'FACT' | 'DECISION' | 'CONSTRAINT' | 'EXPERIENCE';
export type MemoryApplicability = 'PROJECT' | 'ARTIFACT_LINEAGE';
export type MemoryStatus = 'ACTIVE' | 'OUTDATED' | 'ARCHIVED';
export type MemoryCandidateStatus = 'PENDING' | 'CONFIRMED' | 'REJECTED' | 'OUTDATED';
export type MemoryArtifactSource = {
  artifactId: string;
  artifactType: ArtifactType;
  version: number;
  status: ArtifactStatus;
  workItemId?: string | null;
  prdId?: string | null;
  rootArtifactId: string;
};
export type MemoryDraft = {
  memoryType: MemoryType;
  title: string;
  content: string;
  confidence: number;
  applicability: MemoryApplicability;
  expiresAt?: string | null;
  targetRefs: string[];
};
export type MemoryCandidate = {
  candidateId: string;
  systemId: string;
  projectScope: string;
  memoryType: MemoryType;
  artifactSourceId?: string | null;
  artifactSource?: MemoryArtifactSource | null;
  sourceKind: 'ARTIFACT_APPROVED' | 'CODING_COMPLETED' | 'VALIDATION_FAILED' | string;
  title: string;
  content: string;
  confidence: number;
  applicability: MemoryApplicability;
  expiresAt?: string | null;
  status: MemoryCandidateStatus;
  targetRefs: string[];
  evidenceRefs: string[];
  sourceEventId?: string | null;
  createdBy?: string;
  reviewedBy?: string | null;
  reviewNote?: string | null;
  memoryId?: string | null;
  createdAt?: string;
  reviewedAt?: string | null;
};
export type MemoryItem = {
  memoryId: string;
  candidateId?: string | null;
  systemId: string;
  projectScope: string;
  memoryType: MemoryType;
  artifactSourceId?: string | null;
  artifactSource?: MemoryArtifactSource | null;
  title: string;
  content: string;
  confidence: number;
  applicability: MemoryApplicability;
  expiresAt?: string | null;
  status: MemoryStatus;
  targetRefs: string[];
  evidenceRefs: string[];
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
  usedContextRefs: string[];
  citations: Record<string, string[]>;
  contextItems: ContextItem[];
  createdAt?: string;
};
export type ContextItem = {
  refId: string;
  type: 'memory' | 'system_knowledge' | 'user_message' | string;
  audience: 'product' | 'execution' | 'both' | string;
  title: string;
  content: string;
  targetRefs: string[];
  sourceRef?: string;
  contentHash: string;
  relevance: number;
};
export type Conversation = {
  messages: ConversationMessage[];
  activeExecution?: ProductAgentExecution | null;
  latestExecution?: ProductAgentExecution | null;
};
export type ProductAgentExecutionStatus = 'CREATED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type ProductAgentExecution = {
  executionId: string;
  prdId: string;
  status: ProductAgentExecutionStatus;
  workflowId: string;
  inputMessageId: string;
  contextBundleId: string;
  stage: string;
  attempt: number;
  failureCode?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  lastHeartbeat?: string | null;
  resultMessageId?: string | null;
  createdAt?: string;
  updatedAt?: string;
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
  anchorTexts?: string[];
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
// 详情页仅消费附件展示字段，接口不暴露系统和存储信息。
export type WorkItemAttachment = Omit<Attachment, 'systemId'>;
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
export type PrdMessageStartResult = {
  executionId: string;
  prdId: string;
  conversationId: string;
  status: ProductAgentExecutionStatus;
};

export type ConfirmResponse = {
  prdId: string;
  workItemId: string;
  caseId: string;
  lifecycleStatus: string;
  requirementManifestId?: string;
  productArtifactId?: string;
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
  supportsVision?: boolean;
  imageInput: boolean;
  structuredOutput: 'json_schema' | 'json_object' | 'prompt_only';
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
  checkedAt?: string;
  code?: string;
};

export type ModelCapabilityTestResult = {
  supported: boolean;
  message: string;
  checkedAt?: string;
  code?: string;
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
  testModelProfileCapability: (systemId: string, profileId: string, capability: 'structured_output' | 'image_input') =>
    request<ModelCapabilityTestResult>('/api/v5/systems/' + encodeURIComponent(systemId) + '/model-profiles/' + encodeURIComponent(profileId) + '/capability-test?capability=' + capability, { method: 'POST' }),
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
    request<PrdMessageStartResult>('/api/v5/systems/' + systemId + '/prd/messages', { method: 'POST', headers: jsonHeaders, body: JSON.stringify(body) }),
  confirmPrd: (prdId: string) =>
    request<ConfirmResponse>('/api/v5/prd-sessions/' + prdId + '/confirm', { method: 'POST' }),
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
  workItemAttachments: (workItemId: string) => request<WorkItemAttachment[]>('/api/v5/work-items/' + encodeURIComponent(workItemId) + '/attachments'),
  deleteWorkItem: (workItemId: string) => requestVoid('/api/v5/work-items/' + encodeURIComponent(workItemId), { method: 'DELETE' }),
  // 后端主线程会补该接口；前端先固定预期契约。
  workItemEvents: (workItemId: string) => request<WorkItemEvent[]>('/api/v5/work-items/' + encodeURIComponent(workItemId) + '/events'),
  workItemArtifacts: (workItemId: string) => request<ArtifactGraph>('/api/v5/work-items/' + encodeURIComponent(workItemId) + '/artifacts'),
  artifactDetail: (artifactId: string) => request<ArtifactDetail>('/api/v5/artifacts/' + encodeURIComponent(artifactId)),
  selectArtifactVersion: (workItemId: string, body: ArtifactVersionSelectionRequest) =>
    request<ArtifactVersionSelectionResponse>('/api/v5/work-items/' + encodeURIComponent(workItemId) + '/artifacts/active', {
      method: 'POST', headers: jsonHeaders, body: JSON.stringify(body),
    }),
  continueWithArtifact: (workItemId: string, body: ArtifactVersionSelectionRequest) =>
    request<ArtifactVersionSelectionResponse>('/api/v5/work-items/' + encodeURIComponent(workItemId) + '/artifacts/continue', {
      method: 'POST', headers: jsonHeaders, body: JSON.stringify(body),
    }),
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
  memories: (systemId: string, status: MemoryStatus) =>
    request<MemoryItem[]>('/api/v5/memory?systemId=' + encodeURIComponent(systemId) + '&status=' + encodeURIComponent(status)),
  memoryCandidates: (systemId: string, status: MemoryCandidateStatus) =>
    request<MemoryCandidate[]>('/api/v5/memory/candidates?systemId=' + encodeURIComponent(systemId) + '&status=' + encodeURIComponent(status)),
  approveMemory: (candidateId: string, draft: MemoryDraft) =>
    request<MemoryItem>('/api/v5/memory/candidates/' + encodeURIComponent(candidateId) + '/approve', {
      method: 'POST', headers: jsonHeaders, body: JSON.stringify(draft),
    }),
  rejectMemory: (candidateId: string, note?: string) =>
    request<MemoryCandidate>('/api/v5/memory/candidates/' + encodeURIComponent(candidateId) + '/reject', {
      method: 'POST', headers: jsonHeaders, body: JSON.stringify({ note: note || '' }),
    }),
  archiveMemory: (memoryId: string) => request<MemoryItem>('/api/v5/memory/' + encodeURIComponent(memoryId) + '/archive', {
    method: 'POST',
  }),
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
