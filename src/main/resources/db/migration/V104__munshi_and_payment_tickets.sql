-- Munshi ji: the payments desk. A second official account (Sarpanch is the first; only one row can be "official", so this one is told apart by
-- official_role) with its own read-only chat. Every payment message (top-ups, payment done / failed, tickets) comes from Munshi ji.
alter table users add column if not exists official_role varchar(16);
update users set official_role = 'SARPANCH' where official and official_role is null;
create unique index if not exists uq_users_official_role on users (official_role) where official_role is not null;

insert into users (email, full_name, bio, email_verified, profile_completed, official_role)
select 'munshi@panchayat.system', 'Munshi ji', 'The payments desk: coin top-ups, payment messages and help with payment problems.', true, true, 'MUNSHI'
where not exists (select 1 from users where official_role = 'MUNSHI');

-- The name people see for the payments desk is the Munshi ji account's name (set in the admin panel)
alter table manual_topup_settings alter column helper_name set default 'Munshi ji';
update manual_topup_settings set helper_name = 'Munshi ji' where helper_name = 'Mukhiya ji';

-- Payment problems people raise (a ticket), answered from the admin panel; every answer also comes as a message from Munshi ji
create table if not exists payment_tickets (
    id bigserial primary key,
    user_id bigint not null,
    category varchar(20) not null default 'OTHER',
    reference varchar(80),
    message varchar(1000) not null,
    proof_path varchar(500),
    status varchar(10) not null default 'OPEN',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    closed_at timestamptz,
    closed_by varchar(120)
);
create index if not exists idx_payment_tickets_user on payment_tickets (user_id, id desc);
create index if not exists idx_payment_tickets_status on payment_tickets (status, updated_at desc);

create table if not exists payment_ticket_messages (
    id bigserial primary key,
    ticket_id bigint not null references payment_tickets (id) on delete cascade,
    sender varchar(5) not null,
    admin_name varchar(120),
    body varchar(1000) not null,
    created_at timestamptz not null default now()
);
create index if not exists idx_payment_ticket_messages_ticket on payment_ticket_messages (ticket_id, id);
