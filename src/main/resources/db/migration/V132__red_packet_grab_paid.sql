-- Red packet grabs are paid into the wallet the moment they are grabbed.
-- "paid" marks the ones already credited, so the end-of-rain settle only pays
-- grabs made before this change (and never pays one twice).
alter table room_red_packet_grabs add column if not exists paid boolean not null default false;
