-- Admin switch: online payment (PayU) temporarily closed. While closed, the app sends every top-up / VIP / pack
-- to Munshi ji (manual UPI) and the server refuses to start a PayU payment.
ALTER TABLE manual_topup_settings ADD COLUMN IF NOT EXISTS gateway_closed BOOLEAN NOT NULL DEFAULT FALSE;
