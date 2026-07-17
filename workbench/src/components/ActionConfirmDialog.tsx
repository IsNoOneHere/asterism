import { AlertTriangle } from 'lucide-react';
import { ReactNode, useEffect, useId, useRef } from 'react';

export function ActionConfirmDialog({ open, title, description, confirmLabel = '确认', pending = false, tone = 'danger', alert = false, showCancel = true, fields, onClose, onConfirm }: {
  open: boolean;
  title: string;
  description: string;
  confirmLabel?: string;
  pending?: boolean;
  tone?: 'danger' | 'primary';
  alert?: boolean;
  showCancel?: boolean;
  fields?: ReactNode;
  onClose: () => void;
  onConfirm: () => void;
}) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const descriptionId = useId();

  useEffect(() => {
    if (open && !dialogRef.current?.open) dialogRef.current?.showModal();
    if (!open && dialogRef.current?.open) dialogRef.current.close();
  }, [open]);

  return <dialog
    ref={dialogRef}
    className="confirm-dialog action-confirm-dialog"
    data-tone={tone}
    role={alert ? 'alertdialog' : undefined}
    aria-labelledby={titleId}
    aria-describedby={descriptionId}
    onCancel={(event) => { event.preventDefault(); if (!pending) onClose(); }}
  >
    <div className="action-confirm-body">
      <span className="action-confirm-icon" aria-hidden="true"><AlertTriangle size={22} /></span>
      <div>
        <h2 id={titleId}>{title}</h2>
        <p id={descriptionId}>{description}</p>
      </div>
    </div>
    {fields}
    <div className="action-confirm-actions">
      {showCancel && <button type="button" className="secondary" disabled={pending} onClick={onClose}>取消</button>}
      <button type="button" className={tone === 'danger' ? 'danger-action' : ''} disabled={pending} onClick={onConfirm}>
        {pending ? '处理中…' : confirmLabel}
      </button>
    </div>
  </dialog>;
}
