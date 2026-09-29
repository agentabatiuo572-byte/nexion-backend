-- Private support images only. No CMS URLs, no default upload policy, no historical rewrites.
CREATE TABLE IF NOT EXISTS nx_support_attachment (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  customer_id BIGINT NOT NULL,
  uploader_type VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  uploader_id BIGINT NOT NULL,
  assignment_id BIGINT NULL,
  client_upload_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  mime VARCHAR(32) NOT NULL,
  bytes BIGINT NOT NULL,
  width INT NOT NULL,
  height INT NOT NULL,
  object_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state VARCHAR(16) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  message_id BIGINT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
  UNIQUE KEY uk_support_attachment_upload(uploader_type,uploader_id,client_upload_id),
  UNIQUE KEY uk_support_attachment_message(message_id),
  UNIQUE KEY uk_support_attachment_object(object_key),
  KEY ix_support_attachment_expiry(state,expires_at),
  KEY ix_support_attachment_customer(customer_id)
) ENGINE=InnoDB;

-- Upload/cancel command keys persist independently of message command retention.
CREATE TABLE IF NOT EXISTS nx_support_attachment_command (
  actor_type VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  actor_id BIGINT NOT NULL,
  operation VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  command_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  attachment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
  PRIMARY KEY(actor_type,actor_id,operation,command_key)
) ENGINE=InnoDB;
