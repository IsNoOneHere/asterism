import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowLeft,
  ArrowRight,
  Check,
  CheckCircle2,
  Clock3,
  FileText,
  ListChecks,
  MessageSquare,
  Paperclip,
  Sparkles,
  Target,
  X,
} from 'lucide-react';
import { Link, useBlocker, useNavigate, useParams } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { api, ConversationMessage, PrdMessageStartResult, ProductAgentExecution } from '../api/client';
import { ActionConfirmDialog } from '../components/ActionConfirmDialog';
import { errorMessage, ErrorState, StatusBadge } from '../components/Display';
import { MarkdownContent } from '../components/MarkdownContent';
import { SystemSelect } from '../components/SystemSelect';
import { hasGeneratedWorkItem } from '../prd';
import { useCurrentSystem } from '../SystemContext';

const schema = z.object({
  systemId: z.string().min(1),
  content: z.string(),
});

type FormValue = z.infer<typeof schema>;
type DraftSummary = { title: string; goal: string; acceptanceCriteria: string[] };
type UploadStatus = 'uploading' | 'uploaded' | 'failed';
type UploadItem = {
  key: string;
  file: File;
  previewUrl: string;
  status: UploadStatus;
  attachmentId?: string;
};
type SendValue = FormValue & { attachmentIds: string[] };
type UserMessageLabel = '你的描述' | '你的回答' | '你的补充';
type OptimisticUser = { content: string; display: string; label: UserMessageLabel; question?: string };
type ClarificationItem = { question: string; suggestion?: string };
type PrdViewState = {
  status?: string;
  draft?: Record<string, unknown>;
  missingFields?: string[];
  workItemId?: string;
  lifecycleStatus?: string;
};

const fieldNames: Record<string, string> = {
  acceptanceCriteria: '验收标准',
  acceptance_criteria: '验收标准',
  title: '标题',
  goal: '目标',
};
const unsavedMessage = '内容尚未保存，是否离开？';
const steps = ['描述想法', '理解与澄清', '生成工作项', '执行准备'];
const maxMessageAttachments = 3;
const questionAnswerPattern = /^针对问题「(.+?)」的回答：\s*\n?([\s\S]*)$/;

