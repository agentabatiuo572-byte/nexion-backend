-- Separate, finite, scheduled Genesis draws. No campaign is enabled by migration.
CREATE TABLE IF NOT EXISTS nx_genesis_raffle_draw (
  draw_id VARCHAR(64) PRIMARY KEY, revision BIGINT NOT NULL DEFAULT 1,
  title_zh VARCHAR(160) NOT NULL,title_en VARCHAR(160) NOT NULL,title_vi VARCHAR(160) NOT NULL,
  starts_at DATETIME(6) NOT NULL,draw_at DATETIME(6) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',prizes_json JSON NOT NULL,
  opened_at DATETIME(6) NULL,drawn_at DATETIME(6) NULL,last_attempt_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  INDEX ix_raffle_due(status,draw_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_genesis_raffle_ticket (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NOT NULL,
  source_type VARCHAR(32) NOT NULL,source_id VARCHAR(192) NOT NULL,position_no VARCHAR(96) NULL,order_no VARCHAR(96) NULL,
  quantity BIGINT NOT NULL,used_quantity BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_raffle_source(user_id,source_type,source_id),INDEX ix_raffle_user(user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_genesis_raffle_entry (
  draw_id VARCHAR(64) NOT NULL,ticket_id BIGINT NOT NULL,user_id BIGINT NOT NULL,quantity BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(draw_id,ticket_id),INDEX ix_raffle_entry_user(user_id,draw_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_genesis_raffle_result (
  draw_id VARCHAR(64) NOT NULL,prize_id VARCHAR(64) NOT NULL,award_index INT NOT NULL,
  user_id BIGINT NOT NULL,ticket_id BIGINT NOT NULL,receipt_json JSON NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(draw_id,prize_id,award_index),INDEX ix_raffle_result_user(user_id,draw_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Preserve existing G7 entitlement identities. A forfeited position is excluded at read/entry/draw time.
INSERT IGNORE INTO nx_genesis_raffle_ticket(user_id,source_type,source_id,position_no,quantity,created_at)
SELECT user_id,'G7_BASE',ticket_no,position_no,quantity,issued_at FROM nx_g7_repurchase_ticket
WHERE is_deleted=0 AND status='AVAILABLE' AND quantity>0;
