BEGIN;

DO $$
DECLARE
    payload_type text;
BEGIN
    SELECT udt_name
    INTO payload_type
    FROM information_schema.columns
    WHERE table_name = 'transaction_outbox'
      AND column_name = 'payload';

    IF payload_type IS NULL THEN
        RAISE EXCEPTION 'Column transaction_outbox.payload does not exist';
    END IF;

    IF payload_type = 'jsonb' THEN
        RAISE NOTICE 'transaction_outbox.payload is already jsonb';
        RETURN;
    END IF;

    ALTER TABLE transaction_outbox ADD COLUMN payload_jsonb jsonb;

    WITH source_rows AS (
        SELECT id, payload::text AS payload_text
        FROM transaction_outbox
    ),
    resolved_rows AS (
        SELECT
            id,
            CASE
                WHEN payload_text ~ '^[0-9]+$' THEN convert_from(lo_get(payload_text::oid), 'UTF8')
                ELSE payload_text
            END AS raw_payload
        FROM source_rows
    ),
    parsed_rows AS (
        SELECT
            id,
            CASE
                WHEN jsonb_typeof(raw_payload::jsonb) = 'string' THEN (raw_payload::jsonb #>> '{}')::jsonb
                ELSE raw_payload::jsonb
            END AS normalized_payload
        FROM resolved_rows
    )
    UPDATE transaction_outbox t
    SET payload_jsonb = p.normalized_payload
    FROM parsed_rows p
    WHERE p.id = t.id;

    ALTER TABLE transaction_outbox DROP COLUMN payload;
    ALTER TABLE transaction_outbox RENAME COLUMN payload_jsonb TO payload;
    ALTER TABLE transaction_outbox ALTER COLUMN payload SET NOT NULL;
END $$;

COMMIT;
