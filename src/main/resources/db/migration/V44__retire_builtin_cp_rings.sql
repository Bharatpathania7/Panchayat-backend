-- CP rings now come only from the admin shop (ids 'shop-<productId>').
-- The built-in starter rings stop being sold; anyone who already owns one keeps it
-- in their bag and can still use it, and couples keep the rings they have.
UPDATE cp_rings SET active = FALSE WHERE id NOT LIKE 'shop-%';
