-- Room treasure ("Daily Task" in a voice room): boxes open as a person spends time in voice rooms today (India time);
-- three small tasks each take a few minutes off the wait. Everything is set in the admin panel.
create table if not exists room_treasure_settings (
    id integer primary key check (id = 1),
    enabled boolean not null default false,
    bonus_follow integer not null default 1 check (bonus_follow between 0 and 120),
    bonus_gift integer not null default 1 check (bonus_gift between 0 and 120),
    bonus_message integer not null default 1 check (bonus_message between 0 and 120)
);
insert into room_treasure_settings (id) values (1) on conflict (id) do nothing;

create table if not exists room_treasure_boxes (
    id bigserial primary key,
    minutes integer not null check (minutes between 1 and 1440),
    reward_coins bigint not null default 0 check (reward_coins between 0 and 100000000),
    vip_only boolean not null default false,
    active boolean not null default true,
    created_at timestamptz not null default now()
);

create table if not exists room_treasure_user_day (
    user_id bigint not null,
    day date not null,
    seconds integer not null default 0,
    followed boolean not null default false,
    gifted boolean not null default false,
    messaged boolean not null default false,
    primary key (user_id, day)
);

create table if not exists room_treasure_claims (
    user_id bigint not null,
    day date not null,
    box_id bigint not null,
    coins bigint not null,
    claimed_at timestamptz not null default now(),
    primary key (user_id, day, box_id)
);

-- Starting set (the feature itself stays off until it is switched on in the admin panel)
insert into room_treasure_boxes (minutes, reward_coins, vip_only) values (5, 50, false), (10, 100, false), (20, 200, false), (40, 400, false), (15, 500, true), (30, 1000, true);
