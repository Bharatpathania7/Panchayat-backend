-- Gives each big coin pack its title, by price (only where the pack and the title exist, and never over a title the admin already chose):
--   Rs 169 pack (10,00,000 coins)    -> Lakhpati
--   Rs 249 pack (20,00,000 coins)    -> Seth Ji
--   Rs 449 pack (50,00,000 coins)    -> Nagar Seth
--   Rs 1,049 pack (1,00,00,000 coins) -> Crorepati
-- days = 0: the title lasts as long as the title itself is set to last (Admin > Profile titles).
INSERT INTO coin_pack_titles (pack_id, title_id, days)
SELECT p.id, t.id, 0
FROM (VALUES (169, 'lakhpati'), (249, 'seth ji'), (449, 'nagar seth'), (1049, 'crorepati')) AS m (price, title_name)
JOIN coin_packs p ON p.price_in_rupees = m.price
JOIN LATERAL (
    SELECT id FROM profile_titles WHERE lower(name) = m.title_name AND active ORDER BY id LIMIT 1
) t ON TRUE
ON CONFLICT (pack_id) DO NOTHING;
