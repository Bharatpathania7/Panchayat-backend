-- Levelling a Parivar up takes only the family fund the next level asks for (for example 48.6 Cr of a 60 Cr fund), not the whole fund.
-- The switch stays in the admin panel (Parivar settings, "Level up uses ALL the family fund") if it is ever wanted again.
alter table parivar_settings alter column levelup_spend_all set default false;
update parivar_settings set levelup_spend_all = false;
