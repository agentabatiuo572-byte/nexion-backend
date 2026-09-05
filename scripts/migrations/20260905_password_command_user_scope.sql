-- Upgrade the earlier session-scoped primary key without deleting receipts.
-- MySQL atomic DDL rejects duplicate (user_id, command_key) values and preserves
-- the old table; operators must resolve ambiguous receipts, never silently dedupe.
SET @password_receipt_pk = (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'nx_user_password_command' AND index_name = 'PRIMARY'
);
SET @password_receipt_upgrade = IF(
    @password_receipt_pk = 'user_id,command_key',
    'SELECT 1',
    IF(@password_receipt_pk IS NULL,
       'ALTER TABLE nx_user_password_command ADD PRIMARY KEY (user_id, command_key)',
       'ALTER TABLE nx_user_password_command DROP PRIMARY KEY, ADD PRIMARY KEY (user_id, command_key)')
);
PREPARE password_receipt_upgrade FROM @password_receipt_upgrade;
EXECUTE password_receipt_upgrade;
DEALLOCATE PREPARE password_receipt_upgrade;
