-- Forward repair for the G1/G7 H1 snapshot property revision split.
-- The H1 migration advanced the two registries without carrying their original
-- properties. Repair only those exact historical contracts. Later governance,
-- deleted properties, types, PII and required flags remain authoritative.
START TRANSACTION;

UPDATE nx_event_schema_property
   SET registry_revision=99,updated_at=CURRENT_TIMESTAMP
 WHERE schema_id IN (SELECT id FROM nx_event_schema_registry
                      WHERE event_name='staking.opened' AND current_revision=99
                        AND status='ACTIVE' AND is_deleted=0)
   AND registry_revision=62 AND is_deleted=0 AND pii=0 AND required_field=1
   AND (property_name,property_type) IN (
       ('position_no','id'),('tier_key','string'),('product_code','id'),
       ('amount_usdt','number'),('apy_pct','number'),('term_days','number'),
       ('unlock_at','timestamp'),('wallet_balance_usdt','number'));

UPDATE nx_event_schema_property
   SET registry_revision=98,updated_at=CURRENT_TIMESTAMP
 WHERE schema_id IN (SELECT id FROM nx_event_schema_registry
                      WHERE event_name='wallet.reinvest' AND current_revision=98
                        AND status='ACTIVE' AND is_deleted=0)
   AND registry_revision=72 AND is_deleted=0 AND pii=0 AND required_field=1
   AND (property_name,property_type) IN (
       ('position_no','id'),('product','enum'),('amount_usdt','number'),
       ('apy_pct','number'),('term_days','number'),('unlock_at','timestamp'),
       ('ticket_count','number'),('nurture_multiplier','number'),
       ('wallet_balance_usdt','number'));

COMMIT;
