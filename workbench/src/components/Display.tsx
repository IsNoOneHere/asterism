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
  approved: '已批准',
  waiting_input: '待输入',
  need_clarification: '待补充',
  waiting_user_confirm: '待确认',
  case_start_failed: '启动失败',
  case_starting: '启动中',
  confirmed: '已确认',
  imported: '历史导入',
  imported_pending: '历史待处理',
  imported_completed: '历史已完成',
  candidate: '待审批',
  disabled: '已停用',
};

const danger = new Set(['worker_blocked', 'patch_rejected', 'validation_failed', 'case_start_failed', 'rejected']);
const warning = new Set(['waiting_owner_approval', 'pending', 'need_clarification', 'waiting_user_confirm', 'imported_pending']);
const success = new Set(['completed', 'validation_passed', 'approved', 'confirmed', 'imported_completed']);
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
