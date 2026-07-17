set search_path to control_plane_v5, public;

alter table work_items add column display_work_item_id text;

-- 正常历史编号保持不变，早期 UUID 编号按创建日期补成可读编号。
update work_items
set display_work_item_id = work_item_id
where work_item_id ~ '^WI[0-9]{11,12}$';

with malformed as (
    select work_item_id,
           to_char(created_at at time zone 'Asia/Shanghai', 'YYYYMMDD') as day_key,
           row_number() over (
               partition by to_char(created_at at time zone 'Asia/Shanghai', 'YYYYMMDD')
               order by created_at, work_item_id
           ) as day_sequence
    from work_items
    where display_work_item_id is null
), numbered as (
    select malformed.work_item_id,
           'WI' || malformed.day_key || lpad((
               coalesce((
                   select max(right(existing.work_item_id, 3)::integer)
                   from work_items existing
                   where existing.work_item_id ~ ('^WI' || malformed.day_key || '[0-9]{3}$')
               ), 0) + malformed.day_sequence
           )::text, 3, '0') as display_work_item_id
    from malformed
)
update work_items
set display_work_item_id = numbered.display_work_item_id
from numbered
where work_items.work_item_id = numbered.work_item_id;

alter table work_items alter column display_work_item_id set not null;
alter table work_items add constraint uq_work_items_display_id unique (display_work_item_id);

-- 未产生事件和工作项的旧草稿不应占用伪工作项编号，恢复为待分配状态。
update prd_sessions session
set work_item_id = null
where session.work_item_id is not null
  and session.work_item_id !~ '^WI[0-9]{11,12}$'
  and not exists (select 1 from work_items item where item.work_item_id = session.work_item_id)
  and not exists (select 1 from domain_events event where event.work_item_id = session.work_item_id);

comment on column work_items.display_work_item_id is '面向用户展示和搜索的稳定工作项编号；内部关联继续使用 work_item_id';
