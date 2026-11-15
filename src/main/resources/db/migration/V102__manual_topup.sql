-- Manual UPI top-up: for people whose Google Pay / Paytm shows the gateway as risky. The person pays the UPI ID set in the admin panel,
-- sends the transaction ID (UTR) and a screenshot, and Mukhiya ji (the admin) checks it in the bank and approves: the coins of the pack are added.
create table if not exists manual_topup_settings (
    id integer primary key check (id = 1),
    enabled boolean not null default false,
    upi_id varchar(100),
    payee_name varchar(100),
    qr_url varchar(500),
    note varchar(500)
);
insert into manual_topup_settings (id) values (1) on conflict (id) do nothing;

create table if not exists manual_topups (
    id bigserial primary key,
    user_id bigint not null,
    pack_id bigint not null,
    pack_name varchar(100) not null,
    coins bigint not null,
    bonus_coins bigint not null default 0,
    amount_inr numeric(10, 2) not null,
    reference varchar(16) not null unique,
    status varchar(12) not null default 'REQUESTED',
    utr varchar(40),
    proof_path varchar(500),
    submitted_at timestamptz,
    reviewed_by varchar(120),
    reviewed_at timestamptz,
    reject_reason varchar(200),
    created_at timestamptz not null default now()
);
create index if not exists idx_manual_topups_user on manual_topups (user_id, created_at desc);
create index if not exists idx_manual_topups_status on manual_topups (status, created_at);
-- One transaction ID can back only one request that is waiting or approved
create unique index if not exists uq_manual_topups_utr on manual_topups (upper(utr)) where status in ('SUBMITTED', 'APPROVED');
