-- Shop items can optionally have an animation (any category), same idea as a Gift's icon + animation.
alter table shop_products add column if not exists animation_url varchar(1000);
alter table shop_products add column if not exists animation_format varchar(10);

-- A Shop item that IS a gift: linked to a real gift so buying it lands in the Package tab of "Send a
-- gift" (free to send, like a chest-won gift), not in the Bag like a wearable.
alter table shop_products add column if not exists gift_id varchar(64) references gifts(id);