export function NewPrdPage() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const { prdId: routePrdId } = useParams();
  const { systems, systemId, setSystemId } = useCurrentSystem();
  const [prdId, setPrdId] = useState<string | undefined>(routePrdId);
  const [conversationId, setConversationId] = useState<string>();
  const [result, setResult] = useState<PrdViewState>({ status: 'waiting_input' });
  const [startedExecution, setStartedExecution] = useState<PrdMessageStartResult>();
  const [uploads, setUploads] = useState<UploadItem[]>([]);
  const [dragActive, setDragActive] = useState(false);
  const [optimisticUser, setOptimisticUser] = useState<OptimisticUser>();
  const [draftSummary, setDraftSummary] = useState<DraftSummary>({ title: '', goal: '', acceptanceCriteria: [] });
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [previewImage, setPreviewImage] = useState<string>();
  const previewDialogRef = useRef<HTMLDialogElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const answerInputRef = useRef<HTMLTextAreaElement | null>(null);
  const handledExecution = useRef<string>();
  const loadedPrdId = useRef<string>();
  const allowNavigation = useRef(false);
  const uploadSequence = useRef(0);
  const uploadsRef = useRef<UploadItem[]>([]);
  const sessionPrdId = routePrdId || prdId;
  const draftSession = useQuery({
    queryKey: ['prd-session', sessionPrdId],
    queryFn: () => api.prdSession(sessionPrdId!),
    enabled: Boolean(sessionPrdId),
    retry: false,
  });
  const conversation = useQuery({
    queryKey: ['conversation', conversationId],
    queryFn: () => api.conversation(conversationId!),
    enabled: Boolean(conversationId),
    retry: false,
    refetchInterval: (query) => (
      isExecutionActive(query.state.data?.activeExecution?.status)
      || isExecutionActive(startedExecution?.status)
    ) ? 2_000 : false,
  });
  const form = useForm<FormValue>({
    resolver: zodResolver(schema),
    defaultValues: { systemId, content: '' },
  });
  const selectedSystemId = form.watch('systemId');
  const content = form.watch('content') ?? '';
  const hasUnsavedChanges = Boolean(content.trim() || uploads.length);
  const shouldBlock = useCallback(() => hasUnsavedChanges && !allowNavigation.current, [hasUnsavedChanges]);
  const blocker = useBlocker(shouldBlock);
  const readinessSystemId = draftSession.data?.systemId || selectedSystemId;
  const readiness = useQuery({
    queryKey: ['system-readiness', readinessSystemId],
    queryFn: () => api.systemReadiness(readinessSystemId),
    enabled: Boolean(readinessSystemId),
    retry: false,
  });
  const confirmable = ['waiting_user_confirm', 'case_start_failed'].includes(result.status || '');

  useEffect(() => {
    uploadsRef.current = uploads;
  }, [uploads]);

  useEffect(() => () => {
    uploadsRef.current.forEach((item) => revokePreview(item.previewUrl));
  }, []);

  useEffect(() => {
    if (!prdId && !routePrdId && systemId && selectedSystemId !== systemId) form.setValue('systemId', systemId);
  }, [form, prdId, routePrdId, selectedSystemId, systemId]);

  useEffect(() => {
    const session = draftSession.data;
    if (!session) return;
    const incomingDraft = editorValue(session.draft, session.title, session.goal);
    const firstLoad = loadedPrdId.current !== session.prdId;
    // 刷新草稿地址时恢复原始系统与对话，不使用当前全局系统覆盖历史数据。
    setSystemId(session.systemId);
    if (firstLoad) form.reset({ systemId: session.systemId, content: '' });
    setPrdId(session.prdId);
    setConversationId(session.conversationId);
    setResult({ status: session.status, draft: session.draft, missingFields: session.missingFields, workItemId: session.workItemId });
    setDraftSummary(incomingDraft);
    loadedPrdId.current = session.prdId;
  }, [draftSession.data, form, setSystemId]);

  useEffect(() => {
    allowNavigation.current = false;
  }, [routePrdId]);

  const send = useMutation({
    mutationFn: (value: SendValue) => api.sendPrdMessage(value.systemId, {
      prdId,
      content: value.content,
      attachmentIds: value.attachmentIds,
    }),
    onSuccess: (data) => {
      console.info('v5 workbench PRD execution 已创建', { prdId: data.prdId, executionId: data.executionId });
      form.resetField('content');
      clearUploads();
      if (!prdId) {
        allowNavigation.current = true;
        navigate('/work-items/new/' + data.prdId, { replace: true });
      }
      setPrdId(data.prdId);
      setConversationId(data.conversationId);
      setStartedExecution(data);
      queryClient.invalidateQueries({ queryKey: ['conversation', data.conversationId] });
      queryClient.invalidateQueries({ queryKey: ['prd-sessions'] });
    },
    onError: () => {
      setOptimisticUser(undefined);
      setStartedExecution(undefined);
    },
  });
  const confirm = useMutation({
    mutationFn: () => api.confirmPrd(prdId!),
    onSuccess: (data) => {
      setResult((current) => ({ ...current, ...data, status: 'confirmed' }));
      queryClient.invalidateQueries({ queryKey: ['prd-sessions'] });
    },
    onSettled: () => setConfirmOpen(false),
  });

  useEffect(() => {
    const data = conversation.data;
    if (!data) return;
    if (optimisticUser && data.messages.some((message) => message.senderType === 'user' && message.content === optimisticUser.content)) {
      setOptimisticUser(undefined);
    }
    const execution = data.latestExecution;
    if (!execution || !isExecutionTerminal(execution.status)) return;
    setStartedExecution((current) => current?.executionId === execution.executionId ? undefined : current);
    const marker = `${execution.executionId}:${execution.status}`;
    if (handledExecution.current === marker) return;
    handledExecution.current = marker;
    if (execution.status === 'COMPLETED') {
      if (prdId) queryClient.invalidateQueries({ queryKey: ['prd-session', prdId] });
      if (conversationId) queryClient.invalidateQueries({ queryKey: ['conversation', conversationId] });
    }
  }, [conversation.data, conversationId, optimisticUser, prdId, queryClient]);

  useEffect(() => {
    if (!hasUnsavedChanges) return;
    const beforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, [hasUnsavedChanges]);

  const projectedExecution = conversation.data?.activeExecution;
  const executionInFlight = send.isPending
    || isExecutionActive(projectedExecution?.status)
    || isExecutionActive(startedExecution?.status);
  const executionStage = projectedExecution?.stage || startedExecution?.status || 'CREATED';
  const terminalExecution = terminalFailure(conversation.data?.activeExecution, conversation.data?.latestExecution);
  const conversationMessages = conversation.data?.messages ?? [];
  const latestAssistant = [...conversationMessages].reverse().find((message) => message.senderType === 'assistant');
  const clarificationItems = useMemo(
    () => result.status === 'waiting_user_confirm'
      ? []
      : collectClarificationQuestions(latestAssistant),
    [latestAssistant, result.status],
  );
  const answeredQuestionKeys = useMemo(
    () => new Set(conversationMessages
      .filter((message) => message.senderType === 'user')
      .flatMap((message) => answeredQuestions(message.content))
      .map(questionKey)),
    [conversationMessages],
  );
  // 当前问题只来自最新一条 AI 回复；历史问题保留在对话记录中。
  const activeQuestion = clarificationItems.find((item) => !answeredQuestionKeys.has(questionKey(item.question)));
  const selectedQuestion = activeQuestion;
  const activeQuestionIndex = selectedQuestion
    ? clarificationItems.findIndex((item) => questionKey(item.question) === questionKey(selectedQuestion.question))
    : -1;
  const answeredQuestionCount = clarificationItems.filter((item) =>
    answeredQuestionKeys.has(questionKey(item.question))).length;
  const remainingQuestionCount = Math.max(0, clarificationItems.length - answeredQuestionCount);
  const confirmationAttachmentIds = useMemo(() => Array.from(new Set(
    conversationMessages.flatMap((message) => message.attachmentIds || []),
  )), [conversationMessages]);
  const workItemGenerated = hasGeneratedWorkItem(result);
  const uploadsBusy = uploads.some((item) => item.status === 'uploading');
  const uploadsFailed = uploads.some((item) => item.status === 'failed');
  const uploadedIds = uploads.flatMap((item) => item.attachmentId ? [item.attachmentId] : []);
  const activeStep = workItemGenerated ? 4 : confirmable ? 3 : prdId ? 2 : 1;
  const showQuestionWorkspace = Boolean(selectedQuestion);
  const contentField = form.register('content');

  function reset() {
    navigate('/work-items/new');
  }

  function confirmPrd() {
    confirm.reset();
    setConfirmOpen(true);
  }

  function previewUrl(file: File) {
    return typeof URL.createObjectURL === 'function' ? URL.createObjectURL(file) : '';
  }

  function clearUploads() {
    setUploads((current) => {
      current.forEach((item) => revokePreview(item.previewUrl));
      uploadsRef.current = [];
      return [];
    });
    if (fileInputRef.current) fileInputRef.current.value = '';
  }

  function removeUpload(key: string) {
    setUploads((current) => {
      const removed = current.find((item) => item.key === key);
      if (removed) revokePreview(removed.previewUrl);
      const next = current.filter((item) => item.key !== key);
      uploadsRef.current = next;
      return next;
    });
  }

  function upload(item: UploadItem) {
    api.uploadAttachment(selectedSystemId, item.file)
      .then((attachment) => setUploads((current) => current.map((value) =>
        value.key === item.key ? { ...value, status: 'uploaded', attachmentId: attachment.attachmentId } : value)))
      .catch(() => setUploads((current) => current.map((value) =>
        value.key === item.key ? { ...value, status: 'failed', attachmentId: undefined } : value)));
  }

  function addFiles(values: FileList | File[]) {
    if (!selectedSystemId) return;
    // 后端每条消息最多接收三张图片，粘贴、拖拽和文件选择统一遵守该契约。
    const remaining = Math.max(0, maxMessageAttachments - uploadsRef.current.length);
    const images = Array.from(values).filter((file) => file.type.startsWith('image/')).slice(0, remaining);
    const staged = images.map((file) => ({
      key: `upload-${Date.now()}-${uploadSequence.current++}`,
      file,
      previewUrl: previewUrl(file),
      status: 'uploading' as const,
    }));
    if (!staged.length) return;
    setUploads((current) => {
      const next = [...current, ...staged].slice(0, maxMessageAttachments);
      uploadsRef.current = next;
      return next;
    });
    staged.forEach(upload);
  }

  function retryUpload(item: UploadItem) {
    const retry = { ...item, status: 'uploading' as const };
    setUploads((current) => current.map((value) => value.key === item.key ? retry : value));
    upload(retry);
  }

  function openImagePreview(url: string) {
    if (!url) return;
    setPreviewImage(url);
    if (!previewDialogRef.current?.open) previewDialogRef.current?.showModal();
  }

  function submitMessage(value: FormValue) {
    const message = value.content.trim();
    if (!message && uploadedIds.length === 0) return;
    const answer = message || '见附件';
    const question = selectedQuestion?.question;
    const wireContent = question ? questionAnswerContent(question, answer) : message;
    const label: UserMessageLabel = selectedQuestion ? '你的回答' : prdId ? '你的补充' : '你的描述';
    setOptimisticUser({
      content: wireContent,
      display: message || `已发送 ${uploadedIds.length} 张图片`,
      label,
      question,
    });
    send.mutate({ ...value, content: wireContent, attachmentIds: uploadedIds });
  }

  function applySuggestion() {
    if (!selectedQuestion?.suggestion) return;
    // 推荐答案只进入输入框，必须由用户确认后才会发送并更新需求摘要。
    form.setValue('content', selectedQuestion.suggestion, { shouldDirty: true });
    answerInputRef.current?.focus();
  }

  function skipQuestion() {
    if (!selectedQuestion || !selectedSystemId || executionInFlight) return;
    const content = questionAnswerContent(selectedQuestion.question, '暂不回答');
    setOptimisticUser({
      content,
      display: '暂不回答',
      label: '你的回答',
      question: selectedQuestion.question,
    });
    send.mutate({ systemId: selectedSystemId, content, attachmentIds: uploadedIds });
  }

  const conversationTimeline = <section className="prd-conversation-timeline" aria-label="需求分析对话">
    {conversationMessages.length === 0 && !optimisticUser && !executionInFlight && <div className="prd-conversation-empty">
      <Sparkles size={18} aria-hidden="true" />
      <div>
        <h2>AI 需求分析</h2>
        <p>描述需求后，AI 会先准备完整问题，再陪你逐题确认。</p>
      </div>
    </div>}
    {conversationMessages.map((message, index) =>
      <ConversationMessageCard
        key={message.messageId}
        message={message}
        index={index}
        messages={conversationMessages}
        latestAssistantId={latestAssistant?.messageId}
        onPreview={openImagePreview}
      />)}
    {optimisticUser && <article className="prd-message user optimistic" role="status">
      <header>
        <MessageSquare size={17} aria-hidden="true" />
        <strong>{optimisticUser.label}</strong>
        {optimisticUser.question && <small>回答：{optimisticUser.question}</small>}
      </header>
      <p>{optimisticUser.display}</p>
    </article>}
    {executionInFlight && <ExecutionProgressBubble stage={executionStage} />}
  </section>;

  const composer = <form className={'prd-unified-composer' + (dragActive ? ' drag-active' : '')}
    onSubmit={form.handleSubmit(submitMessage)}
    onDragOver={(event) => {
      if (Array.from(event.dataTransfer.types).includes('Files')) {
        event.preventDefault();
        setDragActive(true);
      }
    }}
    onDragLeave={() => setDragActive(false)}
    onDrop={(event) => {
      const images = Array.from(event.dataTransfer.files).filter((file) => file.type.startsWith('image/'));
      if (images.length) {
        event.preventDefault();
        addFiles(images);
      }
      setDragActive(false);
    }}>
    {uploads.length > 0 && <div className="prd-upload-tray" aria-label={`已上传图片 ${uploads.length} 张`}>
      <span className="prd-upload-count">已选择 {uploads.length} 张</span>
      <div className="prd-upload-scroll">{uploads.map((item) => <div className={'prd-upload-tile ' + item.status} key={item.key}>
        <button type="button" className="prd-upload-preview" title={item.file.name}
          aria-label={item.status === 'failed' ? `重试上传 ${item.file.name}` : `预览 ${item.file.name}`}
          onClick={() => item.status === 'failed' ? retryUpload(item) : openImagePreview(item.previewUrl)}>
          {item.previewUrl ? <img src={item.previewUrl} alt="" /> : <span aria-hidden="true" />}
          <small>{shortFilename(item.file.name)}</small>
          <i aria-hidden="true">{item.status === 'uploaded' ? <Check size={10} /> : item.status === 'failed' ? '!' : ''}</i>
        </button>
        <button type="button" className="prd-upload-remove" aria-label={`删除图片 ${item.file.name}`}
          onClick={() => removeUpload(item.key)}><X size={11} /></button>
      </div>)}</div>
    </div>}
    <label className="prd-answer-label" htmlFor="prd-answer">
      {selectedQuestion ? '你的回答' : '需求描述'}
    </label>
    <textarea id="prd-answer" rows={selectedQuestion ? 5 : 4}
      aria-label={selectedQuestion ? '回答 AI 当前问题' : '需求描述'}
      placeholder={selectedQuestion ? '输入你的答案，或先采用 AI 建议再修改…' : prdId ? '继续补充需求或要求 AI 修改…' : '描述业务目标、现状和期望结果…'}
      disabled={executionInFlight}
      {...contentField}
      ref={(element) => {
        contentField.ref(element);
        answerInputRef.current = element;
      }}
      onPaste={(event) => {
        const images = Array.from(event.clipboardData.files).filter((file) => file.type.startsWith('image/'));
        if (images.length) addFiles(images);
      }} />
    <div className="prd-composer-footer">
      <div className="prd-composer-tools">
        <input ref={fileInputRef} type="file" accept="image/png,image/jpeg,image/webp" multiple hidden
          onChange={(event) => {
            if (event.target.files) addFiles(event.target.files);
            event.target.value = '';
          }} />
        <button type="button" className="prd-attachment-trigger" aria-label="选择图片"
          disabled={executionInFlight || uploads.length >= maxMessageAttachments}
          onClick={() => fileInputRef.current?.click()}><Paperclip size={18} /></button>
        <span>{uploadsFailed ? '部分图片上传失败，点击缩略图重试' : uploadsBusy ? '图片上传中…' : `本次最多 ${maxMessageAttachments} 张图片`}</span>
      </div>
      <div className="prd-answer-actions">
        {selectedQuestion && <button type="button" className="secondary"
          disabled={executionInFlight || uploadsBusy || uploadsFailed} onClick={skipQuestion}>暂不回答</button>}
        <button type="submit"
          disabled={executionInFlight || uploadsBusy || uploadsFailed || !selectedSystemId || (!content.trim() && uploadedIds.length === 0)}>
          {executionInFlight ? 'AI 分析中…' : selectedQuestion ? '回答并进入下一题' : prdId ? '继续分析' : '开始分析'}
        </button>
      </div>
    </div>
  </form>;

  return (
    <section className="create-workspace prd-create-page">
      <header className="prd-create-head">
        <Link className="prd-create-back" to={routePrdId ? '/work-items/drafts' : '/work-items'}>
          <ArrowLeft size={16} aria-hidden="true" />
          {routePrdId ? '返回需求草稿' : '返回工作项中心'}
        </Link>
        <div className="prd-create-title">
          <h1>{routePrdId ? '继续创建工作项' : '创建工作项'}</h1>
          <div className="prd-create-meta">
            {prdId && <span className="prd-draft-tag">草稿 {prdId}</span>}
            <StatusBadge value={result.lifecycleStatus || result.status || 'waiting_input'} />
          </div>
        </div>
        <ol className="prd-create-steps" aria-label="创建工作项进度">
          {steps.map((step, index) => <li className={index + 1 < activeStep ? 'completed' : index + 1 === activeStep ? 'active' : ''} key={step}
            aria-current={index + 1 === activeStep ? 'step' : undefined}>
            <span>{index + 1 < activeStep ? <Check size={14} /> : index + 1}</span>{step}
          </li>)}
        </ol>
      </header>

      {routePrdId && draftSession.isLoading ? <div className="panel">草稿加载中...</div> :
      routePrdId && draftSession.isError ? <ErrorState title="草稿加载失败" error={draftSession.error} onRetry={() => draftSession.refetch()} /> :
      workItemGenerated ? <section className="prd-created-completion" role="status" aria-labelledby="prd-created-title">
        <span className="prd-created-icon" aria-hidden="true"><CheckCircle2 size={38} /></span>
        <span className="prd-created-eyebrow">创建完成</span>
        <h2 id="prd-created-title">工作项创建成功</h2>
        <p>需求已确认，新的工作项已进入审批队列。</p>
        <div className="prd-created-status">
          <span><Clock3 size={16} aria-hidden="true" />当前状态</span>
          <StatusBadge value={result.lifecycleStatus || 'waiting_owner_approval'} />
          <span>审批通过后进入规划与执行 <ArrowRight size={16} aria-hidden="true" /></span>
        </div>
        <div className="prd-created-divider" aria-hidden="true" />
        <div className="prd-created-actions">
          <Link className="primary-action-link" to={'/work-items/' + result.workItemId}>查看工作项</Link>
          <button type="button" className="secondary" onClick={reset}>创建另一项</button>
        </div>
      </section> :
      <div className="prd-create-workspace">
        <div className="prd-system-field">
          <SystemSelect systems={systems} value={selectedSystemId} label="所属系统"
            disabled={Boolean(prdId) || uploads.length > 0}
            onChange={(value) => { setSystemId(value); form.setValue('systemId', value); }} />
        </div>
        {systems.length === 0 && <div className="notice">还没有可用系统，请先前往 <Link className="action-link" to="/systems">系统配置</Link> 创建系统。</div>}

        {conversation.error && <ErrorState title="对话加载失败" error={conversation.error} onRetry={() => conversation.refetch()} />}
        {terminalExecution && <ExecutionFailureNotice execution={terminalExecution} />}

        {clarificationItems.length > 0 && <section className="prd-analysis-progress" aria-label="需求问题进度">
          <div className="prd-analysis-progress-title">
            <span aria-hidden="true"><Sparkles size={20} /></span>
            <div>
              <strong>{remainingQuestionCount > 0 ? '需求分析中' : '问题已确认'}</strong>
              <small>问题 {activeQuestionIndex >= 0 ? activeQuestionIndex + 1 : clarificationItems.length}/{clarificationItems.length}</small>
            </div>
          </div>
          <div className="prd-analysis-progress-track" aria-hidden="true">
            <i style={{ width: `${Math.round((answeredQuestionCount / clarificationItems.length) * 100)}%` }} />
          </div>
          <p>
            已回答 {answeredQuestionCount} 项
            {activeQuestionIndex >= 0 && <> · <strong>当前第 {activeQuestionIndex + 1} 项</strong></>}
            {' '}· 剩余 {remainingQuestionCount} 项
          </p>
        </section>}

        {showQuestionWorkspace && selectedQuestion ? <>
          <div className="prd-analysis-layout">
            <section className="prd-current-question-card" aria-labelledby="prd-current-question">
              <header>
                <span>问题 {activeQuestionIndex + 1}/{clarificationItems.length}</span>
                <small>按实际情况确认，可修改 AI 建议</small>
              </header>
              <h2 id="prd-current-question">{selectedQuestion.question}</h2>
              {selectedQuestion.suggestion && <section className="prd-ai-suggestion" aria-label="AI 推荐答案">
                <span aria-hidden="true"><Sparkles size={18} /></span>
                <div>
                  <strong>AI 建议</strong>
                  <p>{selectedQuestion.suggestion}</p>
                  <small>仅供参考，采用后仍可继续修改，不会自动提交。</small>
                </div>
                <button type="button" className="secondary" onClick={applySuggestion}>采用建议</button>
              </section>}
              {composer}
            </section>

            <aside className="prd-live-summary" aria-labelledby="prd-live-summary-title">
              <header>
                <h2 id="prd-live-summary-title">需求摘要</h2>
                <span>随回答更新</span>
              </header>
              <dl>
                <div>
                  <dt><FileText size={17} aria-hidden="true" />需求</dt>
                  <dd>{draftSummary.title || '待 AI 提炼'}</dd>
                </div>
                <div>
                  <dt><Target size={17} aria-hidden="true" />目标</dt>
                  <dd>{draftSummary.goal || '待补充'}</dd>
                </div>
                <div>
                  <dt><ListChecks size={17} aria-hidden="true" />验收标准</dt>
                  <dd>{draftSummary.acceptanceCriteria.length > 0
                    ? `${draftSummary.acceptanceCriteria.length} 条`
                    : '待补充'}</dd>
                </div>
                <div>
                  <dt><Paperclip size={17} aria-hidden="true" />相关图片</dt>
                  <dd>{confirmationAttachmentIds.length} 张</dd>
                </div>
              </dl>
              <div className="prd-live-summary-note">
                <Sparkles size={17} aria-hidden="true" />
                <p>你的回答确认发送后，才会用于完善 PRD 与右侧摘要。</p>
              </div>
            </aside>
          </div>
          <details className="prd-conversation-history">
            <summary>查看完整沟通记录（{conversationMessages.length} 条）</summary>
            <div>{conversationTimeline}</div>
          </details>
        </> : <main className="prd-work-panel">{conversationTimeline}</main>}

        {confirmable && <section className="panel prd-confirmation-card" aria-labelledby="prd-confirmation-title">
          <header>
            <span className="prd-confirmation-icon" aria-hidden="true"><CheckCircle2 size={20} /></span>
            <div>
              <h2 id="prd-confirmation-title">需求确认</h2>
              <p>AI 已完成分析，请确认以下内容可以进入开发。</p>
            </div>
            <span className="prd-confirmation-ready">可确认</span>
          </header>

          <div className="prd-confirmation-content">
            <section>
              <h3>要解决的问题</h3>
              <p>{draftSummary.title || 'AI 尚未生成问题摘要'}</p>
            </section>
            <section>
              <h3>期望结果</h3>
              <p>{draftSummary.goal || 'AI 尚未生成期望结果'}</p>
            </section>
            <section className="prd-confirmation-wide">
              <h3>验收标准</h3>
              {draftSummary.acceptanceCriteria.length > 0
                ? <ul>{draftSummary.acceptanceCriteria.map((criterion, index) => <li key={`${criterion}-${index}`}>{criterion}</li>)}</ul>
                : <p>暂无验收标准</p>}
            </section>
            <section className="prd-confirmation-wide">
              <h3>相关图片</h3>
              {confirmationAttachmentIds.length > 0
                ? <div className="prd-confirmation-images">
                  {confirmationAttachmentIds.map((attachmentId, index) => <button type="button" key={attachmentId}
                    aria-label={`预览相关图片 ${index + 1}`} onClick={() => openImagePreview(api.attachmentUrl(attachmentId))}>
                    <img src={api.attachmentUrl(attachmentId)} alt="" />
                  </button>)}
                </div>
                : <p>暂无相关图片</p>}
            </section>
          </div>

          {readiness.isLoading && <div className="notice" role="status">正在检查系统执行条件…</div>}
          {readiness.isError && <ErrorState title="执行条件检查失败" error={readiness.error} onRetry={() => readiness.refetch()} />}
          {readiness.data && !readiness.data.ready && <div className="warning">
            <strong>系统尚未具备真实执行条件</strong>
            {readiness.data.issues?.length > 0 && <ul>{readiness.data.issues.map((issue) => <li key={issue.code}>{issue.message}</li>)}</ul>}
          </div>}
          <footer className="prd-confirmation-actions">
            <button type="button" onClick={confirmPrd}
              disabled={executionInFlight || !readiness.data?.ready || confirm.isPending}>
              确认并生成工作项
            </button>
          </footer>
        </section>}

        {!showQuestionWorkspace && composer}
        {send.error && <ErrorState title="消息发送失败" error={send.error} />}
        {Boolean(result.missingFields?.length) && <div className="warning prd-analysis-missing">
          AI 建议补充：{result.missingFields?.map((field) => fieldNames[field] || field).join('、')}
        </div>}
      </div>}

      <dialog ref={previewDialogRef} className="confirm-dialog image-preview-dialog" aria-label="图片预览" onClose={() => setPreviewImage(undefined)}>
        {previewImage && <img src={previewImage} alt="需求截图预览" />}
        <button type="button" className="secondary" onClick={() => previewDialogRef.current?.close()}>关闭预览</button>
      </dialog>
      <ActionConfirmDialog
        open={confirmOpen}
        title="确认并生成工作项？"
        description="确认后将创建工作项并启动真实执行流程。"
        confirmLabel="确认并生成"
        pending={confirm.isPending}
        tone="primary"
        onClose={() => setConfirmOpen(false)}
        onConfirm={() => confirm.mutate()}
      />
      <ActionConfirmDialog
        open={Boolean(confirm.error)}
        title="生成工作项失败"
        description={errorMessage(confirm.error, '工作项生成失败')}
        confirmLabel="知道了"
        alert
        showCancel={false}
        onClose={() => confirm.reset()}
        onConfirm={() => confirm.reset()}
      />
      <ActionConfirmDialog
        open={blocker.state === 'blocked'}
        title="离开当前页面？"
        description={unsavedMessage}
        confirmLabel="确认离开"
        tone="danger"
        onClose={() => blocker.state === 'blocked' && blocker.reset()}
        onConfirm={() => blocker.state === 'blocked' && blocker.proceed()}
      />
    </section>
  );
}

