-- A4 has one canonical risk family named `risk`. Repair legacy C4/C5 schemas
-- and already-published facts that were incorrectly assigned to `phase_risk`.
UPDATE nx_event_schema_registry
   SET family_key='risk', updated_at=NOW()
 WHERE family_key='phase_risk';

UPDATE nx_event_outbox
   SET family_key='risk', updated_at=NOW()
 WHERE family_key='phase_risk';
