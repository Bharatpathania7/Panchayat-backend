-- App-wide switches changeable from the admin panel (Settings page), including maintenance mode.
create table if not exists app_settings (
    id                      int primary key default 1 check (id = 1),
    maintenance             boolean not null default false,
    maintenance_message     text not null default 'Panchayat is getting an upgrade. We''ll be back in a few minutes.',
    -- Expected end time, shown to users as a countdown. Does not auto turn maintenance off: staff still do that by hand.
    maintenance_until       timestamptz,
    announcement            text not null default '',
    signup_bonus_coins      int not null default 500 check (signup_bonus_coins >= 0),
    daily_checkin_coins     int not null default 50 check (daily_checkin_coins >= 0),
    gifting_enabled         boolean not null default true,
    room_creation_enabled   boolean not null default true,
    room_create_min_level   int not null default 0 check (room_create_min_level >= 0),
    min_app_version         varchar(20) not null default '1.0.0',
    updated_at              timestamptz not null default now(),
    updated_by              varchar(190)
);

insert into app_settings (id) values (1) on conflict do nothing;
