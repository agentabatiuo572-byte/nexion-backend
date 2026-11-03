-- A row is inserted only by the authorized retirement after nonpayment or reversal is reconciled.
-- Normal settlement, day-key deduplication, original financial evidence and audit readers use the base tables.
CREATE TABLE IF NOT EXISTS nx_compute_retired_task (
  task_id BIGINT NOT NULL PRIMARY KEY, user_id BIGINT NOT NULL,
  task_no VARCHAR(96) COLLATE utf8mb4_0900_ai_ci NOT NULL,
  receipt_id BIGINT NULL, receipt_no VARCHAR(64) COLLATE utf8mb4_0900_ai_ci NULL,
  batch_ref VARCHAR(96) NOT NULL,
  archived_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_retired_task_no(task_no), UNIQUE KEY uk_retired_receipt_id(receipt_id),
  UNIQUE KEY uk_retired_receipt_no(receipt_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_compute_retired_ledger (
  ledger_id BIGINT NOT NULL PRIMARY KEY, task_id BIGINT NOT NULL,
  KEY ix_retired_ledger_task(task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE OR REPLACE SQL SECURITY INVOKER VIEW nx_visible_compute_task AS
 SELECT t.* FROM nx_compute_task t WHERE NOT EXISTS (SELECT 1 FROM nx_compute_retired_task a WHERE a.task_id=t.id);
CREATE OR REPLACE SQL SECURITY INVOKER VIEW nx_visible_compute_receipt AS
 SELECT r.* FROM nx_compute_receipt r WHERE NOT EXISTS (SELECT 1 FROM nx_compute_retired_task a WHERE a.receipt_id=r.id);
CREATE OR REPLACE SQL SECURITY INVOKER VIEW nx_visible_wallet_ledger AS
 SELECT l.* FROM nx_wallet_ledger l WHERE NOT EXISTS (SELECT 1 FROM nx_compute_retired_ledger a WHERE a.ledger_id=l.id);
CREATE OR REPLACE SQL SECURITY INVOKER VIEW nx_visible_earning_event AS
 SELECT e.* FROM nx_earning_event e WHERE NOT EXISTS (SELECT 1 FROM nx_compute_retired_task a WHERE a.user_id=e.user_id AND a.receipt_no=e.receipt_no);
CREATE OR REPLACE SQL SECURITY INVOKER VIEW nx_visible_simulated_task AS
 SELECT s.* FROM nx_simulated_task s WHERE NOT EXISTS (SELECT 1 FROM nx_compute_retired_task a WHERE a.user_id=s.user_id AND a.task_no=s.task_no COLLATE utf8mb4_0900_ai_ci);
