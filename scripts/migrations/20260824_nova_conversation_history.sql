CREATE TABLE IF NOT EXISTS nx_nova_conversation_turn (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  turn_id CHAR(36) NOT NULL,
  conversation_id CHAR(36) NOT NULL,
  language VARCHAR(8) NOT NULL,
  user_message VARCHAR(2000) NOT NULL,
  assistant_reply TEXT NOT NULL,
  provider VARCHAR(32) NOT NULL,
  model VARCHAR(128) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_nova_turn_user_turn (user_id, turn_id),
  KEY idx_nova_turn_user_conversation (user_id, conversation_id, id),
  KEY idx_nova_turn_user_latest (user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
