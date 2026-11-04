-- Privacy: people can hide that they're online (and their last seen time)
alter table users add column if not exists hide_online boolean not null default false;
