-- When a Parivar levels up, the whole family fund is used (the treasury goes to 0), not just the level's price.
-- The admin can turn this off to take only the price of the level.
alter table parivar_settings add column if not exists levelup_spend_all boolean not null default true;
