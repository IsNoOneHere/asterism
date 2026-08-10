set search_path to control_plane_v5, public;

drop index if exists uq_conversation_pending_assistant;

delete from conversation_messages where sender_type = 'assistant_pending';

alter table conversation_messages
    add constraint chk_conversation_message_sender
        check (sender_type in ('user', 'assistant', 'system'));

create table product_agent_executions (
    execution_id text primary key,
    prd_id text not null references prd_sessions(prd_id),
    status text not null check (status in ('CREATED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    workflow_id text not null unique,
    input_message_id text not null references conversation_messages(message_id),
    context_bundle_id text not null references context_bundles(bundle_id),
    stage text not null default 'CREATED',
    attempt integer not null default 0 check (attempt >= 0),
    failure_code text,
    started_at timestamptz,
    completed_at timestamptz,
    last_heartbeat timestamptz,
    result_message_id text references conversation_messages(message_id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create unique index uq_product_agent_execution_active_prd
    on product_agent_executions(prd_id)
    where status in ('CREATED', 'RUNNING');

create index idx_product_agent_execution_prd_created
    on product_agent_executions(prd_id, created_at desc);

create index idx_product_agent_execution_input_message
    on product_agent_executions(input_message_id);

comment on table product_agent_executions is 'Product Agent 执行查询与审计投影，Temporal 是执行生命周期权威';
comment on column product_agent_executions.workflow_id is '确定性的 Temporal Workflow ID';
comment on column product_agent_executions.attempt is '同一 Workflow 的启动或执行尝试次数';
comment on column product_agent_executions.result_message_id is '首次合法完成事件生成的 assistant 消息';

comment on column conversation_messages.sender_type is '发送方类型：user、assistant 或 system';
