-- Run with all old authentication writers stopped. DATETIME values below are UTC.
-- Synchronous login capture is fail-closed. A capture failure rolls back session issuance.
CREATE TABLE IF NOT EXISTS nx_support_maintenance_preference (
 customer_id BIGINT PRIMARY KEY, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 version BIGINT NOT NULL DEFAULT 1, updated_at DATETIME(6) NOT NULL,
 updated_by BIGINT NULL, reason VARCHAR(200) NULL
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS nx_support_activity_coverage (
 id TINYINT PRIMARY KEY, coverage_start_at DATETIME(6) NOT NULL,
 observed_through_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;
INSERT IGNORE INTO nx_support_activity_coverage VALUES (1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
CREATE TABLE IF NOT EXISTS nx_support_activity_state (
 customer_id BIGINT PRIMARY KEY, activity_seq BIGINT NOT NULL DEFAULT 0,
 last_effective_at DATETIME(6) NULL
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS nx_support_activity_event (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, customer_id BIGINT NOT NULL,
 seq BIGINT NOT NULL, source_ref VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 occurred_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_support_activity_source(source_ref),
 UNIQUE KEY uk_support_activity_seq(customer_id,seq),
 KEY ix_support_activity_time(customer_id,occurred_at)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS nx_support_maintenance_cycle (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, customer_id BIGINT NOT NULL,
 assignment_id BIGINT NOT NULL, agent_admin_id BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL, baseline_activity_seq BIGINT NOT NULL,
 opened_at DATETIME(6) NOT NULL, last_execution_at DATETIME(6) NOT NULL,
 closed_at DATETIME(6) NULL, success_event_id BIGINT NULL,
 open_customer_id BIGINT GENERATED ALWAYS AS (CASE WHEN status='OPEN' THEN customer_id ELSE NULL END) STORED,
 UNIQUE KEY uk_support_open_cycle(open_customer_id),
 UNIQUE KEY uk_support_cycle_success(customer_id,success_event_id),
 KEY ix_support_cycle_assignment(assignment_id,last_execution_at),
 KEY ix_support_cycle_agent(agent_admin_id,status,closed_at),
 CONSTRAINT ck_support_cycle_status CHECK(status IN ('OPEN','SUCCEEDED','STOPPED','TRANSFERRED'))
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS nx_support_maintenance_execution (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, customer_id BIGINT NOT NULL,
 assignment_id BIGINT NOT NULL, agent_admin_id BIGINT NOT NULL,
 cycle_id BIGINT NOT NULL, message_id BIGINT NOT NULL,
 command_key VARCHAR(128) NOT NULL, executed_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_support_execution_message(message_id),
 KEY ix_support_execution_customer(customer_id,id),
 KEY ix_support_execution_assignment(assignment_id,executed_at),
 KEY ix_support_execution_agent(agent_admin_id,executed_at)
) ENGINE=InnoDB;

-- CREATE TABLE IF NOT EXISTS does not upgrade an existing S4 table.
-- Keep repeated migration runs able to add the workbench MAX(executed_at) lookup index.
SET @support_execution_assignment_ddl = IF(
 EXISTS(SELECT 1 FROM information_schema.statistics
        WHERE table_schema=DATABASE() AND table_name='nx_support_maintenance_execution'
          AND index_name='ix_support_execution_assignment'),
 'SELECT 1',
 'ALTER TABLE nx_support_maintenance_execution ADD INDEX ix_support_execution_assignment(assignment_id,executed_at)'
);
PREPARE support_execution_assignment_stmt FROM @support_execution_assignment_ddl;
EXECUTE support_execution_assignment_stmt;
DEALLOCATE PREPARE support_execution_assignment_stmt;