export function ExecutionProgressBubble({ stage }: { stage: string }) {
  return <article className="prd-message assistant pending" role="status" aria-live="polite">
    <header>
      <Sparkles size={17} aria-hidden="true" />
      <strong>AI 需求分析</strong>
    </header>
    <p>{executionStageText(stage)}</p>
  </article>;
}

function ExecutionFailureNotice({ execution }: { execution: ProductAgentExecution }) {
  const cancelled = execution.status === 'CANCELLED';
  return <div className="warning prd-execution-warning" role="alert">
    <strong>{cancelled ? 'AI 分析已取消' : 'AI 分析失败'}</strong>
    <p>{cancelled
      ? '本轮没有生成回复，你可以重新发送、补充文字或手工编辑草稿。'
      : '本轮没有修改草稿，你可以重试、继续补充文字或手工编辑草稿。'}</p>
  </div>;
}

function isExecutionActive(status?: string) {
  return status === 'CREATED' || status === 'RUNNING';
}

function isExecutionTerminal(status?: string) {
  return status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED';
}

function terminalFailure(active?: ProductAgentExecution | null, latest?: ProductAgentExecution | null) {
  if (active || (latest?.status !== 'FAILED' && latest?.status !== 'CANCELLED')) return undefined;
  return latest;
}

