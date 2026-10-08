-- A source success and its ownership evidence are inserted in the same transaction.
-- Existing payments have no inferred ownership; this migration creates structure only.
CREATE TABLE IF NOT EXISTS nx_support_payment_attribution (
 fact_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
 customer_id BIGINT NOT NULL, kind VARCHAR(32) NOT NULL, source VARCHAR(32) NOT NULL,
 ledger_id BIGINT NOT NULL, source_business_id VARCHAR(128) NOT NULL,
 order_no VARCHAR(128) NULL, order_type VARCHAR(64) NULL, original_fact_id VARCHAR(128) NULL,
 currency VARCHAR(16) NOT NULL, amount DECIMAL(18,6) NOT NULL,
 succeeded_at DATETIME(6) NOT NULL, source_business_zone VARCHAR(64) NOT NULL,
 success_time_field VARCHAR(96) NOT NULL, fractional_second_digits TINYINT NOT NULL, source_partition VARCHAR(128) NULL,
 capture_db_utc DATETIME(6) NOT NULL,
 agent_admin_id BIGINT NULL, group_id BIGINT NULL, owner_admin_id BIGINT NULL,
 agent_status VARCHAR(16) NOT NULL, group_status VARCHAR(16) NOT NULL, owner_status VARCHAR(16) NOT NULL,
 capture_mode VARCHAR(16) NOT NULL, capture_schema_version VARCHAR(64) NOT NULL,
 source_fact_json JSON NOT NULL, attribution_evidence_json JSON NOT NULL,
 CONSTRAINT ck_support_payment_attribution_positive CHECK(amount>0 AND ledger_id>0 AND customer_id>0),
 CONSTRAINT ck_support_payment_attribution_precision CHECK(fractional_second_digits BETWEEN 0 AND 6),
 CONSTRAINT ck_support_payment_attribution_mode CHECK(capture_mode IN ('NEW_SUCCESS','OLD_SOURCE')),
 CONSTRAINT ck_support_payment_attribution_old CHECK(capture_mode<>'OLD_SOURCE' OR (agent_status='UNKNOWN' AND group_status='UNKNOWN' AND owner_status='UNKNOWN')),
 CONSTRAINT ck_support_payment_attribution_agent CHECK((agent_status='KNOWN' AND agent_admin_id IS NOT NULL AND agent_admin_id>0) OR (agent_status IN ('UNKNOWN','UNASSIGNED') AND agent_admin_id IS NULL)),
 CONSTRAINT ck_support_payment_attribution_group CHECK((group_status='KNOWN' AND group_id IS NOT NULL AND group_id>0) OR (group_status IN ('UNKNOWN','UNASSIGNED') AND group_id IS NULL)),
 CONSTRAINT ck_support_payment_attribution_owner CHECK((owner_status='KNOWN' AND owner_admin_id IS NOT NULL AND owner_admin_id>0 AND group_status='KNOWN') OR (owner_status IN ('UNKNOWN','UNASSIGNED') AND owner_admin_id IS NULL)),
 KEY ix_support_payment_attribution_customer(customer_id,fact_id),
 KEY ix_support_payment_attribution_original(original_fact_id)
) ENGINE=InnoDB;
