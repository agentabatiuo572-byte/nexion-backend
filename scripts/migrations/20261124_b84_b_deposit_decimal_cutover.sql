-- Run after old writers stop, with the session in the application's storage timezone.
CREATE TEMPORARY TABLE b84_storage_offset_check (
    storage_offset VARCHAR(6) NOT NULL CHECK (storage_offset IN ('+07:00', '+08:00'))
);
INSERT INTO b84_storage_offset_check VALUES (@@session.time_zone);
DROP TEMPORARY TABLE b84_storage_offset_check;

CREATE TABLE IF NOT EXISTS nx_cregis_deposit_decimal_cutover (
    id TINYINT NOT NULL PRIMARY KEY CHECK (id = 1),
    effective_at DATETIME(6) NOT NULL
);

INSERT INTO nx_cregis_deposit_decimal_cutover (id, effective_at)
VALUES (1, CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE effective_at = effective_at;

SELECT @@session.time_zone AS storage_offset, effective_at
  FROM nx_cregis_deposit_decimal_cutover WHERE id = 1;
