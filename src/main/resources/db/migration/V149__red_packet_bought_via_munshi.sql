-- RED_PACKET_BOUGHT only ever fires after a payment goes through, so it should come from Munshi ji
-- (the payments desk) like every other payment message, not Sarpanch. VIP_ACTIVATED stays as is:
-- admin-granted VIP uses the same template with no payment involved, so it can't move to Munshi ji.
UPDATE sarpanch_templates SET category = 'PAYMENT' WHERE key = 'RED_PACKET_BOUGHT' AND category = 'RED_PACKET';
