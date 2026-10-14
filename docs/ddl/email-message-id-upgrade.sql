-- Existing PostgreSQL installations: pause enqueue and dispatch before applying.
-- SHA-256 is PostgreSQL's built-in bytea function; no extension is required.
BEGIN;
ALTER TABLE email_message ADD COLUMN rfc_message_id VARCHAR(500);
UPDATE email_message
SET rfc_message_id = '<' || left(encode(sha256(convert_to(
   coalesce(idempotency_key, 'qbit-email:' || id::text), 'UTF8')), 'hex'), 32)
   || '@' || coalesce(lower(substring(from_address from
      '@([A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)(?:>|$)')), 'qbit-email.local') || '>';
ALTER TABLE email_message ADD CONSTRAINT email_message_rfc_message_id_unique UNIQUE (rfc_message_id);
COMMIT;
