-- Account-side visibility only. Never delete messages, attachments, receipts or staff history.
CREATE TABLE IF NOT EXISTS nx_app_conversation_user_clear (
  user_id BIGINT NOT NULL,
  peer_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  through_message_id BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (user_id, peer_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Retain the old CID boundary even if its current staff assignment changes later.
CREATE TABLE IF NOT EXISTS nx_app_conversation_user_clear_member (
  user_id BIGINT NOT NULL,
  peer_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  conversation_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  through_message_id BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, peer_key, conversation_no),
  KEY idx_app_clear_conversation (user_id, conversation_no, through_message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
