-- Entry animations from the Shop: how the wearer's picture shows on the entry banner.
--   null / OVERLAY  the picture and name are drawn over the banner (as before)
--   SLOT            the picture goes into the animation's own avatar slot (found by its name), the name into its name slot
--   SLOT:<key>      the same, with the slot named by the admin
--   NONE            no picture, only the name
alter table shop_products add column if not exists entry_avatar varchar(60);
alter table user_rewards add column if not exists entry_avatar varchar(60);
