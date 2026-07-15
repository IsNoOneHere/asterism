set search_path to control_plane_v5, public;

create unique index uq_conversation_pending_assistant
    on conversation_messages(conversation_id)
    where sender_type = 'assistant_pending';

comment on index uq_conversation_pending_assistant is '同一 PRD 对话同时只能有一个待处理 AI 回合';
comment on column conversation_messages.sender_type is '发送方类型：user、assistant 或 assistant_pending';
