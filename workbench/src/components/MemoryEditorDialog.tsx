import { useEffect, useRef, useState } from 'react';
import { MemoryCategory, MemoryDraft } from '../api/client';

const emptyDraft: MemoryDraft = { category: 'constraint', title: '', content: '' };

export function MemoryEditorDialog({ open, title, submitLabel = '保存', initial, workItemId, pending, error, onClose, onSubmit }: {
  open: boolean;
  title: string;
  submitLabel?: string;
  initial?: MemoryDraft;
  workItemId?: string;
  pending: boolean;
  error?: unknown;
  onClose: () => void;
  onSubmit: (draft: MemoryDraft) => void;
}) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [draft, setDraft] = useState<MemoryDraft>(emptyDraft);

  useEffect(() => {
    if (open) {
      setDraft(initial ?? emptyDraft);
      if (!dialogRef.current?.open) dialogRef.current?.showModal();
    } else if (dialogRef.current?.open) {
      dialogRef.current.close();
    }
  }, [initial?.category, initial?.content, initial?.title, open]);

  return <dialog ref={dialogRef} className="confirm-dialog config-dialog memory-dialog" aria-labelledby="memory-dialog-title" onClose={onClose}>
    <form onSubmit={(event) => { event.preventDefault(); onSubmit(draft); }}>
      <h2 id="memory-dialog-title">{title}</h2>
      <p>只记录跨工作项仍然有效的工程规则，不粘贴 diff、日志或一次性改动。</p>
      {workItemId && <div className="field-note">来源工作项：{workItemId}</div>}
      <div className="memory-dialog-fields">
        <label>记忆类型<select value={draft.category} onChange={(event) => setDraft({ ...draft, category: event.target.value as MemoryCategory })}>
          <option value="constraint">约束</option>
          <option value="convention">约定</option>
          <option value="lesson">经验</option>
        </select></label>
        <label>标题<input maxLength={80} required value={draft.title} onChange={(event) => setDraft({ ...draft, title: event.target.value })} /></label>
        <label>正文<textarea maxLength={1000} required rows={6} value={draft.content} onChange={(event) => setDraft({ ...draft, content: event.target.value })} /></label>
      </div>
      {Boolean(error) && <div className="error-text" role="alert">{error instanceof Error ? error.message : '记忆保存失败'}</div>}
      <div className="button-row"><button type="button" className="secondary" onClick={() => dialogRef.current?.close()}>取消</button><button type="submit" disabled={pending || !draft.title.trim() || !draft.content.trim()}>{submitLabel}</button></div>
    </form>
  </dialog>;
}
