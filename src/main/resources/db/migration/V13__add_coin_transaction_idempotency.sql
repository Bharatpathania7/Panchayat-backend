ALTER TABLE coin_transactions
ADD COLUMN idempotency_key VARCHAR(100);

CREATE UNIQUE INDEX uk_coin_transactions_idempotency_key
ON coin_transactions(idempotency_key)
WHERE idempotency_key IS NOT NULL;