-- A staff account flagged here can join any password-protected room without the password,
-- invisibly: not in the participant list, no join/leave broadcast, listen-only. Set by a Super
-- Admin from Staff. Every use is written to silent_observer_audit for accountability.
alter table users add column if not exists silent_observer boolean not null default false;
alter table voice_room_participants add column if not exists invisible boolean not null default false;

create table if not exists silent_observer_audit (
    id         bigserial primary key,
    user_id    bigint not null references users(id),
    room_id    bigint not null,
    joined_at  timestamptz not null default now()
);
create index if not exists idx_silent_observer_audit_user on silent_observer_audit(user_id);
