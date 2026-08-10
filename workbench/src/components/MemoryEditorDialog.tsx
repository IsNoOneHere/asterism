import { useEffect, useRef, useState } from 'react';
import {
  KnowledgeEntry, MemoryApplicability, MemoryDraft, MemoryType,
} from '../api/client';

const emptyDraft: MemoryDraft = {
  memoryType: 'FACT',
  title: '',
  content: '',
  confidence: 0.8,
  applicability: 'PROJECT',
  expiresAt: null,
  targetRefs: [],
};
const memoryTypeOptions: { value: MemoryType; label: string }[] = [
  { value: 'FACT', label: '项目事实' },
  { value: 'DECISION', label: '项目决策' },
  { value: 'CONSTRAINT', label: '项目约束' },
  { value: 'EXPERIENCE', label: '项目经验' },
];

export function MemoryEditorDialog({
  open,
  initial,
  allowedTypes,
  knowledgeTargets = [],
  sourceLabel,
  workItemId,
  pending,
  error,
  onClose,
  onSubmit,
}: {
  open: boolean;
  initial?: MemoryDraft;
  allowedTypes?: MemoryType[];
  knowledgeTargets?: KnowledgeEntry[];
  sourceLabel?: string;
  workItemId?: string | null;
  pending: boolean;
  error?: unknown;
  onClose: () => void;
  onSubmit: (draft: MemoryDraft) => void;
}) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [draft, setDraft] = useState<MemoryDraft>(emptyDraft);
  const initialTargetKey = initial?.targetRefs?.join('|') || '';
  const selectableTypes = memoryTypeOptions.filter((option) =>
    !allowedTypes?.length || allowedTypes.includes(option.value));

  useEffect(() => {
    if (open) {
      setDraft(initial ?? emptyDraft);
      if (!dialogRef.current?.open) dialogRef.current?.showModal();
    } else if (dialogRef.current?.open) {
      dialogRef.current.close();
    }
  }, [
    initial?.applicability,
    initial?.confidence,
    initial?.content,
    initial?.expiresAt,
    initial?.memoryType,
    initial?.title,
    initialTargetKey,
    open,
  ]);

  const toggleTarget = (entryId: string) => setDraft((current) => ({
    ...current,
    targetRefs: current.targetRefs.includes(entryId)
      ? current.targetRefs.filter((value) => value !== entryId)
      : [...current.targetRefs, entryId],
  }));

  return <dialog
    ref={dialogRef}
    className="confirm-dialog config-dialog memory-dialog"
    aria-labelledby="memory-dialog-title"
    onClose={onClose}
  >
    <form onSubmit={(event) => { event.preventDefault(); onSubmit(draft); }}>
      <h2 id="memory-dialog-title">确认项目记忆</h2>
      <p>请只保留已确认的事实、决策、约束或经验；系统不会直接保存 Agent 推理和完整对话。</p>
      {(sourceLabel || workItemId) && <div className="field-note">
        来源：{sourceLabel || 'Artifact'}{workItemId ? ` · 工作项 ${workItemId}` : ''}
      </div>}
      <div className="memory-dialog-fields">
        <label>记忆类型
          <select
            value={draft.memoryType}
            onChange={(event) => setDraft({ ...draft, memoryType: event.target.value as MemoryType })}
          >
            {selectableTypes.map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </label>
        <label>适用范围
          <select
            value={draft.applicability}
            onChange={(event) => setDraft({
              ...draft,
              applicability: event.target.value as MemoryApplicability,
            })}
          >
            <option value="PROJECT">整个项目</option>
            <option value="ARTIFACT_LINEAGE">仅当前产物链</option>
          </select>
        </label>
        <label>置信度
          <input
            type="number"
            min="0"
            max="1"
            step="0.01"
            required
            value={draft.confidence}
            onChange={(event) => setDraft({ ...draft, confidence: Number(event.target.value) })}
          />
        </label>
        <label>失效时间（可选）
          <input
            type="datetime-local"
            value={toLocalInput(draft.expiresAt)}
            onChange={(event) => setDraft({
              ...draft,
              expiresAt: event.target.value ? new Date(event.target.value).toISOString() : null,
            })}
          />
        </label>
        <label>标题
          <input
            maxLength={80}
            required
            value={draft.title}
            onChange={(event) => setDraft({ ...draft, title: event.target.value })}
          />
        </label>
        <label>正文
          <textarea
            maxLength={1000}
            required
            rows={7}
            value={draft.content}
            onChange={(event) => setDraft({ ...draft, content: event.target.value })}
          />
        </label>
        <fieldset className="memory-target-picker">
          <legend>关联页面 / 路由 / API（可选）</legend>
          <span className="field-note">精确关联可提高后续相似需求召回优先级。</span>
          <div>{knowledgeTargets.map((target) => <label key={target.entryId}>
            <input
              type="checkbox"
              checked={draft.targetRefs.includes(target.entryId)}
              onChange={() => toggleTarget(target.entryId)}
            />
            <span>{target.title}<small>{target.routePath || target.apiEndpoints.join('、') || target.kind}</small></span>
          </label>)}</div>
          {!knowledgeTargets.length && <span className="field-note">当前没有已批准的系统知识目标。</span>}
        </fieldset>
      </div>
      {Boolean(error) && <div className="error-text" role="alert">
        {error instanceof Error ? error.message : '项目记忆确认失败'}
      </div>}
      <div className="button-row">
        <button type="button" className="secondary" onClick={() => dialogRef.current?.close()}>取消</button>
        <button
          type="submit"
          disabled={pending || !draft.title.trim() || !draft.content.trim()}
        >
          确认并生效
        </button>
      </div>
    </form>
  </dialog>;
}

function toLocalInput(value?: string | null) {
  if (!value) return '';
  const date = new Date(value);
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
}