function executionStageText(stage: string) {
  const stages: Record<string, string> = {
    CREATED: '请求已进入队列，正在准备分析…',
    RETRIEVING_CONTEXT: '正在整理对话和业务上下文…',
    ANALYZING_REQUIREMENT: '正在分析需求与相关图片…',
    GENERATING_RESPONSE: '正在生成回复和需求草稿…',
    RUNNING: '正在理解你的回答并整理下一个问题…',
  };
  return stages[stage] || stages.RUNNING;
}

export function extractClarification(content: string) {
  if (!content.trim()) return { intro: '', questions: [] as string[], suggestions: {} as Record<string, string> };
  const lines = content.split(/\r?\n/);
  const questions: string[] = [];
  const intro: string[] = [];
  const suggestions: Record<string, string> = {};
  const pattern = /^\s*(?:(?:\d{1,2}[.、)）]|[-*])\s*)?(.+[？?](?:[（(].*[）)])?)(?:\*\*|__)?\s*$/;
  const suggestionPattern = /^\s*(?:推荐答案|建议答案|AI\s*建议|建议)[:：]\s*(.+)\s*$/i;
  let currentQuestion: string | undefined;
  for (const line of lines) {
    const match = line.match(pattern);
    const suggestion = line.match(suggestionPattern);
    if (match) {
      currentQuestion = cleanQuestion(match[1]);
      questions.push(currentQuestion);
    } else if (currentQuestion && suggestion) {
      suggestions[questionKey(currentQuestion)] = suggestion[1].trim();
    } else {
      intro.push(line);
    }
  }
  if (questions.length === 0) return { intro: content, questions: [] as string[], suggestions };
  return {
    intro: intro.join('\n').replace(/\n{3,}/g, '\n\n').trim(),
    questions: Array.from(new Set(questions)),
    suggestions,
  };
}

