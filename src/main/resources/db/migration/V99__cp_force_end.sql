-- Ending a CP at once (without the partner's yes) for coins set in the admin panel, and a record of how each CP ended.
create table if not exists cp_settings (
    id integer primary key check (id = 1),
    force_end_enabled boolean not null default false,
    force_end_price bigint not null default 0 check (force_end_price >= 0)
);
insert into cp_settings (id) values (1) on conflict (id) do nothing;

alter table cp_couples add column if not exists ended_how varchar(12);
alter table cp_couples add column if not exists ended_by bigint;
alter table cp_couples add column if not exists force_paid bigint;

create table if not exists cp_admin_audit (
    id bigserial primary key,
    admin_name varchar(120),
    what varchar(40) not null,
    detail varchar(400),
    created_at timestamptz not null default now()
);
