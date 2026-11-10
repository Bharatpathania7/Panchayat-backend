-- Shape of a gift's animation in the app (width / height). Empty = full screen; set = floats across the width at that shape.
alter table gifts add column if not exists animation_ratio varchar(20);
-- Bride bouquet plays at the same size as Savanna King
update gifts set animation_ratio = '510 / 534' where lower(name) like 'bride bouquet%' and animation_ratio is null;
