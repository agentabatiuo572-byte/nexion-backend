-- Structure only: never infer a historical segment policy or rewrite a customer binding/pool.
CREATE TABLE IF NOT EXISTS nx_conversation_timeout_segment (
  conversation_no VARCHAR(40) PRIMARY KEY,
  policy_version BIGINT NOT NULL,
  warn_minutes INT NOT NULL,
  close_minutes INT NOT NULL,
  CONSTRAINT chk_support_segment_policy CHECK (policy_version>0 AND warn_minutes BETWEEN 1 AND 30
    AND close_minutes BETWEEN 2 AND 120 AND close_minutes>warn_minutes)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- The reviewed S3 prerequisite owns row creation. Missing rows/columns are not UNCONFIGURED.
ALTER TABLE nx_support_rules ALTER COLUMN inheritance_mode SET DEFAULT 'UNLIMITED';
