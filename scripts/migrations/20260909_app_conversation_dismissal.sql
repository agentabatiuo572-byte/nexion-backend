-- Personal inbox visibility only. Conversation, message and receipt rows are retained.
CREATE TABLE IF NOT EXISTS nx_app_conversation_dismissal (
  user_id BIGINT NOT NULL,
  conversation_no VARCHAR(40) NOT NULL,
  through_message_id BIGINT NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, conversation_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
