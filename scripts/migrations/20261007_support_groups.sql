-- Structure only. Qualification adjudication is a separate, controlled cutover.
CREATE TABLE IF NOT EXISTS nx_support_group (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(120) NOT NULL, supervisor_admin_id BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ENABLED', version BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 CONSTRAINT ck_support_group_status CHECK(status IN ('ENABLED','DISABLED','ARCHIVED')),
 KEY ix_support_group_owner(supervisor_admin_id,status,id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS nx_support_group_owner_history (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, group_id BIGINT NOT NULL,
 supervisor_admin_id BIGINT NOT NULL, starts_at DATETIME(6) NOT NULL, ends_at DATETIME(6) NULL,
 version BIGINT NOT NULL, actor_admin_id BIGINT NULL, reason VARCHAR(200) NOT NULL,
 operation_id VARCHAR(128) NOT NULL,
 current_group_id BIGINT GENERATED ALWAYS AS (CASE WHEN ends_at IS NULL THEN group_id ELSE NULL END) STORED,
 UNIQUE KEY uk_support_group_owner_current(current_group_id),
 UNIQUE KEY uk_support_group_owner_operation(group_id,operation_id),
 CONSTRAINT ck_support_group_owner_interval CHECK(ends_at IS NULL OR ends_at>=starts_at)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS nx_support_group_member_history (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, agent_admin_id BIGINT NOT NULL, group_id BIGINT NULL,
 starts_at DATETIME(6) NOT NULL, ends_at DATETIME(6) NULL, version BIGINT NOT NULL,
 actor_admin_id BIGINT NULL, reason VARCHAR(200) NOT NULL, operation_id VARCHAR(128) NOT NULL,
 current_agent_id BIGINT GENERATED ALWAYS AS (CASE WHEN ends_at IS NULL THEN agent_admin_id ELSE NULL END) STORED,
 UNIQUE KEY uk_support_group_member_current(current_agent_id),
 UNIQUE KEY uk_support_group_member_operation(agent_admin_id,operation_id),
 KEY ix_support_group_member_group(group_id,ends_at,agent_admin_id),
 CONSTRAINT ck_support_group_member_interval CHECK(ends_at IS NULL OR ends_at>=starts_at)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS nx_support_account_qualification_history (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, admin_id BIGINT NOT NULL,
 qualification_kind VARCHAR(16) NOT NULL, state VARCHAR(16) NOT NULL,
 starts_at DATETIME(6) NOT NULL, ends_at DATETIME(6) NULL, version BIGINT NOT NULL,
 actor_admin_id BIGINT NULL, reason VARCHAR(200) NOT NULL, operation_id VARCHAR(128) NOT NULL,
 current_admin_id BIGINT GENERATED ALWAYS AS (CASE WHEN ends_at IS NULL THEN admin_id ELSE NULL END) STORED,
 UNIQUE KEY uk_support_qualification_current(current_admin_id,qualification_kind),
 UNIQUE KEY uk_support_qualification_operation(admin_id,qualification_kind,operation_id),
 CONSTRAINT ck_support_qualification_kind CHECK(qualification_kind IN ('SERVICE','SUPERVISOR')),
 CONSTRAINT ck_support_qualification_state CHECK(state IN ('ENABLED','DISABLED','REMOVED')),
 CONSTRAINT ck_support_qualification_interval CHECK(ends_at IS NULL OR ends_at>=starts_at)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS nx_support_customer_route_history (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, customer_id BIGINT NOT NULL, group_id BIGINT NULL,
 starts_at DATETIME(6) NOT NULL, ends_at DATETIME(6) NULL, version BIGINT NOT NULL,
 actor_admin_id BIGINT NULL, reason VARCHAR(200) NOT NULL, operation_id VARCHAR(128) NOT NULL,
 current_customer_id BIGINT GENERATED ALWAYS AS (CASE WHEN ends_at IS NULL THEN customer_id ELSE NULL END) STORED,
 UNIQUE KEY uk_support_customer_route_current(current_customer_id),
 UNIQUE KEY uk_support_customer_route_operation(customer_id,operation_id),
 KEY ix_support_customer_route_group(group_id,ends_at,customer_id),
 CONSTRAINT ck_support_customer_route_interval CHECK(ends_at IS NULL OR ends_at>=starts_at)
) ENGINE=InnoDB;
