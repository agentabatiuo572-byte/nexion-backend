-- New account creation attests a capture lifetime; existing accounts are never backfilled.
CREATE TABLE IF NOT EXISTS nx_support_payment_history_birth (
 customer_id BIGINT NOT NULL PRIMARY KEY,
 capture_protocol VARCHAR(64) NOT NULL,
 birth_origin VARCHAR(96) NOT NULL,
 birth_db_utc DATETIME(6) NOT NULL,
 sandbox_at_birth TINYINT NULL,
 environment_status VARCHAR(16) NOT NULL,
 CONSTRAINT ck_support_payment_history_birth_customer CHECK(customer_id>0),
 CONSTRAINT ck_support_payment_history_birth_environment CHECK(environment_status IN ('PRODUCTION','EXCLUDED','UNKNOWN'))
) ENGINE=InnoDB;
