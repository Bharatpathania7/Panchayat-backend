-- The name people see for the person who checks the payments ("Mukhiya ji", "Munshi ji", ...): set in the admin panel.
alter table manual_topup_settings add column if not exists helper_name varchar(40) not null default 'Mukhiya ji';
