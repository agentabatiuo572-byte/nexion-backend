-- Ticket headers represent the current dedicated advisor, including closed/archived tickets.
-- Apply after support_binding_s3 while the application is stopped. Historical message authors are immutable.
CREATE TABLE IF NOT EXISTS nx_support_migration (
  id VARCHAR(64) PRIMARY KEY,
  committed_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;

START TRANSACTION;
SET @ticket_owner_first = NOT EXISTS (
  SELECT 1 FROM nx_support_migration WHERE id='support-ticket-binding-owner-20261003'
);

INSERT IGNORE INTO nx_support_binding_pool(customer_id,reason,version,entered_at)
SELECT u.id,'MIGRATION_REVIEW',1,UTC_TIMESTAMP(6)
  FROM nx_user u
 WHERE @ticket_owner_first AND u.is_deleted=0
   AND EXISTS (SELECT 1 FROM nx_support_ticket t WHERE t.user_id=u.id AND t.is_deleted=0)
   AND NOT EXISTS (SELECT 1 FROM nx_support_agent_user_assignment x
                    WHERE x.user_id=u.id AND x.status='ACTIVE' AND x.is_deleted=0);

UPDATE nx_support_ticket t
LEFT JOIN nx_support_agent_user_assignment x
  ON x.user_id=t.user_id AND x.status='ACTIVE' AND x.is_deleted=0
LEFT JOIN nx_admin a ON a.id=x.agent_admin_id
   SET t.assigned_admin_id=x.agent_admin_id,
       t.assigned_admin_name=CASE WHEN x.agent_admin_id IS NULL THEN 'Unassigned'
           ELSE COALESCE(NULLIF(TRIM(a.nickname),''),NULLIF(TRIM(a.username),''),CAST(x.agent_admin_id AS CHAR)) END,
       t.version=t.version+1,
       t.updated_at=UTC_TIMESTAMP(6)
 WHERE @ticket_owner_first AND t.is_deleted=0
   AND NOT (t.assigned_admin_id <=> x.agent_admin_id AND t.assigned_admin_name <=>
       CASE WHEN x.agent_admin_id IS NULL THEN 'Unassigned'
           ELSE COALESCE(NULLIF(TRIM(a.nickname),''),NULLIF(TRIM(a.username),''),CAST(x.agent_admin_id AS CHAR)) END);

INSERT IGNORE INTO nx_support_migration(id,committed_at)
VALUES('support-ticket-binding-owner-20261003',UTC_TIMESTAMP(6));
COMMIT;
