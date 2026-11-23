-- All of a user's restrictions in one place (short of a full ban): right now CHAT_BAN (can't send any chat,
-- room or private) and MOMENT_BAN (can't post a Moment). One row per (user, kind); removing the row lifts it.
-- expires_at null = until staff lift it by hand.
create table if not exists user_restrictions (
    user_id    bigint      not null references users (id) on delete cascade,
    kind       varchar(20) not null check (kind in ('CHAT_BAN', 'MOMENT_BAN')),
    reason     varchar(300),
    created_by varchar(120),
    created_at timestamptz not null default now(),
    expires_at timestamptz,
    primary key (user_id, kind)
);