function ConversationMessageCard({
  message,
  index,
  messages,
  latestAssistantId,
  onPreview,
}: {
  message: ConversationMessage;
  index: number;
  messages: ConversationMessage[];
  latestAssistantId?: string;
  onPreview: (url: string) => void;
}) {
  const isUser = message.senderType === 'user';
  const answer = isUser ? parseQuestionAnswer(message.content) : undefined;
  const firstUserIndex = messages.findIndex((item) => item.senderType === 'user');
  const label: UserMessageLabel = answer ? '你的回答' : index === firstUserIndex ? '你的描述' : '你的补充';
  const clarification = isUser ? undefined : extractClarification(message.content);
  const body = isUser ? answer?.answer ?? message.content : clarification?.intro ?? message.content;
  const attachments = message.attachmentIds || [];

  return <article className={`prd-message ${isUser ? 'user' : 'assistant'}`}>
    <header>
      {isUser ? <MessageSquare size={17} aria-hidden="true" /> : <Sparkles size={17} aria-hidden="true" />}
      <strong>{isUser ? label : 'AI 需求分析'}</strong>
      {answer && <small>回答：{answer.question}</small>}
      {!isUser && clarification?.questions.length && message.messageId === latestAssistantId
        ? <small>已准备 {clarification.questions.length} 个问题，界面将逐题展示</small>
        : null}
    </header>
    {body.trim() && (isUser ? <p>{body}</p> : <MarkdownContent markdown={body} />)}
    {clarification?.questions.length ? <ol className="prd-message-question-list">
      {clarification.questions.map((question) => <li key={questionKey(question)}>
        <strong>{question}</strong>
        {clarification.suggestions[questionKey(question)] && <span>
          建议：{clarification.suggestions[questionKey(question)]}
        </span>}
      </li>)}
    </ol> : null}
    {attachments.length > 0 && <div className="prd-message-images">
      {attachments.map((attachmentId, attachmentIndex) => {
        const url = api.attachmentUrl(attachmentId);
        return <button type="button" key={`${attachmentId}-${attachmentIndex}`}
          aria-label={`预览消息图片 ${attachmentIndex + 1}`} onClick={() => onPreview(url)}>
          <img src={url} alt="" />
        </button>;
      })}
    </div>}
  </article>;
}

