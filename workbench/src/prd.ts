// 导入数据会预分配工作项 ID，能否编辑必须由生命周期决定。
const resumableStatuses = new Set([
  'waiting_input',
  'need_clarification',
  'waiting_user_confirm',
  'turn_failed',
  'case_start_failed',
]);

type PrdLifecycleView = { status?: string | null; workItemId?: string | null };

export function isResumablePrd(session: PrdLifecycleView) {
  return resumableStatuses.has(session.status || '');
}

export function hasGeneratedWorkItem(session: PrdLifecycleView) {
  return Boolean(session.workItemId) && !isResumablePrd(session);
}
