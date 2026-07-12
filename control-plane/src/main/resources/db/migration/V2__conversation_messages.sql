set search_path to control_plane_v5, public;

create table conversation_messages (
    message_id text primary key,
    conversation_id text not null,
    system_id text not null references systems(system_id),
    prd_id text references prd_sessions(prd_id),
    sender_type text not null,
    content text not null,
    created_by text not null,
    created_at timestamptz not null default now()
);

create index idx_conversation_messages_conversation_id_created_at
    on conversation_messages(conversation_id, created_at);

comment on table conversation_messages is 'PRD 对话消息表';
comment on column conversation_messages.message_id is '消息唯一标识';
comment on column conversation_messages.conversation_id is '会话唯一标识';
comment on column conversation_messages.system_id is '所属系统唯一标识';
comment on column conversation_messages.prd_id is '关联 PRD 标识';
comment on column conversation_messages.sender_type is '发送方类型，user 或 assistant';
comment on column conversation_messages.content is '消息正文';
comment on column conversation_messages.created_by is '创建人';
comment on column conversation_messages.created_at is '创建时间';

