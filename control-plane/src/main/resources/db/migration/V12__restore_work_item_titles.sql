set search_path to control_plane_v5, public;

-- 早期投影把内部 ID 当成标题，恢复用户确认的 PRD 标题。
update work_items item
set title = session.title
from prd_sessions session
where item.prd_id = session.prd_id
  and item.title = item.work_item_id
  and session.title is not null
  and btrim(session.title) <> '';
