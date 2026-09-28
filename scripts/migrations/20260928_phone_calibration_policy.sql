-- Installation identities must compare exactly across calibration, native proof and binding.
ALTER TABLE nx_onboarding_calibration
  MODIFY COLUMN device_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL;
-- Phone binding survives recalibration, deactivation, logout and app reinstall.
CREATE TABLE IF NOT EXISTS nx_phone_binding (
  user_id BIGINT NOT NULL,
  source_environment VARCHAR(16) NOT NULL,
  run_id VARCHAR(96) NOT NULL DEFAULT '',
  installation_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  execution_installation_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  user_device_id BIGINT NOT NULL,
  hardware_key VARCHAR(64) NOT NULL DEFAULT '',
  changed_at DATETIME(6) NOT NULL,
  version BIGINT NOT NULL DEFAULT 1,
  PRIMARY KEY (user_id,source_environment,run_id),
  KEY idx_phone_binding_device (user_device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE nx_phone_binding
  MODIFY COLUMN installation_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL;

-- Preserve the most recently activated legacy binding; never overwrite an existing new binding.
INSERT IGNORE INTO nx_phone_binding
  (user_id,source_environment,run_id,installation_id,execution_installation_id,user_device_id,hardware_key,changed_at,version)
SELECT user_id,source_environment,run_id,device_id,device_id,user_device_id,'',changed_at,1
FROM (
  SELECT c.user_id,c.source_environment,c.run_id,c.device_id,c.user_device_id,
         COALESCE(d.activated_at,d.deactivated_at,c.updated_at) changed_at,
         ROW_NUMBER() OVER (PARTITION BY c.user_id,c.source_environment,c.run_id
                           ORDER BY (c.activation_status='ACTIVE' AND d.status IN ('ACTIVE','ONLINE')
                                     AND d.activated_at IS NOT NULL AND d.deactivated_at IS NULL) DESC,
                                    COALESCE(d.activated_at,d.deactivated_at) DESC,d.id DESC) sequence_no
    FROM nx_onboarding_calibration c
    JOIN nx_user_device d ON d.id=c.user_device_id AND d.user_id=c.user_id AND d.is_deleted=0
   WHERE c.is_deleted=0 AND c.user_device_id IS NOT NULL
     AND d.source_channel='ONBOARDING' AND d.source_environment=c.source_environment AND d.run_id=c.run_id
     AND d.device_type IN ('MOBILE','PHONE') AND d.ownership_status='OWNED'
) existing WHERE sequence_no=1;
CREATE TABLE IF NOT EXISTS nx_phone_native_session (
  user_id BIGINT NOT NULL,
  session_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  installation_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  nonce VARCHAR(64) NOT NULL,
  payload VARCHAR(1024) NOT NULL,
  challenge_expires_at BIGINT NOT NULL,
  verified_until BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id,session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_phone_installation_key (
  user_id BIGINT NOT NULL,
  installation_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  key_hash CHAR(64) NOT NULL,
  PRIMARY KEY (user_id,installation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
