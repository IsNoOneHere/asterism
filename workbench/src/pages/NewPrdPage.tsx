import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { ArrowLeft } from 'lucide-react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { api, PrdMessageResult, SuspectedTarget, UiObservation } from '../api/client';
import { StatusBadge } from '../components/Display';
import { SystemSelect } from '../components/SystemSelect';
import { useCurrentSystem } from '../SystemContext';

const schema = z.object({
  systemId: z.string().min(1),
  content: z.string(),
});

type FormValue = z.infer<typeof schema>;

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
  const completedAssistant = useRef<string>();
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
  const content = form.watch('content');
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
    // 刷新编辑地址时，恢复草稿原始系统和对话。
    setSystemId(session.systemId);
    form.reset({ systemId: session.systemId, content: '' });
    setPrdId(session.prdId);
    setConversationId(session.conversationId);
    setResult({ status: session.status, draft: session.draft, missingFields: session.missingFields, workItemId: session.workItemId });
  }, [draftSession.data, form, setSystemId]);

  const send = useMutation({
    mutationFn: async (value: FormValue) => {
      const uploaded = await Promise.all(files.map((file) => api.uploadAttachment(value.systemId, file)));
      return api.sendPrdMessage(value.systemId, { prdId, content: value.content, attachmentIds: uploaded.map((item) => item.attachmentId) });
    },
    onSuccess: (data) => {
      console.info('v5 workbench PRD 对话发送成功', { prdId: data.prdId });
      if (!prdId && data.prdId) navigate('/work-items/new/' + data.prdId, { replace: true });
      setPrdId(data.prdId);
      setConversationId(data.conversationId);
      setResult(data);
      form.resetField('content');
      setFiles([]);
      queryClient.invalidateQueries({ queryKey: ['conversation', data.conversationId] });
      queryClient.invalidateQueries({ queryKey: ['prd-sessions'] });
    },
    onError: () => setOptimisticUser(undefined),
  });
  const confirmTarget = useMutation({
    mutationFn: (entryId: string) => api.confirmPrdTargets(prdId!, [entryId]),
    onSuccess: (data) => {
      setResult((current) => ({ ...current, draft: data.draft }));
      queryClient.invalidateQueries({ queryKey: ['conversation', conversationId] });
    },
  });
  const confirm = useMutation({
    mutationFn: () => api.confirmPrd(prdId!),
    onSuccess: (data) => {
      setResult((current) => ({ ...current, ...data, status: 'confirmed' }));
      queryClient.invalidateQueries({ queryKey: ['prd-sessions'] });
    },
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
    if (prdId || (!content.trim() && files.length === 0)) return;
    const beforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    const guardLink = (event: MouseEvent) => {
      if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
      const anchor = event.target instanceof Element ? event.target.closest('a[href]') : null;
      if (!anchor || anchor.getAttribute('target') === '_blank') return;
      const url = new URL(anchor.getAttribute('href') || '', window.location.href);
      if (url.origin !== window.location.origin || window.confirm(unsavedMessage)) return;
      event.preventDefault();
      event.stopPropagation();
    };
    window.addEventListener('beforeunload', beforeUnload);
    document.addEventListener('click', guardLink, true);
    return () => {
      window.removeEventListener('beforeunload', beforeUnload);
      document.removeEventListener('click', guardLink, true);
    };
  }, [content, files.length, prdId]);

  function reset() {
    if (routePrdId) navigate('/work-items/new');
    setPrdId(undefined);
    setConversationId(undefined);
    setResult({ status: 'waiting_input' });
    setOptimisticUser(undefined);
    form.reset({ systemId: selectedSystemId, content: '' });
    setFiles([]);
  }

  function confirmPrd() {
    if (window.confirm('确认后将创建工作项并启动执行流程，是否继续？')) confirm.mutate();
  }

  function addFiles(values: FileList | File[]) {
    setFiles((current) => [...current, ...Array.from(values).filter((file) => file.type.startsWith('image/'))].slice(0, 3));
  }

  const suspectedTargets = targetList(result.draft?.suspectedTargets);
  const confirmedTargets = targetList(result.draft?.targets);
  const pendingAssistant = send.isPending || Boolean(result.assistantPending) || Boolean(conversation.data?.pendingAssistant);
  const conversationMessages = conversation.data?.messages ?? [];

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
      {routePrdId && draftSession.isLoading && prdId !== routePrdId ? <div className="panel">草稿加载中...</div> :
      routePrdId && draftSession.isError && prdId !== routePrdId ? <div className="panel error-text">草稿加载失败。</div> :
      <div className="split wide-left create-workspace-grid">
      <div className="panel chat-panel">
        <h2>AI 需求沟通</h2>
        <SystemSelect systems={systems} value={selectedSystemId} label="所属系统" disabled={Boolean(prdId)} onChange={(value) => { setSystemId(value); form.setValue('systemId', value); }} />
        <div className="message-list">
          {conversationMessages.map((message) => (
            <div className={'bubble ' + (message.senderType === 'user' ? 'user' : 'assistant')} key={message.messageId}>
              {message.content && <div>{message.content}</div>}
              {message.attachmentIds?.length > 0 && <div className="message-images">{message.attachmentIds.map((id) => <img key={id} src={api.attachmentUrl(id)} alt="需求截图" />)}</div>}
              {message.observations?.map((observation, index) => <small className="observation-summary" key={index}>{observationSummary(observation)}</small>)}
            </div>
          ))}
          {optimisticUser && !conversationMessages.some((message) => message.senderType === 'user' && message.content === optimisticUser.content)
            && <div className="bubble user">{optimisticUser.display}</div>}
          {pendingAssistant && <PendingAssistantBubble />}
        </div>
        {Boolean(result.missingFields?.length) && (
          <div className="warning">AI 需要你补充：{result.missingFields?.map((field) => fieldNames[field] || field).join('、')}</div>
        )}
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
      <div className="panel">
        <h2>工作项预览</h2>
        <dl className="summary-list">
          <dt>状态</dt>
          <dd><StatusBadge value={result.lifecycleStatus || result.status || 'waiting_input'} /></dd>
          <dt>标题</dt>
          <dd>{text(result.draft?.title)}</dd>
          <dt>目标</dt>
          <dd>{text(result.draft?.goal)}</dd>
          <dt>验收标准</dt>
          <dd>{arrayText(result.draft?.acceptanceCriteria)}</dd>
        </dl>
        {suspectedTargets.length > 0 && <div className="suspected-targets"><h3>疑似相关页面</h3>{suspectedTargets.map((target) => <div className="list-item" key={target.entryId}>
          <div><strong>{target.title}</strong><span>{target.apiEndpoints?.join('、') || target.routePath || target.kind} · 置信度 {Math.round((target.confidence || 0) * 100)}%</span></div>
          <button type="button" disabled={confirmTarget.isPending || confirmedTargets.some((item) => item.entryId === target.entryId)} onClick={() => confirmTarget.mutate(target.entryId)}>{confirmedTargets.some((item) => item.entryId === target.entryId) ? '已确认' : '确认页面'}</button>
        </div>)}</div>}
        <button type="button" className={confirmable ? 'primary-strong' : ''} onClick={confirmPrd} disabled={!prdId || !confirmable || pendingAssistant || !readiness.data?.ready || confirm.isPending}>
          确认并生成工作项
        </button>
        {!readiness.data?.ready && <div className="warning">系统尚未具备真实执行条件</div>}
        {result.workItemId && (
          <div className="success-text">
            工作项已生成 <Link className="action-link" to={'/work-items/' + result.workItemId}>查看工作项</Link>
          </div>
        )}
        {prdId && <button type="button" className="secondary" onClick={reset}>创建另一项</button>}
        {send.isError && <div className="error-text">{String(send.error)}</div>}
        {confirm.isError && <div className="error-text">{String(confirm.error)}</div>}
        {confirmTarget.isError && <div className="error-text">{String(confirmTarget.error)}</div>}
      </div>
      </div>}
    </section>
  );
}

export function PendingAssistantBubble() {
  return <div className="bubble assistant pending" role="status" aria-live="polite">正在分析…</div>;
}

function text(value: unknown) {
  return typeof value === 'string' && value.trim() ? value : '未生成';
}

function arrayText(value: unknown) {
  return Array.isArray(value) && value.length ? value.map(String).join('；') : '未生成';
}

function targetList(value: unknown) {
  return Array.isArray(value) ? value as SuspectedTarget[] : [];
}

function observationSummary(observation: UiObservation) {
  return observation.user_visible_summary || observation.userVisibleSummary || [observation.page_title || observation.pageTitle, ...(observation.text_anchors || observation.textAnchors || [])].filter(Boolean).join(' · ');
}
