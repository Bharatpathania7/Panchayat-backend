-- Daroga ji: the safety desk, a third official account (Sarpanch, then Munshi ji, now this one) with its own
-- read-only chat. Every safety message (bans, restrictions, report outcomes) comes from Daroga ji, the same
-- way every payment message already comes from Munshi ji.
insert into users (email, full_name, bio, email_verified, profile_completed, official_role)
select 'daroga@panchayat.system', 'Daroga ji', 'The safety desk: reports, bans and keeping Panchayat safe.', true, true, 'DAROGA'
where not exists (select 1 from users where official_role = 'DAROGA');

insert into sarpanch_templates (key, title, body, action_label, action_url, category) values
('REPORT_RESOLVED', 'Your report was reviewed', 'Thanks for the report. We looked into it and took action. Your report helps keep Panchayat safe.', null, null, 'SAFETY'),
('REPORT_DISMISSED', 'Your report was reviewed', 'Thanks for the report. We looked into it and didn''t find enough to act on this time. Keep reporting anything that breaks the rules.', null, null, 'SAFETY')
on conflict (key) do nothing;
