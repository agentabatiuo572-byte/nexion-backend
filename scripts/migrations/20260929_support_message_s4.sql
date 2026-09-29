-- Additive S4 metadata. Historical messages retain their original authors and times.
CREATE TABLE IF NOT EXISTS nx_support_human_message (
 message_id BIGINT NOT NULL PRIMARY KEY,
 customer_id BIGINT NOT NULL,
 assignment_id BIGINT NULL,
 actor_type VARCHAR(16) NOT NULL,
 actor_id BIGINT NOT NULL,
 client_message_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
 kind VARCHAR(16) NOT NULL,
 intent VARCHAR(16) NOT NULL,
 attachment_id VARCHAR(64) NULL,
 committed_at DATETIME(6) NOT NULL,
 payload_hash CHAR(64) NOT NULL,
 UNIQUE KEY uk_support_message_client (actor_type,actor_id,client_message_id),
 KEY ix_support_message_assignment (assignment_id,actor_type),
 KEY ix_support_message_customer (customer_id,committed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
