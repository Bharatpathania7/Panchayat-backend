-- How many days a reward lasts once it is won (0 = it never runs out). Set per reward by the admin.
-- A gift card won from a chest or Gacha runs out of the Bag after that many days; a badge / picture / title leaves the
-- profile collection after that many days; frames, bubbles, entry effects and profile titles last that many days.
alter table treasure_rewards add column if not exists valid_days int not null default 0 check (valid_days >= 0);
alter table parivar_gacha_rewards add column if not exists valid_days int not null default 0 check (valid_days >= 0);
-- Gacha gift cards were always valid for 30 days: keep that for the ones that exist
update parivar_gacha_rewards set valid_days = 30 where kind = 'GIFT_CARD';
alter table user_collection add column if not exists expires_at timestamptz;
