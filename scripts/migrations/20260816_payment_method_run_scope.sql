-- Payment methods are account facts, but sandbox accounts also need a server-owned
-- fixture dimension.  A sandbox run must never see, reactivate, or revoke a card
-- from another run.  Empty run_id is the canonical production scope.
SET @wallet_card_run_sql = IF(
  EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_wallet_bank_card' AND COLUMN_NAME='run_id'),
  'SELECT 1',
  'ALTER TABLE nx_wallet_bank_card ADD COLUMN run_id VARCHAR(64) NOT NULL DEFAULT '''' AFTER source_environment'
);
PREPARE wallet_card_run_stmt FROM @wallet_card_run_sql; EXECUTE wallet_card_run_stmt; DEALLOCATE PREPARE wallet_card_run_stmt;

SET @wallet_card_scope_index_sql = IF(
  EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_wallet_bank_card' AND INDEX_NAME='uk_wallet_card_scope_token'),
  'SELECT 1',
  'ALTER TABLE nx_wallet_bank_card ADD UNIQUE KEY uk_wallet_card_scope_token (source_environment,run_id,card_token)'
);
PREPARE wallet_card_scope_index_stmt FROM @wallet_card_scope_index_sql; EXECUTE wallet_card_scope_index_stmt; DEALLOCATE PREPARE wallet_card_scope_index_stmt;

SET @wallet_card_legacy_index_sql = IF(
  EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_wallet_bank_card' AND INDEX_NAME='uk_wallet_card_token'),
  'ALTER TABLE nx_wallet_bank_card DROP INDEX uk_wallet_card_token',
  'SELECT 1'
);
PREPARE wallet_card_legacy_index_stmt FROM @wallet_card_legacy_index_sql; EXECUTE wallet_card_legacy_index_stmt; DEALLOCATE PREPARE wallet_card_legacy_index_stmt;

SET @revoke_run_sql = IF(
  EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payment_method_revoke_command' AND COLUMN_NAME='run_id'),
  'SELECT 1',
  'ALTER TABLE nx_payment_method_revoke_command ADD COLUMN run_id VARCHAR(64) NOT NULL DEFAULT '''' AFTER source_environment'
);
PREPARE revoke_run_stmt FROM @revoke_run_sql; EXECUTE revoke_run_stmt; DEALLOCATE PREPARE revoke_run_stmt;

SET @revoke_scope_index_sql = IF(
  EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payment_method_revoke_command' AND INDEX_NAME='uk_payment_method_revoke_scope_method'),
  'SELECT 1',
  'ALTER TABLE nx_payment_method_revoke_command ADD UNIQUE KEY uk_payment_method_revoke_scope_method (payment_method_id,source_environment,run_id)'
);
PREPARE revoke_scope_index_stmt FROM @revoke_scope_index_sql; EXECUTE revoke_scope_index_stmt; DEALLOCATE PREPARE revoke_scope_index_stmt;

SET @revoke_legacy_index_sql = IF(
  EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payment_method_revoke_command' AND INDEX_NAME='uk_payment_method_revoke_method'),
  'ALTER TABLE nx_payment_method_revoke_command DROP INDEX uk_payment_method_revoke_method',
  'SELECT 1'
);
PREPARE revoke_legacy_index_stmt FROM @revoke_legacy_index_sql; EXECUTE revoke_legacy_index_stmt; DEALLOCATE PREPARE revoke_legacy_index_stmt;
