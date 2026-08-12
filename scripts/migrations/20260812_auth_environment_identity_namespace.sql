-- Authentication identity namespace: a phone can exist independently in SANDBOX and PRODUCTION.
-- Legacy registration OTP records have no trustworthy audience and remain deliberately unverifiable.
SET @drop_phone_unique := IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='nx_user' AND index_name='uk_user_phone')>0,
  'ALTER TABLE nx_user DROP INDEX uk_user_phone', 'SELECT 1');
PREPARE stmt FROM @drop_phone_unique; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @add_phone_environment_unique := IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='nx_user' AND index_name='uk_user_phone_sandbox')=0,
  'ALTER TABLE nx_user ADD UNIQUE KEY uk_user_phone_sandbox (country_code,phone,sandbox)', 'SELECT 1');
PREPARE stmt FROM @add_phone_environment_unique; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_registration_otp_environment := IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_user_registration_otp' AND column_name='auth_environment')=0,
  'ALTER TABLE nx_user_registration_otp ADD COLUMN auth_environment VARCHAR(16) NOT NULL DEFAULT ''LEGACY'' AFTER client_ip', 'SELECT 1');
PREPARE stmt FROM @add_registration_otp_environment; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @add_registration_otp_environment_index := IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='nx_user_registration_otp' AND index_name='idx_user_registration_otp_phone_environment')=0,
  'ALTER TABLE nx_user_registration_otp ADD INDEX idx_user_registration_otp_phone_environment (country_code,phone,auth_environment,expires_at,consumed_at)', 'SELECT 1');
PREPARE stmt FROM @add_registration_otp_environment_index; EXECUTE stmt; DEALLOCATE PREPARE stmt;
