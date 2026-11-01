-- E2 runtime policy is separate from legacy task rows to preserve v1 constructor/SQL compatibility.
CREATE TABLE IF NOT EXISTS nx_compute_task_policy_v2 (
  task_id VARCHAR(64) NOT NULL,
  selection_weight INT NOT NULL DEFAULT 1,
  expected_duration_min_sec INT NOT NULL,
  expected_duration_max_sec INT NOT NULL,
  execution_timeout_sec INT NOT NULL,
  runtime_key VARCHAR(96) NOT NULL DEFAULT '',
  validator_key VARCHAR(96) NOT NULL DEFAULT '',
  workload_config_json JSON NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  PRIMARY KEY(task_id),
  CONSTRAINT chk_compute_v2_policy_weight CHECK(selection_weight BETWEEN 1 AND 1000000),
  CONSTRAINT chk_compute_v2_policy_duration CHECK(expected_duration_min_sec BETWEEN 1 AND 86400
    AND expected_duration_max_sec BETWEEN expected_duration_min_sec AND 86400
    AND execution_timeout_sec BETWEEN expected_duration_max_sec AND 172800)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
