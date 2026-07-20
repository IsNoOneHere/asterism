import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ArrowLeft, CheckCircle2, Plus, Trash2 } from 'lucide-react';
import { Link, useBlocker, useNavigate, useParams } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { api, PrdMessageResult, SuspectedTarget, UiObservation } from '../api/client';
import { ActionConfirmDialog } from '../components/ActionConfirmDialog';
import { errorMessage, ErrorState, StatusBadge } from '../components/Display';
import { SystemSelect } from '../components/SystemSelect';
import { hasGeneratedWorkItem, isResumablePrd } from '../prd';
import { useCurrentSystem } from '../SystemContext';

const schema = z.object({
  systemId: z.string().min(1),
  content: z.string(),
});

type FormValue = z.infer<typeof schema>;
type DraftEditorValue = { title: string; goal: string; acceptanceCriteria: string[] };

const fieldNames: Record<string, string> = {
  acceptanceCriteria: '验收标准',
  acceptance_criteria: '验收标准',
  title: '标题',
  goal: '目标',
};
const unsavedMessage = '内容尚未保存，是否离开？';

export function NewPrdPage() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const { prdId: routePrdId } = useParams();
  const { systems, systemId, setSystemId } = useCurrentSystem();
  const [prdId, setPrdId] = useState<string | undefined>(routePrdId);
  const [conversationId, setConversationId] = useState<string>();
  const [result, setResult] = useState<PrdMessageResult>({ status: 'waiting_input' });
  const [files, setFiles] = useState<File[]>([]);
  const [optimisticUser, setOptimisticUser] = useState<{ content: string; display: string }>();
  const [draftEditor, setDraftEditor] = useState<DraftEditorValue>({ title: '', goal: '', acceptanceCriteria: [] });
  const [savedDraftEditor, setSavedDraftEditor] = useState<DraftEditorValue>({ title: '', goal: '', acceptanceCriteria: [] });
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [previewImage, setPreviewImage] = useState<string>();
  const previewDialogRef = useRef<HTMLDialogElement>(null);
  const completedAssistant = useRef<string>();
  const loadedPrdId = useRef<string>();
  const allowNavigation = useRef(false);
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
    refetchInterval: (query) => query.state.data?.pendingAssistant ? 2_000 : false,
  });
  const form = useForm<FormValue>({
    resolver: zodResolver(schema),
    defaultValues: { systemId, content: '' },
  });
  const selectedSystemId = form.watch('systemId');
  const content = form.watch('content') ?? '';
  const draftDirty = Boolean(prdId) && JSON.stringify(draftEditor) !== JSON.stringify(savedDraftEditor);
  const hasUnsavedChanges = Boolean(content.trim() || files.length || draftDirty);
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
    if (!prdId && !routePrdId && systemId && selectedSystemId !== systemId) form.setValue('systemId', systemId);
  }, [form, prdId, routePrdId, selectedSystemId, systemId]);

  useEffect(() => {
    const session = draftSession.data;
    if (!session) return;
    const incomingDraft = editorValue(session.draft, session.title, session.goal);
    const firstLoad = loadedPrdId.current !== session.prdId;
    // 刷新编辑地址时，恢复草稿原始系统和对话。
    setSystemId(session.systemId);
    if (firstLoad) form.reset({ systemId: session.systemId, content: '' });
    setPrdId(session.prdId);
    setConversationId(session.conversationId);
    setResult({ status: session.status, draft: session.draft, missingFields: session.missingFields, workItemId: session.workItemId });
    setDraftEditor((current) => firstLoad || JSON.stringify(current) === JSON.stringify(savedDraftEditor) ? incomingDraft : current);
    setSavedDraftEditor((current) => JSON.stringify(current) === JSON.stringify(incomingDraft) ? current : incomingDraft);
    loadedPrdId.current = session.prdId;
  }, [draftSession.data, form, savedDraftEditor, setSystemId]);

  useEffect(() => {
    allowNavigation.current = false;
  }, [routePrdId]);

  const send = useMutation({
    mutationFn: async (value: FormValue) => {
      const uploaded = await Promise.all(files.map((file) => api.uploadAttachment(value.systemId, file)));
      return api.sendPrdMessage(value.systemId, { prdId, content: value.content, attachmentIds: uploaded.map((item) => item.attachmentId) });
    },
    onSuccess: (data) => {
      console.info('v5 workbench PRD 对话发送成功', { prdId: data.prdId });
      if (data.draft) {
        const nextDraft = editorValue(data.draft);
        setDraftEditor(nextDraft);
        setSavedDraftEditor(nextDraft);
      }
      form.resetField('content');
      setFiles([]);
      if (!prdId && data.prdId) {
        // 首次发送后的地址替换是保存流程的一部分，不触发离页确认。
        allowNavigation.current = true;
        navigate('/work-items/new/' + data.prdId, { replace: true });
      }
      setPrdId(data.prdId);
      setConversationId(data.conversationId);
      setResult(data);
      queryClient.invalidateQueries({ queryKey: ['conversation', data.conversationId] });
      queryClient.invalidateQueries({ queryKey: ['prd-sessions'] });
    },
    onError: () => setOptimisticUser(undefined),
  });
  const confirmTarget = useMutation({
    mutationFn: ({ entryId, accepted }: { entryId: string; accepted: boolean }) =>
      api.confirmPrdTargets(prdId!, [entryId], accepted),
    onSuccess: (data) => {
      setResult((current) => ({ ...current, draft: data.draft }));
      queryClient.invalidateQueries({ queryKey: ['conversation', conversationId] });
      queryClient.invalidateQueries({ queryKey: ['prd-session', prdId] });
    },
  });
  const saveDraft = useMutation({
    mutationFn: () => api.updatePrdDraft(prdId!, draftEditor),
    onSuccess: (data) => {
      const nextDraft = editorValue(data.draft, data.title, data.goal);
      setResult((current) => ({ ...current, status: data.status, draft: data.draft, missingFields: data.missingFields }));
      setDraftEditor(nextDraft);
      setSavedDraftEditor(nextDraft);
      queryClient.invalidateQueries({ queryKey: ['conversation', conversationId] });
      queryClient.invalidateQueries({ queryKey: ['prd-session', prdId] });
      queryClient.invalidateQueries({ queryKey: ['prd-sessions'] });
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
    const latestAssistant = [...data.messages].reverse().find((message) => message.senderType === 'assistant');
    if (!data.pendingAssistant && latestAssistant && completedAssistant.current !== latestAssistant.messageId) {
      completedAssistant.current = latestAssistant.messageId;
      setResult((current) => ({ ...current, assistantPending: false }));
      if (prdId) queryClient.invalidateQueries({ queryKey: ['prd-session', prdId] });
    }
  }, [conversation.data, optimisticUser, prdId, queryClient]);

  useEffect(() => {
    if (!hasUnsavedChanges) return;
    const beforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, [hasUnsavedChanges]);

  function reset() {
    navigate('/work-items/new');
  }

  function confirmPrd() {
    confirm.reset();
    setConfirmOpen(true);
  }

  function addFiles(values: FileList | File[]) {
    setFiles((current) => [...current, ...Array.from(values).filter((file) => file.type.startsWith('image/'))].slice(0, 3));
  }

  // 原生 dialog 已提供遮罩、焦点约束和 Esc 关闭，不引入额外图片预览依赖。
  function openImagePreview(attachmentId: string) {
    setPreviewImage(api.attachmentUrl(attachmentId));
    if (!previewDialogRef.current?.open) previewDialogRef.current?.showModal();
  }

  const suspectedTargets = targetList(result.draft?.suspectedTargets);
  const confirmedTargets = targetList(result.draft?.targets);
  const unconfirmedTargets = suspectedTargets.filter((target) =>
    !confirmedTargets.some((confirmed) => confirmed.entryId === target.entryId));
  const pendingAssistant = send.isPending || Boolean(result.assistantPending) || Boolean(conversation.data?.pendingAssistant);
  const conversationMessages = conversation.data?.messages ?? [];
  const latestAssistantId = [...conversationMessages].reverse()
    .find((message) => message.senderType === 'assistant')?.messageId;
  const acceptanceMissing = result.missingFields?.some((field) => ['acceptanceCriteria', 'acceptance_criteria'].includes(field));
  const draftEditable = isResumablePrd(result);
  const workItemGenerated = hasGeneratedWorkItem(result);

  return (
    <section className="create-workspace">
      <header className="page-head create-workspace-head">
        <div>
          <Link className="secondary-action-link" to={routePrdId ? '/work-items/drafts' : '/work-items'}>
            <ArrowLeft size={16} aria-hidden="true" />
            {routePrdId ? '返回需求草稿' : '返回工作项中心'}
          </Link>
          <h1>{routePrdId ? '继续创建工作项' : '创建工作项'}</h1>
          <p>通过 AI 沟通明确目标和验收标准，确认后生成工作项。</p>
        </div>
      </header>
      {routePrdId && draftSession.isLoading ? <div className="panel">草稿加载中...</div> :
      routePrdId && draftSession.isError ? <ErrorState title="草稿加载失败" error={draftSession.error} onRetry={() => draftSession.refetch()} /> :
      <div className="split wide-left create-workspace-grid">
      <div className="panel chat-panel">
        <h2>AI 需求沟通</h2>
        {systems.length === 0 && <div className="notice">还没有可用系统，请先前往 <Link className="action-link" to="/systems">系统配置</Link> 创建系统。</div>}
        <SystemSelect systems={systems} value={selectedSystemId} label="所属系统" disabled={Boolean(prdId)} onChange={(value) => { setSystemId(value); form.setValue('systemId', value); }} />
        <div className="message-list">
          {conversationMessages.map((message) => (
            <div className={'bubble ' + (message.senderType === 'user' ? 'user' : 'assistant')} key={message.messageId}>
              {message.content && <div>{message.content}</div>}
              {message.attachmentIds?.length > 0 && <div className="message-images">{message.attachmentIds.map((id) => <img key={id} src={api.attachmentUrl(id)} alt="需求截图" role="button" tabIndex={0} title="双击预览"
                onDoubleClick={() => openImagePreview(id)} onKeyDown={(event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); openImagePreview(id); } }} />)}</div>}
              {message.observations?.map((observation, index) => <small className="observation-summary" key={index}>{observationSummary(observation)}</small>)}
              {message.messageId === latestAssistantId && unconfirmedTargets.length > 0 && (
                <div className="target-confirmation-cards">
                  {unconfirmedTargets.map((target) => (
                    <div className="target-confirmation-card" key={target.entryId}>
                      <strong>{target.title}</strong>
                      <span>{target.apiEndpoints?.join('、') || target.routePath || target.kind}</span>
                      <div className="target-confirmation-actions">
                        <button type="button" disabled={confirmTarget.isPending} onClick={() => confirmTarget.mutate({ entryId: target.entryId, accepted: true })}>是这个</button>
                        <button type="button" className="secondary" disabled={confirmTarget.isPending} onClick={() => confirmTarget.mutate({ entryId: target.entryId, accepted: false })}>不是</button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          ))}
          {optimisticUser && !conversationMessages.some((message) => message.senderType === 'user' && message.content === optimisticUser.content)
            && <div className="bubble user">{optimisticUser.display}</div>}
          {pendingAssistant && <PendingAssistantBubble />}
          {confirmable && !pendingAssistant && (
            <button type="button" className="primary-strong chat-confirm-prd" onClick={confirmPrd} disabled={draftDirty || !readiness.data?.ready || confirm.isPending}>
              确认 PRD
            </button>
          )}
        </div>
        {Boolean(result.missingFields?.length) && (
          <div className="warning">AI 需要你补充：{result.missingFields?.map((field) => fieldNames[field] || field).join('、')}</div>
        )}
        {conversation.error && <ErrorState title="对话加载失败" error={conversation.error} onRetry={() => conversation.refetch()} />}
        {send.error && <ErrorState title="消息发送失败" error={send.error} />}
        <form onSubmit={form.handleSubmit((value) => {
          if (value.content.trim() || files.length) {
            setOptimisticUser({ content: value.content.trim(), display: value.content.trim() || `已发送 ${files.length} 张图片` });
            send.mutate(value);
          }
        })}>
          <label>
            需求描述
            <textarea rows={4} {...form.register('content')} onPaste={(event) => {
              const images = Array.from(event.clipboardData.files).filter((file) => file.type.startsWith('image/'));
              if (images.length) addFiles(images);
            }} />
          </label>
          <label className="secondary file-picker">选择图片<input type="file" accept="image/png,image/jpeg,image/webp" multiple hidden onChange={(event) => { if (event.target.files) addFiles(event.target.files); event.target.value = ''; }} /></label>
          {files.length > 0 && <div className="pending-files">{files.map((file, index) => <button type="button" className="secondary" key={file.name + index} onClick={() => setFiles((current) => current.filter((_, itemIndex) => itemIndex !== index))}>{file.name} ×</button>)}</div>}
          <button type="submit" disabled={pendingAssistant || !selectedSystemId || (!content.trim() && files.length === 0)}>发送</button>
        </form>
      </div>
      <div className="panel prd-preview-panel">
        <div className="prd-preview-head">
          <h2>工作项预览</h2>
          <div className="prd-preview-status">
            <span>状态</span>
            <StatusBadge value={result.lifecycleStatus || result.status || 'waiting_input'} />
          </div>
        </div>
        {workItemGenerated ? (
          <div className="prd-created-state" role="status">
            <span className="prd-created-icon" aria-hidden="true"><CheckCircle2 size={22} /></span>
            <div className="prd-created-copy">
              <h3>工作项已生成</h3>
              <p>需求已确认，可前往工作项详情查看后续执行状态。</p>
            </div>
            <div className="prd-created-actions">
              <Link className="primary-action-link" to={'/work-items/' + result.workItemId}>查看工作项</Link>
              <button type="button" className="secondary" onClick={reset}>创建另一项</button>
            </div>
          </div>
        ) : <>
          <div className="draft-editor">
            <label>
              标题
              <input aria-label="PRD 标题" value={draftEditor.title} disabled={!draftEditable || pendingAssistant}
                onChange={(event) => setDraftEditor((current) => ({ ...current, title: event.target.value }))} />
            </label>
            <label>
              目标
              <textarea aria-label="PRD 目标" rows={3} value={draftEditor.goal} disabled={!draftEditable || pendingAssistant}
                onChange={(event) => setDraftEditor((current) => ({ ...current, goal: event.target.value }))} />
            </label>
            <div className={'draft-acceptance ' + (acceptanceMissing ? 'missing' : '')}>
              <div className="draft-acceptance-head">
                <strong>验收标准</strong>
                {acceptanceMissing && <span className="draft-field-status">待补充</span>}
              </div>
              {acceptanceMissing && <div className="draft-field-tip">可以直接在这里填写，不用打字描述</div>}
              {draftEditor.acceptanceCriteria.map((criterion, index) => (
                <div className="draft-criterion" key={index}>
                  <input aria-label={`验收标准 ${index + 1}`} value={criterion} disabled={!draftEditable || pendingAssistant}
                    onChange={(event) => setDraftEditor((current) => ({ ...current, acceptanceCriteria: current.acceptanceCriteria.map((item, itemIndex) => itemIndex === index ? event.target.value : item) }))} />
                  <button type="button" className="icon-button danger" aria-label={`删除验收标准 ${index + 1}`} disabled={!draftEditable || pendingAssistant}
                    onClick={() => setDraftEditor((current) => ({ ...current, acceptanceCriteria: current.acceptanceCriteria.filter((_, itemIndex) => itemIndex !== index) }))}><Trash2 size={16} /></button>
                </div>
              ))}
              <button type="button" className="secondary icon-text-button draft-add-criterion" disabled={!draftEditable || pendingAssistant}
                onClick={() => setDraftEditor((current) => ({ ...current, acceptanceCriteria: [...current.acceptanceCriteria, ''] }))}><Plus size={16} />添加验收标准</button>
            </div>
          </div>
          {suspectedTargets.length > 0 && <div className="suspected-targets"><h3>疑似相关页面</h3>{suspectedTargets.map((target) => <div className="list-item" key={target.entryId}>
            <div><strong>{target.title}</strong><span>{target.apiEndpoints?.join('、') || target.routePath || target.kind} · 置信度 {Math.round((target.confidence || 0) * 100)}%</span></div>
            <button type="button" disabled={confirmTarget.isPending || confirmedTargets.some((item) => item.entryId === target.entryId)} onClick={() => confirmTarget.mutate({ entryId: target.entryId, accepted: true })}>{confirmedTargets.some((item) => item.entryId === target.entryId) ? '已确认' : '确认页面'}</button>
          </div>)}</div>}
          {draftDirty && <div className="notice prd-preview-notice">预览内容已修改，保存草稿后即可确认。</div>}
          {readiness.isLoading && <div className="notice" role="status">正在检查系统执行条件…</div>}
          {readiness.isError && <ErrorState title="执行条件检查失败" error={readiness.error} onRetry={() => readiness.refetch()} />}
          {readiness.data && !readiness.data.ready && <div className="warning">
            <strong>系统尚未具备真实执行条件</strong>
            {readiness.data.issues?.length > 0 && <ul>{readiness.data.issues.map((issue) => <li key={issue.code}>{issue.message}</li>)}</ul>}
          </div>}
          {prdId && <div className="prd-preview-actions">
            <button type="button" className="secondary" onClick={() => saveDraft.mutate()}
              disabled={!draftDirty || !draftEditable || pendingAssistant || saveDraft.isPending}>保存草稿</button>
            <button type="button" className={confirmable ? 'primary-strong' : ''} onClick={confirmPrd} disabled={!confirmable || pendingAssistant || draftDirty || !readiness.data?.ready || confirm.isPending}>
              确认并生成工作项
            </button>
          </div>}
          {confirmTarget.error && <ErrorState title="页面确认失败" error={confirmTarget.error} />}
          {saveDraft.error && <ErrorState title="草稿保存失败" error={saveDraft.error} />}
        </>}
      </div>
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

export function PendingAssistantBubble() {
  return <div className="bubble assistant pending" role="status" aria-live="polite">正在分析…</div>;
}

function editorValue(draft: Record<string, unknown>, title?: string, goal?: string): DraftEditorValue {
  return {
    title: typeof draft.title === 'string' ? draft.title : title || '',
    goal: typeof draft.goal === 'string' ? draft.goal : goal || '',
    acceptanceCriteria: Array.isArray(draft.acceptanceCriteria) ? draft.acceptanceCriteria.map(String) : [],
  };
}

function targetList(value: unknown) {
  return Array.isArray(value) ? value as SuspectedTarget[] : [];
}

function observationSummary(observation: UiObservation) {
  return observation.user_visible_summary || observation.userVisibleSummary || [observation.page_title || observation.pageTitle, ...(observation.text_anchors || observation.textAnchors || [])].filter(Boolean).join(' · ');
}
