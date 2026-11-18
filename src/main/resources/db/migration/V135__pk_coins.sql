-- PK Coins: a currency of its own, earned by sending gifts during a live PK and spent in the Shop's PK category.

create table if not exists pk_coin_wallets (
    user_id     bigint primary key references users (id) on delete cascade,
    balance     bigint not null default 0 check (balance >= 0),
    earned      bigint not null default 0,
    updated_at  timestamptz not null default now()
);

-- Every change: EARN (a PK gift), SPEND (the PK Shop), GRANT / DEDUCT (staff, with a reason)
create table if not exists pk_coin_transactions (
    id              bigserial primary key,
    user_id         bigint not null references users (id) on delete cascade,
    amount          bigint not null,
    kind            varchar(12) not null check (kind in ('EARN', 'SPEND', 'GRANT', 'DEDUCT')),
    ref             varchar(80),
    reason          varchar(300),
    admin_name      varchar(120),
    balance_after   bigint not null,
    idempotency_key varchar(120) not null unique,
    created_at      timestamptz not null default now()
);
create index if not exists idx_pk_coin_tx_user on pk_coin_transactions (user_id, id desc);
create index if not exists idx_pk_coin_tx_earn on pk_coin_transactions (created_at) where kind = 'EARN';

-- How PK Coins are earned (one row)
create table if not exists pk_coin_settings (
    id              int primary key default 1 check (id = 1),
    enabled         boolean not null default true,
    -- PK Coins for every 100 coins of gifts sent during a live PK
    percent         int not null default 10 check (percent between 0 and 1000),
    -- Gifts below this many coins earn nothing
    min_gift_coins  int not null default 1 check (min_gift_coins >= 0),
    official_bonus  int not null default 0 check (official_bonus between 0 and 1000),
    -- PK Shop items are never permanent: an item with no days set lasts this many days
    item_days       int not null default 7 check (item_days between 1 and 365),
    updated_at      timestamptz not null default now()
);
insert into pk_coin_settings (id) values (1) on conflict do nothing;

-- Shop categories say what they are bought with: coins, or PK Coins (the PK category)
alter table shop_categories add column if not exists currency varchar(10) not null default 'COINS' check (currency in ('COINS', 'PK_COINS'));
update shop_categories set currency = 'PK_COINS' where name = 'PK';
insert into shop_categories (name, description, display_order, active, currency)
select 'PK', 'Exclusive rewards for PK Coins, earned by sending gifts in PK battles', 0, true, 'PK_COINS'
where not exists (select 1 from shop_categories where name = 'PK' or currency = 'PK_COINS');

-- Official PK rooms (set from the admin panel): an Official PK badge, and a title for the event
alter table voice_rooms add column if not exists official_pk boolean not null default false;
alter table voice_rooms add column if not exists official_pk_title varchar(80);
