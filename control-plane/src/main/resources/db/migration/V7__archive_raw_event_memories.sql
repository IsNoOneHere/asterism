set search_path to control_plane_v5, public;

-- 旧版本把生命周期事件 JSON 直接当成记忆；保留审计记录，只退出待审批队列。
update memory_items
set status = 'rejected'
where status = 'candidate'
  and source_event_id is not null
  and metadata_json = '{}'::jsonb
  and content ~ '^(WorkerBlocked|ModificationCompleted|PatchApplied|ValidationFailed|ValidationPassed|ReleaseCompleted) ';
