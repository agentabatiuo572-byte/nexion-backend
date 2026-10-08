-- Read-only, actor-owned activity-list snapshots; no activity or approval is seeded.
CREATE TABLE IF NOT EXISTS nx_promotion_list_snapshot (
  snapshot_id VARCHAR(64) NOT NULL PRIMARY KEY,
  actor_id BIGINT NOT NULL,
  query_json JSON NOT NULL,
  rows_json JSON NOT NULL,
  summary_json JSON NOT NULL,
  total BIGINT NOT NULL,
  as_of DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  KEY idx_promotion_list_snapshot_owner (actor_id, expires_at),
  CONSTRAINT chk_promotion_list_snapshot_total CHECK (total >= 0 AND total = JSON_LENGTH(rows_json)),
  CONSTRAINT chk_promotion_list_snapshot_time CHECK (expires_at > as_of),
  CONSTRAINT chk_promotion_list_snapshot_shape CHECK (JSON_TYPE(query_json)='OBJECT' AND JSON_TYPE(rows_json)='ARRAY' AND JSON_TYPE(summary_json)='OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
