-- R1 additive contract migration; no live policy/activity or role grant is seeded.
-- Apply only through the controlled installer once S1 is frozen and S2 registers it.
-- MySQL 8.4; business IDs compare exactly. No version in reward/usage uniqueness.
CREATE TABLE IF NOT EXISTS nx_promotion (
  activity_id VARCHAR(64) NOT NULL PRIMARY KEY,
  event_code VARCHAR(96) NOT NULL,
  category VARCHAR(16) NOT NULL,
  template VARCHAR(32) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  active_version INT NULL,
  draft_version INT NULL,
  reserved_orders BIGINT NOT NULL DEFAULT 0,
  used_orders BIGINT NOT NULL DEFAULT 0,
  revision BIGINT NOT NULL DEFAULT 1,
  fixture_run_id VARCHAR(96) NOT NULL DEFAULT '',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_promotion_event (event_code),
  KEY idx_promotion_status (status, activity_id),
  CONSTRAINT chk_promotion_category CHECK (category IN ('PROMOTION','REFERRAL')),
  CONSTRAINT chk_promotion_template CHECK (template IN ('SKU_GIFT','FIRST_PURCHASE','DIRECT_REFERRAL','MULTI_PRODUCT','REPURCHASE')),
  CONSTRAINT chk_promotion_state CHECK (status IN ('DRAFT','SCHEDULED','ACTIVE','PAUSED','ENDED','ARCHIVED')),
  CONSTRAINT chk_promotion_revision CHECK (revision > 0),
  CONSTRAINT chk_promotion_activity_count CHECK (reserved_orders >= 0 AND used_orders >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS nx_promotion_policy (
  policy_id VARCHAR(64) NOT NULL,
  version INT NOT NULL,
  kind VARCHAR(32) NOT NULL,
  executor_code VARCHAR(64) NOT NULL,
  content_json JSON NOT NULL,
  content_hash CHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  revision BIGINT NOT NULL DEFAULT 1,
  approval_ref VARCHAR(96) NULL,
  approved_by BIGINT NULL,
  approved_at DATETIME(6) NULL,
  evidence_json JSON NOT NULL,
  fixture_run_id VARCHAR(96) NOT NULL DEFAULT '',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (policy_id, version),
  KEY idx_promotion_policy_kind (kind, status, policy_id, version),
  CONSTRAINT chk_promotion_policy_kind CHECK (kind IN ('FIRST_PURCHASE','DEVICE_AUDIENCE','DEVICE_RIGHTS','ASSET','SETTLEMENT','STACKING','REFUND','QUOTE','AUTHORIZATION')),
  CONSTRAINT chk_promotion_policy_status CHECK (status IN ('DRAFT','APPROVED','REVOKED')),
  CONSTRAINT chk_promotion_policy_approval CHECK (status <> 'APPROVED' OR (approval_ref IS NOT NULL AND approved_by IS NOT NULL AND approved_at IS NOT NULL)),
  CONSTRAINT chk_promotion_policy_version CHECK (version > 0 AND revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS nx_promotion_version (
  activity_id VARCHAR(64) NOT NULL,
  version INT NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  starts_at DATETIME(6) NULL,
  ends_at DATETIME(6) NULL,
  contract_json JSON NOT NULL,
  content_hash CHAR(64) NOT NULL,
  resolved_policies_json JSON NULL,
  approved_by BIGINT NULL,
  approved_at DATETIME(6) NULL,
  published_by BIGINT NULL,
  published_at DATETIME(6) NULL,
  approval_ref VARCHAR(96) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (activity_id, version),
  CONSTRAINT fk_promotion_version_root FOREIGN KEY (activity_id) REFERENCES nx_promotion(activity_id),
  CONSTRAINT chk_promotion_version_state CHECK (status IN ('DRAFT','PENDING_APPROVAL','APPROVED','PUBLISHED')),
  CONSTRAINT chk_promotion_version_time CHECK ((starts_at IS NULL OR ends_at IS NULL OR ends_at > starts_at) AND (status='DRAFT' OR (starts_at IS NOT NULL AND ends_at IS NOT NULL AND ends_at > starts_at))),
  CONSTRAINT chk_promotion_version_revision CHECK (version > 0 AND revision > 0),
  CONSTRAINT chk_promotion_version_approval CHECK (status NOT IN ('APPROVED','PUBLISHED') OR (approved_by IS NOT NULL AND approved_at IS NOT NULL AND approval_ref IS NOT NULL)),
  CONSTRAINT chk_promotion_version_publish CHECK (status <> 'PUBLISHED' OR (published_by IS NOT NULL AND published_at IS NOT NULL AND resolved_policies_json IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Stable identities survive revisions, retired rules and new activity versions.
CREATE TABLE IF NOT EXISTS nx_promotion_rule_identity (
  activity_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  product_no VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (activity_id, rule_id),
  CONSTRAINT fk_promotion_rule_root FOREIGN KEY (activity_id) REFERENCES nx_promotion(activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS nx_promotion_reward_identity (
  activity_id VARCHAR(64) NOT NULL,
  reward_rule_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  beneficiary_role VARCHAR(16) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (activity_id, reward_rule_id),
  UNIQUE KEY uk_promotion_rule_role (activity_id, rule_id, beneficiary_role),
  CONSTRAINT fk_promotion_reward_rule FOREIGN KEY (activity_id, rule_id) REFERENCES nx_promotion_rule_identity(activity_id, rule_id),
  CONSTRAINT chk_promotion_reward_role CHECK (beneficiary_role IN ('BUYER','DIRECT_INVITER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS nx_promotion_budget (
  activity_id VARCHAR(64) NOT NULL,
  asset VARCHAR(16) NOT NULL,
  product_no VARCHAR(64) NOT NULL DEFAULT '',
  total DECIMAL(18,6) NOT NULL,
  reserved DECIMAL(18,6) NOT NULL DEFAULT 0,
  committed DECIMAL(18,6) NOT NULL DEFAULT 0,
  issued DECIMAL(18,6) NOT NULL DEFAULT 0,
  reversed DECIMAL(18,6) NOT NULL DEFAULT 0,
  unrecoverable DECIMAL(18,6) NOT NULL DEFAULT 0,
  revision BIGINT NOT NULL DEFAULT 1,
  PRIMARY KEY (activity_id, asset, product_no),
  CONSTRAINT fk_promotion_budget_root FOREIGN KEY (activity_id) REFERENCES nx_promotion(activity_id),
  CONSTRAINT chk_promotion_budget_kind CHECK ((asset IN ('USDT','NEX') AND product_no='') OR (asset='DEVICE' AND product_no<>'')),
  CONSTRAINT chk_promotion_budget_nonnegative CHECK (total >= 0 AND reserved >= 0 AND committed >= 0 AND issued >= 0 AND reversed >= 0 AND unrecoverable >= 0),
  CONSTRAINT chk_promotion_budget_available CHECK (total - reserved - committed - issued + reversed >= 0),
  CONSTRAINT chk_promotion_budget_loss CHECK (reversed <= issued AND unrecoverable <= issued - reversed),
  CONSTRAINT chk_promotion_budget_devices CHECK (asset <> 'DEVICE' OR (total=FLOOR(total) AND reserved=FLOOR(reserved) AND committed=FLOOR(committed) AND issued=FLOOR(issued) AND reversed=FLOOR(reversed) AND unrecoverable=FLOOR(unrecoverable)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS nx_promotion_quote (
  quote_id VARCHAR(64) NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  request_hash CHAR(64) NOT NULL,
  quote_hash CHAR(64) NOT NULL,
  quote_json JSON NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_promotion_quote_expiry (expires_at),
  KEY idx_promotion_quote_owner (user_id, quote_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- One row per role/order for limits; one row per role/rule for group limits. No version reset.
CREATE TABLE IF NOT EXISTS nx_promotion_usage (
  activity_id VARCHAR(64) NOT NULL,
  account_id BIGINT NOT NULL,
  beneficiary_role VARCHAR(16) NOT NULL,
  rule_id VARCHAR(64) NOT NULL DEFAULT '',
  reserved_orders BIGINT NOT NULL DEFAULT 0,
  used_orders BIGINT NOT NULL DEFAULT 0,
  reserved_groups BIGINT NOT NULL DEFAULT 0,
  used_groups BIGINT NOT NULL DEFAULT 0,
  revision BIGINT NOT NULL DEFAULT 1,
  PRIMARY KEY (activity_id, account_id, beneficiary_role, rule_id),
  CONSTRAINT fk_promotion_usage_root FOREIGN KEY (activity_id) REFERENCES nx_promotion(activity_id),
  CONSTRAINT chk_promotion_usage_counts CHECK (reserved_orders>=0 AND used_orders>=0 AND reserved_groups>=0 AND used_groups>=0),
  CONSTRAINT chk_promotion_usage_role CHECK (beneficiary_role IN ('BUYER','DIRECT_INVITER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS nx_promotion_reservation (
  reservation_id VARCHAR(64) NOT NULL PRIMARY KEY,
  activity_id VARCHAR(64) NOT NULL,
  version INT NOT NULL,
  order_no VARCHAR(96) NOT NULL,
  order_line_id BIGINT NOT NULL,
  buyer_id BIGINT NOT NULL,
  beneficiary_id BIGINT NOT NULL,
  beneficiary_role VARCHAR(16) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  reward_rule_id VARCHAR(64) NOT NULL,
  unit_seq INT NOT NULL,
  asset VARCHAR(16) NOT NULL,
  product_no VARCHAR(64) NOT NULL DEFAULT '',
  amount DECIMAL(18,6) NOT NULL,
  snapshot_json JSON NOT NULL,
  snapshot_hash CHAR(64) NOT NULL,
  pay_by DATETIME(6) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'RESERVED',
  revision BIGINT NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_promotion_reservation_unit (activity_id, order_line_id, beneficiary_id, beneficiary_role, reward_rule_id, unit_seq),
  KEY idx_promotion_reservation_order (order_no, reservation_id),
  KEY idx_promotion_reservation_expiry (status, pay_by),
  CONSTRAINT fk_promotion_reservation_version FOREIGN KEY (activity_id, version) REFERENCES nx_promotion_version(activity_id, version),
  CONSTRAINT chk_promotion_reservation_state CHECK (status IN ('RESERVED','COMMITTED','RELEASED')),
  CONSTRAINT chk_promotion_reservation_amount CHECK (amount > 0 AND unit_seq >= 0),
  CONSTRAINT chk_promotion_reservation_asset CHECK ((asset IN ('USDT','NEX') AND product_no='') OR (asset='DEVICE' AND product_no<>'' AND amount=FLOOR(amount)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS nx_promotion_reward (
  obligation_id VARCHAR(64) NOT NULL PRIMARY KEY,
  reservation_id VARCHAR(64) NOT NULL,
  activity_id VARCHAR(64) NOT NULL,
  version INT NOT NULL,
  order_no VARCHAR(96) NOT NULL,
  order_line_id BIGINT NOT NULL,
  beneficiary_id BIGINT NOT NULL,
  beneficiary_role VARCHAR(16) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  reward_rule_id VARCHAR(64) NOT NULL,
  unit_seq INT NOT NULL,
  snapshot_json JSON NOT NULL,
  snapshot_hash CHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  revision BIGINT NOT NULL DEFAULT 1,
  ready_at DATETIME(6) NULL,
  original_ledger_no VARCHAR(160) NULL,
  original_earnings_entry_no VARCHAR(64) NULL,
  last_command_id VARCHAR(96) NULL,
  retry_reason VARCHAR(500) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_promotion_obligation (activity_id, order_line_id, beneficiary_id, beneficiary_role, reward_rule_id, unit_seq),
  UNIQUE KEY uk_promotion_reward_reservation (reservation_id),
  KEY idx_promotion_reward_order (order_no, obligation_id),
  KEY idx_promotion_reward_owner (beneficiary_id, created_at, obligation_id),
  KEY idx_promotion_reward_dispatch (status, ready_at, obligation_id),
  CONSTRAINT fk_promotion_reward_reservation FOREIGN KEY (reservation_id) REFERENCES nx_promotion_reservation(reservation_id),
  CONSTRAINT chk_promotion_reward_state CHECK (status IN ('PENDING','READY','PROCESSING','ISSUED','RETRYABLE_FAILED','OUTCOME_UNKNOWN','CANCELLED','REVERSAL_PENDING','REVERSED','MANUAL_REVIEW')),
  CONSTRAINT chk_promotion_reward_sequence CHECK (unit_seq >= 0 AND revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Keeps each real device mapped without putting gift devices in paid order items.
CREATE TABLE IF NOT EXISTS nx_promotion_device_receipt (
  obligation_id VARCHAR(64) NOT NULL,
  device_unit_seq INT NOT NULL,
  device_id BIGINT NOT NULL,
  instance_no VARCHAR(96) NOT NULL,
  profile_id VARCHAR(64) NOT NULL,
  profile_version INT NOT NULL,
  rights_snapshot_json JSON NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (obligation_id, device_unit_seq),
  UNIQUE KEY uk_promotion_device (device_id),
  UNIQUE KEY uk_promotion_device_instance (instance_no),
  KEY idx_promotion_device_profile (profile_id, profile_version),
  CONSTRAINT fk_promotion_device_reward FOREIGN KEY (obligation_id) REFERENCES nx_promotion_reward(obligation_id),
  CONSTRAINT fk_promotion_device_actual FOREIGN KEY (device_id) REFERENCES nx_user_device(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_promotion_device_profile FOREIGN KEY (profile_id, profile_version) REFERENCES nx_promotion_policy(policy_id, version) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT chk_promotion_device_sequence CHECK (device_unit_seq >= 0 AND profile_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS nx_promotion_refund_hold (
  refund_request_id VARCHAR(96) NOT NULL PRIMARY KEY,
  order_no VARCHAR(96) NOT NULL,
  source_type VARCHAR(16) NOT NULL,
  source_id VARCHAR(96) NOT NULL,
  source_event_id VARCHAR(96) NOT NULL,
  status VARCHAR(24) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  refund_no VARCHAR(96) NULL,
  refund_ledger_biz_no VARCHAR(160) NULL,
  evidence_json JSON NOT NULL,
  reason VARCHAR(500) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_promotion_refund_source (source_type, source_id),
  KEY idx_promotion_refund_order (order_no, status, refund_request_id),
  CONSTRAINT chk_promotion_hold_state CHECK (status IN ('HELD','RELEASED','EXECUTED','OUTCOME_UNKNOWN')),
  CONSTRAINT chk_promotion_hold_source CHECK (source_type IN ('A2_OPERATION','E4_REFUND')),
  CONSTRAINT chk_promotion_hold_executed CHECK (status <> 'EXECUTED' OR (refund_no IS NOT NULL AND refund_ledger_biz_no IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS nx_promotion_reversal (
  reversal_id VARCHAR(64) NOT NULL PRIMARY KEY,
  obligation_id VARCHAR(64) NOT NULL,
  basis_type VARCHAR(32) NOT NULL,
  basis_ref VARCHAR(96) NOT NULL,
  refund_no VARCHAR(96) NULL,
  approval_operation_id VARCHAR(96) NULL,
  asset VARCHAR(16) NOT NULL,
  amount DECIMAL(18,6) NOT NULL,
  recovered DECIMAL(18,6) NOT NULL DEFAULT 0,
  outstanding DECIMAL(18,6) NOT NULL,
  reusable DECIMAL(18,6) NOT NULL DEFAULT 0,
  original_posting_no VARCHAR(160) NOT NULL,
  reversal_posting_no VARCHAR(160) NULL,
  status VARCHAR(32) NOT NULL,
  evidence_json JSON NOT NULL,
  reason VARCHAR(500) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_promotion_reversal (obligation_id, refund_no),
  UNIQUE KEY uk_promotion_disposition (obligation_id, basis_type, basis_ref),
  CONSTRAINT fk_promotion_reversal_reward FOREIGN KEY (obligation_id) REFERENCES nx_promotion_reward(obligation_id),
  CONSTRAINT chk_promotion_reversal_state CHECK (status IN ('REVERSAL_PENDING','REVERSED','MANUAL_REVIEW')),
  CONSTRAINT chk_promotion_reversal_asset CHECK (asset IN ('DEVICE','USDT','NEX')),
  CONSTRAINT chk_promotion_reversal_devices CHECK (asset <> 'DEVICE' OR (amount=FLOOR(amount) AND recovered=FLOOR(recovered) AND outstanding=FLOOR(outstanding) AND reusable=FLOOR(reusable))),
  CONSTRAINT chk_promotion_reversal_basis CHECK ((basis_type='WHOLE_ORDER_REFUND' AND refund_no IS NOT NULL AND basis_ref=refund_no AND approval_operation_id IS NULL) OR (basis_type='APPROVED_CORRECTION' AND refund_no IS NULL AND approval_operation_id IS NOT NULL AND basis_ref=approval_operation_id)),
  CONSTRAINT chk_promotion_reversal_amount CHECK (amount > 0 AND recovered >= 0 AND outstanding >= 0 AND recovered + outstanding = amount AND reusable >= 0 AND reusable <= recovered)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS nx_promotion_reward_attempt (
  attempt_id VARCHAR(64) NOT NULL PRIMARY KEY,
  obligation_id VARCHAR(64) NOT NULL,
  command_id VARCHAR(96) NOT NULL,
  action VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  evidence_json JSON NOT NULL,
  started_at DATETIME(6) NOT NULL,
  finished_at DATETIME(6) NULL,
  KEY idx_promotion_attempt_reward (obligation_id, started_at, attempt_id),
  UNIQUE KEY uk_promotion_attempt_command (obligation_id, command_id, action),
  CONSTRAINT fk_promotion_attempt_reward FOREIGN KEY (obligation_id) REFERENCES nx_promotion_reward(obligation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS nx_promotion_report_snapshot (
  snapshot_id VARCHAR(64) NOT NULL PRIMARY KEY,
  activity_id VARCHAR(64) NOT NULL,
  actor_id BIGINT NOT NULL,
  query_json JSON NOT NULL,
  metrics_json JSON NOT NULL,
  source_watermarks_json JSON NOT NULL,
  row_manifest_json JSON NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  expires_at DATETIME(6) NOT NULL,
  KEY idx_promotion_report_owner (actor_id, activity_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS nx_promotion_export_job (
  export_id VARCHAR(64) NOT NULL PRIMARY KEY,
  snapshot_id VARCHAR(64) NOT NULL,
  actor_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  storage_ref VARCHAR(512) NULL,
  row_count BIGINT NULL,
  content_hash CHAR(64) NULL,
  failure_reason VARCHAR(500) NULL,
  expires_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_promotion_export_snapshot FOREIGN KEY (snapshot_id) REFERENCES nx_promotion_report_snapshot(snapshot_id),
  CONSTRAINT chk_promotion_export_state CHECK (status IN ('PROCESSING','READY','FAILED','EXPIRED')),
  CONSTRAINT chk_promotion_export_ready CHECK (status <> 'READY' OR (storage_ref IS NOT NULL AND row_count IS NOT NULL AND content_hash IS NOT NULL AND expires_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Register independent capabilities; existing role grants remain unchanged.
INSERT IGNORE INTO nx_admin_permission (permission_code,permission_name,resource_type,resource_path,remark,status,is_deleted,perm_type) VALUES
('growth_promotion_approve','growth_promotion_approve','API','/api/admin/growth/**','R1 promotion capability; explicit A1 role assignment required',1,0,'HIGH'),
('growth_promotion_archive','growth_promotion_archive','API','/api/admin/growth/**','R1 promotion capability; explicit A1 role assignment required',1,0,'HIGH'),
('growth_promotion_edit','growth_promotion_edit','API','/api/admin/growth/**','R1 promotion capability; explicit A1 role assignment required',1,0,'WRITE'),
('growth_promotion_end','growth_promotion_end','API','/api/admin/growth/**','R1 promotion capability; explicit A1 role assignment required',1,0,'HIGH'),
('growth_promotion_metrics_export','growth_promotion_metrics_export','API','/api/admin/growth/**','R1 promotion capability; explicit A1 role assignment required',1,0,'HIGH'),
('growth_promotion_metrics_read','growth_promotion_metrics_read','API','/api/admin/growth/**','R1 promotion capability; explicit A1 role assignment required',1,0,'READ'),
('growth_promotion_pause','growth_promotion_pause','API','/api/admin/growth/**','R1 promotion capability; explicit A1 role assignment required',1,0,'HIGH'),
('growth_promotion_policy_approve','growth_promotion_policy_approve','API','/api/admin/growth/**','R1 promotion capability; explicit A1 role assignment required',1,0,'HIGH'),
('growth_promotion_policy_read','growth_promotion_policy_read','API','/api/admin/growth/**','R1 promotion capability; explicit A1 role assignment required',1,0,'READ'),
('growth_promotion_policy_write','growth_promotion_policy_write','API','/api/admin/growth/**','R1 promotion capability; explicit A1 role assignment required',1,0,'WRITE'),
('growth_promotion_publish','growth_promotion_publish','API','/api/admin/growth/**','R1 promotion capability; explicit A1 role assignment required',1,0,'HIGH'),
('growth_promotion_read','growth_promotion_read','API','/api/admin/growth/**','R1 promotion capability; explicit A1 role assignment required',1,0,'READ'),
('growth_promotion_reward_cancel','growth_promotion_reward_cancel','API','/api/admin/growth/**','R1 promotion capability; explicit A1 role assignment required',1,0,'HIGH'),
('growth_promotion_reward_read','growth_promotion_reward_read','API','/api/admin/growth/**','R1 promotion capability; explicit A1 role assignment required',1,0,'READ'),
('growth_promotion_reward_reconcile','growth_promotion_reward_reconcile','API','/api/admin/growth/**','R1 promotion capability; explicit A1 role assignment required',1,0,'HIGH'),
('growth_promotion_reward_resolve','growth_promotion_reward_resolve','API','/api/admin/growth/**','R1 promotion capability; explicit A1 role assignment required',1,0,'HIGH'),
('growth_promotion_reward_retry','growth_promotion_reward_retry','API','/api/admin/growth/**','R1 promotion capability; explicit A1 role assignment required',1,0,'HIGH'),
('growth_promotion_reward_reverse','growth_promotion_reward_reverse','API','/api/admin/growth/**','R1 promotion capability; explicit A1 role assignment required',1,0,'HIGH'),
('growth_promotion_simulate','growth_promotion_simulate','API','/api/admin/growth/**','R1 promotion capability; explicit A1 role assignment required',1,0,'READ'),
('growth_promotion_submit','growth_promotion_submit','API','/api/admin/growth/**','R1 promotion capability; explicit A1 role assignment required',1,0,'WRITE');

-- Register metadata only; existing A1 role assignments and grants are untouched.
UPDATE nx_admin_permission SET perm_type = CASE permission_code
  WHEN 'growth_promotion_approve' THEN 'HIGH'
  WHEN 'growth_promotion_archive' THEN 'HIGH'
  WHEN 'growth_promotion_edit' THEN 'WRITE'
  WHEN 'growth_promotion_end' THEN 'HIGH'
  WHEN 'growth_promotion_metrics_export' THEN 'HIGH'
  WHEN 'growth_promotion_metrics_read' THEN 'READ'
  WHEN 'growth_promotion_pause' THEN 'HIGH'
  WHEN 'growth_promotion_policy_approve' THEN 'HIGH'
  WHEN 'growth_promotion_policy_read' THEN 'READ'
  WHEN 'growth_promotion_policy_write' THEN 'WRITE'
  WHEN 'growth_promotion_publish' THEN 'HIGH'
  WHEN 'growth_promotion_read' THEN 'READ'
  WHEN 'growth_promotion_reward_cancel' THEN 'HIGH'
  WHEN 'growth_promotion_reward_read' THEN 'READ'
  WHEN 'growth_promotion_reward_reconcile' THEN 'HIGH'
  WHEN 'growth_promotion_reward_resolve' THEN 'HIGH'
  WHEN 'growth_promotion_reward_retry' THEN 'HIGH'
  WHEN 'growth_promotion_reward_reverse' THEN 'HIGH'
  WHEN 'growth_promotion_simulate' THEN 'READ'
  WHEN 'growth_promotion_submit' THEN 'WRITE'
END WHERE permission_code IN (
  'growth_promotion_approve',
  'growth_promotion_archive',
  'growth_promotion_edit',
  'growth_promotion_end',
  'growth_promotion_metrics_export',
  'growth_promotion_metrics_read',
  'growth_promotion_pause',
  'growth_promotion_policy_approve',
  'growth_promotion_policy_read',
  'growth_promotion_policy_write',
  'growth_promotion_publish',
  'growth_promotion_read',
  'growth_promotion_reward_cancel',
  'growth_promotion_reward_read',
  'growth_promotion_reward_reconcile',
  'growth_promotion_reward_resolve',
  'growth_promotion_reward_retry',
  'growth_promotion_reward_reverse',
  'growth_promotion_simulate',
  'growth_promotion_submit'
);
