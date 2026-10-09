CREATE TABLE IF NOT EXISTS nx_support_leaderboard_publication (
  id BIGINT NOT NULL AUTO_INCREMENT,
  stream_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  board VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  rank_month CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NULL,
  reference_month CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  currency VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  rank_currency VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
  scope VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  groups_key TEXT NOT NULL,
  definition_version VARCHAR(512) NOT NULL,
  source_version VARCHAR(512) NOT NULL,
  view_version VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  comparison_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  evaluated_at DATETIME(6) NOT NULL,
  published_at DATETIME(6) NOT NULL,
  state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload LONGTEXT NOT NULL,
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_support_leaderboard_view (stream_key,view_version),
  KEY ix_support_leaderboard_day (board,rank_month,rank_currency,scope,state,published_at,id),
  CONSTRAINT ck_support_leaderboard_state CHECK (state IN ('COMPLETE','PROVISIONAL')),
  CONSTRAINT ck_support_leaderboard_payload CHECK (JSON_VALID(payload)),
  CONSTRAINT ck_support_leaderboard_time CHECK (published_at >= evaluated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS nx_support_leaderboard_latest (
  stream_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  publication_id BIGINT NULL,
  PRIMARY KEY (stream_key),
  CONSTRAINT fk_support_leaderboard_latest FOREIGN KEY (publication_id)
    REFERENCES nx_support_leaderboard_publication(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
