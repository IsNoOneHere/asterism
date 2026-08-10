import { AlertTriangle, RotateCw } from 'lucide-react';

const labels: Record<string, string> = {
  waiting_owner_approval: '待负责人审批',
  activated: '执行中',
  modification_completed: '修改完成',
  worker_blocked: '执行阻塞',
  patch_applied: 'Patch 已应用',
  patch_rejected: 'Patch 已拒绝',
  validation_passed: '验证通过',
  validation_failed: '验证失败',
  waiting_merge: '等待 GitLab 合并',
  completed: '已完成',
  cancelled: '已取消',
  rejected: '已拒绝',
  pending: '待处理',
  submitted: '已提交',
  approved: '已批准',
  waiting_input: '待输入',
  need_clarification: '待补充',
  waiting_user_confirm: '待确认',
  turn_failed: 'AI 生成失败',
  case_start_failed: '启动失败',
  case_starting: '启动中',
  confirmed: '已确认',
  allocated: '已分配',
  candidate: '待审批',
  proposed: '待审批',
  disabled: '已停用',
  superseded: '已被替代',
  unknown: '未知',
};

const danger = new Set(['worker_blocked', 'patch_rejected', 'validation_failed', 'turn_failed', 'case_start_failed', 'rejected']);
const warning = new Set(['waiting_owner_approval', 'pending', 'proposed', 'need_clarification', 'waiting_user_confirm']);
const success = new Set(['completed', 'validation_passed', 'approved', 'confirmed']);
const info = new Set(['activated', 'modification_completed', 'patch_applied', 'waiting_merge', 'case_starting']);

export function StatusBadge({ value }: { value?: string | null }) {
  const status = value || 'unknown';
  const tone = danger.has(status) ? 'danger' : warning.has(status) ? 'warning' : success.has(status) ? 'success' : info.has(status) ? 'info' : 'neutral';
  return <span className={'status-badge ' + tone}>{labels[status] || status}</span>;
}

export function formatDateTime(value?: string | null) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(date);
}

export function errorMessage(error: unknown, fallback = '请求没有成功，请稍后重试。') {
  return error instanceof Error && error.message ? error.message : fallback;
}

export function ErrorState({ title = '加载失败', error, onRetry }: { title?: string; error?: unknown; onRetry?: () => void }) {
  // 查询失败统一使用与确认弹窗一致的视觉层级，避免误显示成空数据。
  const message = errorMessage(error);
  return <div className="error-state" role="alert">
    <span className="error-state-icon" aria-hidden="true"><AlertTriangle size={20} /></span>
    <div><strong>{title}</strong><p>{message}</p></div>
    {onRetry && <button type="button" className="secondary icon-text-button" onClick={onRetry}><RotateCw size={15} />重新加载</button>}
  </div>;
}
