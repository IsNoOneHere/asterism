import { PrdSession } from './api/client';

// 只有这些状态仍需要用户继续完善。
const resumableStatuses = new Set(['need_clarification', 'waiting_user_confirm', 'case_start_failed']);

export function isResumablePrd(session: PrdSession) {
  return resumableStatuses.has(session.status);
}