function collectClarificationQuestions(message?: ConversationMessage) {
  if (!message) return [];
  const clarification = extractClarification(message.content);
  return clarification.questions.map((question): ClarificationItem => ({
    question,
    suggestion: clarification.suggestions[questionKey(question)],
  }));
}

function answeredQuestions(content: string) {
  const answer = parseQuestionAnswer(content);
  return answer ? [answer.question] : [];
}

function parseQuestionAnswer(content: string) {
  const match = content.match(questionAnswerPattern);
  if (!match) return undefined;
  return { question: cleanQuestion(match[1]), answer: match[2].trim() };
}

function questionAnswerContent(question: string, answer: string) {
  return `针对问题「${cleanQuestion(question)}」的回答：\n${answer.trim()}`;
}

function questionKey(question: string) {
  return cleanQuestion(question).replace(/\s+/g, ' ').toLocaleLowerCase();
}

function cleanQuestion(question: string) {
  return question.trim().replace(/^(\*{1,2}|__)/, '').replace(/(\*{1,2}|__)$/, '').trim();
}

function shortFilename(filename: string) {
  const stem = filename.replace(/\.[^.]+$/, '');
  return stem.length > 5 ? stem.slice(0, 5) + '…' : stem;
}

function revokePreview(url: string) {
  if (url && typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(url);
}

function editorValue(draft: Record<string, unknown>, title?: string, goal?: string): DraftSummary {
  return {
    title: typeof draft.title === 'string' ? draft.title : title || '',
    goal: typeof draft.goal === 'string' ? draft.goal : goal || '',
    acceptanceCriteria: Array.isArray(draft.acceptanceCriteria) ? draft.acceptanceCriteria.map(String) : [],
  };
}
