create extension if not exists vector;

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

create table if not exists document (
    id uuid primary key,
    user_id text not null default 'local',
    title text not null,
    tags text[] not null default '{}',
    doc_date date,
    active_version int not null default 1,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (user_id, title)
);

create table if not exists chunks (
    id bigserial primary key,
    document_id uuid not null references document(id) on delete cascade,
    user_id text not null default 'local',
    version int not null,
    chunk_index int not null,
    heading text not null default '',
    content text not null,
    tokens text not null,
    -- 不固定维度 vector 列：为后续切换 embedding 模型平滑重建留出空间
    embedding vector,
    embedding_model text,
    dim int,
    created_at timestamptz not null default now()
);

create index if not exists document_user_idx
    on document (user_id, updated_at desc);

create index if not exists document_tags_gin_idx
    on document using gin (tags);

create index if not exists chunks_doc_ver_idx
    on chunks (user_id, document_id, version, chunk_index);

create index if not exists chunks_tokens_gin_idx
    on chunks using gin (to_tsvector('simple', tokens));

create table if not exists run_trace (
    id bigserial primary key,
    conversation_id uuid references conversation(id) on delete cascade,
    request_id text,
    user_id text not null default 'local',
    query text not null,
    retrieved_chunks jsonb not null default '[]'::jsonb,
    degraded boolean not null default false,
    finish_reason text,
    usage jsonb,
    created_at timestamptz not null default now()
);

create index if not exists run_trace_conv_idx
    on run_trace (user_id, conversation_id, created_at desc);
