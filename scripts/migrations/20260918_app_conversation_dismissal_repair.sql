-- Forward repair: nx_app_conversation_dismissal was created only by
-- 20260909_app_conversation_dismissal.sql, which the TEST automatic migration
-- runner recorded as BASELINE_NOT_REPLAYED (recorded, never replayed). The
-- canonical schema.sql baseline did not carry the table either, so a database
-- built from that baseline answers every dismissal read with a SQL error and
-- the App sees an opaque 500 instead of an empty personal marker list.
--
-- This file must stay a separate forward script: the runner verifies the
-- sha256 of every previously recorded file, so the historical migration can
-- never be edited or replayed. CREATE TABLE IF NOT EXISTS keeps it safe on a
-- database that already has the table (local dev applies the original file).
CREATE TABLE IF NOT EXISTS nx_app_conversation_dismissal (
  user_id BIGINT NOT NULL,
  conversation_no VARCHAR(40) NOT NULL,
  through_message_id BIGINT NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, conversation_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
