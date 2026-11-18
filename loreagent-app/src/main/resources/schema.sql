create table if not exists conversation (
    id uuid primary key,
    user_id text not null default 'local',
    title text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists chat_message (
    id bigserial primary key,
    conversation_id uuid not null references conversation(id) on delete cascade,
    user_id text not null default 'local',
    seq int not null,
    role text not null,
    content text not null,
    tool_calls jsonb,
    citations jsonb,
    -- 仅 user 行与最终答复 assistant 行填写；中间 tool_calls / tool 步骤留 null，防止触发唯一索引冲突
    request_id text,
    created_at timestamptz not null default now(),
    unique (conversation_id, seq)
);

create index if not exists conversation_list_idx
    on conversation (user_id, updated_at desc);

create unique index if not exists chat_message_request_idx
    on chat_message (request_id)
    where role = 'assistant' and request_id is not null;
