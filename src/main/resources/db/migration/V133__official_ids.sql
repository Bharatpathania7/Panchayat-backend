-- Official IDs made in the admin panel: numbered 001, 002, 003, ... (official_code 1, 2, 3, ...).
-- They are normal accounts that staff sign in to (with the email set here), shown with a blue tick and "ID 001",
-- and anyone can message them without the message-request limit.
alter table users add column if not exists official_code int;
create unique index if not exists uq_users_official_code on users (official_code) where official_code is not null;
