-- Event Center (home screen): events are grouped in sections (Bonus, Recommend, ...), carry a corner ribbon tag
-- (PK, Recharge, Family, ...) and open something in the app when tapped.
alter table special_events add column if not exists section varchar(30) not null default 'Recommend';
alter table special_events add column if not exists tag varchar(20);
alter table special_events add column if not exists tag_color varchar(9);
-- shop, ranking, pk, recharge, parivar, games, room:<room id>, or an https:// page
alter table special_events add column if not exists link varchar(300);

-- The Event Center on the home screen: switched on and off from the admin panel (off until the admins turn it on)
create table if not exists event_center_settings (
    id      int primary key default 1 check (id = 1),
    enabled boolean not null default false
);
insert into event_center_settings (id) values (1) on conflict do nothing;
